# Redis 消息与 GPS 可靠消费实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用 Redis Stream 和独立消费线程承载报警/GPS 消息，按通道维护最新 GPS，并在开启轨迹时可靠保存每个 GPS 点。

**Architecture:** Pub/Sub 监听器只将兼容消息追加到对应 Stream；独立虚拟线程以消费组读取 Stream，业务成功后 ACK，失败消息留在 Pending 并定期 claim 重试。GPS 最新值使用带独立 TTL 的按通道 Redis value，轨迹保存复用移动位置历史表链路；定时线程只做 Pending 回收和指标日志。

**Tech Stack:** Java 21、Spring Boot 3.4、Spring Data Redis Stream API、RedisTemplate/StringRedisTemplate、JUnit 5、Mockito。

## Global Constraints

- 只修改 `/data/shizy/wvp-GB28181-pro` 内的项目文件。
- 不修改、删除或提交 `t.cap`。
- Git 提交信息必须使用中文。
- 不恢复无界本地消息队列，不使用 `offer()` 失败即丢弃作为报警/GPS默认策略。
- 真实 Redis/ZLM 集成测试风险记录在 `docs`，单元测试不得依赖外部 Redis。

### Task 1: 增加 Stream 基础服务和配置

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/common/VideoManagerConstants.java`
- Create: `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisStreamConfig.java`
- Create: `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisStreamMessageService.java`
- Create: `src/test/java/com/genersoft/iot/vmp/conf/redis/RedisStreamMessageServiceTest.java`

**Interfaces:**
- `RedisStreamMessageService.append(String stream, String body, String source)` returns `RecordId`.
- `RedisStreamMessageService.read(String stream, String group, String consumer, int count, Duration block)` returns `List<MapRecord<String, String, String>>`.
- `RedisStreamMessageService.ack(String stream, String group, RecordId id)` acknowledges one record.
- `RedisStreamMessageService.reclaim(String stream, String group, Duration minIdle, int count)` returns claimed records.
- `RedisStreamMessageService.ensureGroup(String stream, String group)` is idempotent and creates the stream when absent.

- [ ] **Step 1: Write the failing tests**

  Test `append` writes `body`, `source`, and `publishedAt`; test `ensureGroup` ignores an existing `BUSYGROUP`; test `ack` delegates to `StreamOperations.acknowledge`; test `reclaim` only claims pending records older than the configured idle time.

- [ ] **Step 2: Run the tests and verify the expected failures**

  Run:

  ```bash
  mvn -DskipTests=false -Dtest=RedisStreamMessageServiceTest test
  ```

  Expected: compilation/test failure because the service and constants do not exist.

- [ ] **Step 3: Implement the minimal Stream service**

  Use `StringRedisTemplate.opsForStream()` with `StreamRecords.string(...)`. `ensureGroup` must catch only the Redis `BUSYGROUP` error; other Redis errors must propagate. `read` uses `Consumer.from(group, consumer)` and `ReadOffset.lastConsumed()`. `reclaim` reads Pending entries in a bounded range, filters by `getElapsedTimeSinceLastDelivery()`, then calls `claim` with the selected `RecordId` values.

- [ ] **Step 4: Run the tests and verify they pass**

  Run the same Maven command; expected result is all tests passing.

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/com/genersoft/iot/vmp/common/VideoManagerConstants.java src/main/java/com/genersoft/iot/vmp/conf/redis/RedisStreamConfig.java src/main/java/com/genersoft/iot/vmp/conf/redis/RedisStreamMessageService.java src/test/java/com/genersoft/iot/vmp/conf/redis/RedisStreamMessageServiceTest.java
  git commit -m "增加Redis Stream可靠消息基础服务"
  ```

### Task 2: 建立独立 Stream 消费执行器

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisStreamConsumer.java`
- Create: `src/test/java/com/genersoft/iot/vmp/conf/redis/RedisStreamConsumerTest.java`

**Interfaces:**
- `RedisStreamConsumer.start(String stream, String group, String consumer, Function<MapRecord<String, String, String>, Boolean> handler)` starts one virtual-thread loop.
- `RedisStreamConsumer.stop()` interrupts and joins the worker.

- [ ] **Step 1: Write the failing tests**

  Test that a successfully handled record is ACKed, a handler returning `false` is not ACKed, and a handler exception leaves the record pending. Test that `stop()` terminates the worker and that only one worker is started for one consumer.

- [ ] **Step 2: Run the tests and verify the expected failures**

  ```bash
  mvn -DskipTests=false -Dtest=RedisStreamConsumerTest test
  ```

  Expected: compilation failure because the consumer class does not exist.

- [ ] **Step 3: Implement the worker**

  The loop calls `ensureGroup`, first reclaims bounded stale Pending entries, then reads at most the configured batch size with a short block. It ACKs only when the handler returns `true`; it catches handler exceptions, logs the record ID, and continues without ACK. Redis connection errors use bounded backoff and do not terminate the worker.

- [ ] **Step 4: Run the tests and verify they pass**

  ```bash
  mvn -DskipTests=false -Dtest=RedisStreamConsumerTest test
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/com/genersoft/iot/vmp/conf/redis/RedisStreamConsumer.java src/test/java/com/genersoft/iot/vmp/conf/redis/RedisStreamConsumerTest.java
  git commit -m "增加Redis Stream独立消费执行器"
  ```

### Task 3: 迁移报警监听器到可靠 Stream

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/service/redisMsg/RedisAlarmMsgListener.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/conf/redis/RedisMsgListenConfig.java`
- Create: `src/test/java/com/genersoft/iot/vmp/service/redisMsg/RedisAlarmMsgListenerTest.java`

**Interfaces:**
- `RedisAlarmMsgListener.onMessage(Message, byte[])` appends the Pub/Sub body to the alarm Stream and performs no business processing.
- `RedisAlarmMsgListener.handleRecord(MapRecord<String, String, String>)` returns `true` only after the existing alarm routing and SIP send logic succeeds.

- [ ] **Step 1: Write the failing tests**

  Test that Pub/Sub delivery calls `append` without using a local queue, successful alarm handling returns `true`, and parsing/routing failure returns `false` so the Stream record is retried.

- [ ] **Step 2: Run the tests and verify the expected failures**

  ```bash
  mvn -DskipTests=false -Dtest=RedisAlarmMsgListenerTest test
  ```

- [ ] **Step 3: Implement the migration**

  Remove `ConcurrentLinkedQueue`, `@Scheduled executeTaskQueue`, and the unbounded batch list. Start one alarm consumer in `@PostConstruct`, stop it in `@PreDestroy`, and move the existing per-message body into `handleRecord`. Preserve existing exception logging, but return `false` for failures that must be retried. Do not ACK a message when any required platform/device SIP send fails.

- [ ] **Step 4: Run focused tests**

  ```bash
  mvn -DskipTests=false -Dtest=RedisAlarmMsgListenerTest,RedisStreamConsumerTest test
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/com/genersoft/iot/vmp/service/redisMsg/RedisAlarmMsgListener.java src/main/java/com/genersoft/iot/vmp/conf/redis/RedisMsgListenConfig.java src/test/java/com/genersoft/iot/vmp/service/redisMsg/RedisAlarmMsgListenerTest.java
  git commit -m "将报警消息迁移到Redis Stream可靠消费"
  ```

### Task 4: 改造 GPS 存储为最新值 Key 和 Stream 消费

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/storager/IRedisCatchStorage.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/storager/impl/RedisCatchStorageImpl.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/redisMsg/RedisGpsMsgListener.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/service/impl/PlatformServiceImpl.java`
- Create: `src/test/java/com/genersoft/iot/vmp/service/redisMsg/RedisGpsMsgListenerTest.java`
- Create: `src/test/java/com/genersoft/iot/vmp/storager/impl/RedisCatchStorageGpsTest.java`

**Interfaces:**
- `IRedisCatchStorage.updateGpsMsgInfo(GPSMsgInfo)` writes only the per-channel latest value with a 60-second TTL.
- `IRedisCatchStorage.getGpsMsgInfo(String gbId)` reads the new per-channel key and falls back to the legacy Hash during migration.
- `RedisGpsMsgListener.handleRecord(...)` updates latest position and, when configured, writes trajectory history before returning `true`.

- [ ] **Step 1: Write the failing tests**

  Test that updating one channel does not refresh another channel key, that legacy Hash fallback works, that the listener no longer calls `getAllGpsMsgInfo`, and that `stored=true` is not forced back to `false`. Test that concurrent newer GPS data is not overwritten by an older batch snapshot.

- [ ] **Step 2: Run the tests and verify the expected failures**

  ```bash
  mvn -DskipTests=false -Dtest=RedisGpsMsgListenerTest,RedisCatchStorageGpsTest test
  ```

  Expected: failures against the existing Hash/HVALS implementation.

- [ ] **Step 3: Implement latest-value storage**

  Add a per-channel key builder and use `opsForValue().set(key, gpsMsgInfo, Duration.ofSeconds(60))`. Remove the unconditional `setStored(false)` from the storage method. Keep the legacy Hash read fallback but do not write or refresh it. `getAllGpsMsgInfo` is removed from the GPS consumer path.

- [ ] **Step 4: Implement GPS Stream handling and history**

  Remove the local queue and 2-second HVALS task. Start the GPS Stream consumer through `RedisStreamConsumer`. Convert each record to `GPSMsgInfo`, update the latest key, and when `UserSetting.getSavePositionHistory()` is true, resolve the channel and batch-insert `MobilePosition` rows through the existing mobile-position mapper/service. ACK only after both writes succeed. When history is disabled, coalesce only within the current batch for latest-coordinate updates, while ACKing every successfully processed Stream record.

- [ ] **Step 5: Run focused tests**

  ```bash
  mvn -DskipTests=false -Dtest=RedisGpsMsgListenerTest,RedisCatchStorageGpsTest,RedisStreamConsumerTest test
  ```

- [ ] **Step 6: Commit**

  ```bash
  git add src/main/java/com/genersoft/iot/vmp/storager/IRedisCatchStorage.java src/main/java/com/genersoft/iot/vmp/storager/impl/RedisCatchStorageImpl.java src/main/java/com/genersoft/iot/vmp/service/redisMsg/RedisGpsMsgListener.java src/main/java/com/genersoft/iot/vmp/service/impl/PlatformServiceImpl.java src/test/java/com/genersoft/iot/vmp/service/redisMsg/RedisGpsMsgListenerTest.java src/test/java/com/genersoft/iot/vmp/storager/impl/RedisCatchStorageGpsTest.java
  git commit -m "改造GPS最新位置与轨迹可靠消费"
  ```

### Task 5: 发布端迁移、兼容迁移和可观测性

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/web/custom/service/CameraChannelService.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/common/VideoManagerConstants.java`
- Create: `src/main/java/com/genersoft/iot/vmp/gb28181/task/GpsLegacyHashMigrationTask.java`
- Modify: `docs/项目结构与开发协作指南.md`
- Create: `docs/2026-08-02-redis-message-gps-reliable-consumption-risks.md`

**Interfaces:**
- Internal GPS publishers write to the GPS Stream service and keep Pub/Sub only when compatibility mode is enabled.
- `GpsLegacyHashMigrationTask` migrates legacy Hash values to per-channel keys in bounded batches and never deletes a value that has not been copied successfully.

- [ ] **Step 1: Write the failing tests**

  Test internal GPS publishing calls Stream append, migration copies values in bounded batches, and migration leaves the old field when the new key write fails.

- [ ] **Step 2: Run the tests and verify the expected failures**

  ```bash
  mvn -DskipTests=false -Dtest=GpsLegacyHashMigrationTaskTest test
  ```

- [ ] **Step 3: Implement publisher and migration changes**

  Update `CameraChannelService` to append GPS JSON to the Stream. Add bounded migration and log Stream length, Pending count, retry and dead-letter metrics at INFO/WARN levels. Record the Pub/Sub compatibility crash window and required Redis/ZLM integration test risks in the docs file.

- [ ] **Step 4: Run focused tests**

  ```bash
  mvn -DskipTests=false -Dtest=GpsLegacyHashMigrationTaskTest,RedisGpsMsgListenerTest test
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add src/main/java/com/genersoft/iot/vmp/web/custom/service/CameraChannelService.java src/main/java/com/genersoft/iot/vmp/common/VideoManagerConstants.java src/main/java/com/genersoft/iot/vmp/gb28181/task/GpsLegacyHashMigrationTask.java docs/2026-08-02-redis-message-gps-reliable-consumption-risks.md src/test/java/com/genersoft/iot/vmp/gb28181/task/GpsLegacyHashMigrationTaskTest.java
  git commit -m "迁移GPS发布端并增加可靠消费风险说明"
  ```

### Task 6: 全量验证

**Files:**
- No new production files.

- [ ] **Step 1: Run formatting and compile checks**

  ```bash
  mvn -DskipTests=false -Dtest='!*IntegrationTest' test
  mvn -DskipTests=true package
  ```

- [ ] **Step 2: Review Redis command usage**

  Run:

  ```bash
  rg -n "getAllGpsMsgInfo|opsForHash\(\)\.values|WVP_STREAM_GPS_MSG_PREFIX" src/main/java src/test/java
  ```

  Expected: no GPS consumer path calls `HVALS`; legacy references exist only in migration/fallback code.

- [ ] **Step 3: Run `git diff --check` and inspect status**

  ```bash
  git diff --check
  git status --short
  ```

- [ ] **Step 4: Commit verification results if code changes remain**

  ```bash
  git commit -am "验证Redis消息与GPS可靠消费改造"
  ```
