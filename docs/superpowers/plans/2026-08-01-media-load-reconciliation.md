# 媒体节点负载计数修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复媒体节点启动时的真实活跃流计数，并通过节点实现和周期性对账避免 Hook 丢失、重复 Hook、进程重启导致 Redis ZSET 负载长期漂移。

**Architecture:** 在 `IMediaNodeServerService` 增加节点无关的活跃流计数接口；ZLM 实现只统计 `rtsp` 媒体并按 `(app, stream)` 去重，ABL 实现按其媒体列表去重。`MediaServerServiceImpl` 只负责将成功的对账结果写入 ZSET，查询放入异步任务且带超时，失败时保留已有分数而不把失败误判为 0；节点上线和固定周期触发同一套对账逻辑。

**Tech Stack:** Java 21、Spring `TaskExecutor`/`@Scheduled`、Redis ZSET、ZLM/ABL REST API、JUnit 5、Mockito。

## Global Constraints

- 只修改 `/data/shizy/wvp-GB28181-pro` 内的项目文件；不得修改、删除或提交 `t.cap`。
- 修改前后执行 `git status --short`，保留用户已有改动。
- Git 提交信息必须使用中文。
- ZLM 统计口径必须与现有 `MediaArrivalEvent`/`MediaDepartureEvent` 的 `schema=rtsp` 过滤一致。
- 媒体节点 HTTP 查询不得阻塞 SIP、定时调度或节点上线主线程；查询失败不得覆盖已有有效计数。

## 文件与职责

- 修改 `src/main/java/com/genersoft/iot/vmp/media/service/IMediaNodeServerService.java`：声明节点无关的活跃流计数能力。
- 新增 `src/main/java/com/genersoft/iot/vmp/media/service/bean/MediaStreamCountResult.java`：统一表示查询成功、计数和失败原因，区分“成功且为 0”和“查询失败”。
- 修改 `src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMMediaNodeServerService.java`：调用 ZLM `getMediaList(schema=rtsp)`，按 `(app,stream)` 去重；同时修复循环中固定读取下标 0 的错误。
- 修改 `src/main/java/com/genersoft/iot/vmp/media/abl/ABLMediaNodeServerService.java`：调用 ABL 媒体列表并按业务流标识去重。
- 修改 `src/main/java/com/genersoft/iot/vmp/media/service/impl/MediaServerServiceImpl.java`：删除固定返回 0 的私有方法，异步执行初始化/周期对账并原子写入 ZSET。
- 修改 `src/main/java/com/genersoft/iot/vmp/conf/MediaConfig.java` 与 `src/main/resources/配置详情.yml`：增加对账周期和查询超时配置，默认值分别为 60 秒和 3 秒。
- 新增或修改测试：`src/test/java/com/genersoft/iot/vmp/media/zlm/ZLMMediaNodeServerServiceTest.java`、`src/test/java/com/genersoft/iot/vmp/media/abl/ABLMediaNodeServerServiceTest.java`、`src/test/java/com/genersoft/iot/vmp/media/service/impl/MediaServerServiceImplTest.java`。
- 更新 `docs` 中的真实环境集成测试风险记录，说明需要 ZLM/ABL 节点才能验证接口返回和 Hook 对账。

### Task 1: 定义节点计数契约

**Files:**
- Create: `src/main/java/com/genersoft/iot/vmp/media/service/bean/MediaStreamCountResult.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/media/service/IMediaNodeServerService.java`

**Interfaces:**
- Produces `MediaStreamCountResult countActiveStreams(MediaServer mediaServer)`。
- `MediaStreamCountResult.success(int count)` 表示查询成功（允许 `count=0`）。
- `MediaStreamCountResult.failure(String reason)` 表示节点不可达、返回码非 0、数据为空异常或解析失败。

- [x] **Step 1: Write the result type and failing contract test**

  建立不可变结果类型，保证 `failure` 不携带可被误写入 ZSET 的数量；测试断言成功 0 与失败状态可区分。

- [x] **Step 2: Add the interface method**

  在 `IMediaNodeServerService` 中加入：

  ```java
  MediaStreamCountResult countActiveStreams(MediaServer mediaServer);
  ```

- [x] **Step 3: Run the compile/test check**

  Run: `mvn -q -DskipTests compile`

  Expected: FAIL，提示 ZLM/ABL 实现类尚未实现新接口方法；该失败确认契约已被所有实现感知。

## Task 2: 实现 ZLM/ABL 计数

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMMediaNodeServerService.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMRESTfulUtils.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/media/abl/ABLMediaNodeServerService.java`
- Test: `src/test/java/com/genersoft/iot/vmp/media/zlm/ZLMMediaNodeServerServiceTest.java`
- Test: `src/test/java/com/genersoft/iot/vmp/media/abl/ABLMediaNodeServerServiceTest.java`

**Interfaces:**
- Consumes `MediaStreamCountResult` from Task 1.
- Produces provider-specific counts with identical success/failure semantics。

- [x] **Step 1: Add failing ZLM tests**

  使用 Mockito 构造两个不同 schema 的同一 `(app,stream)`、两个不同流和非 0 响应，断言：

  ```java
  assertEquals(2, service.countActiveStreams(mediaServer).getCount());
  assertTrue(service.countActiveStreams(failedServer).isFailure());
  ```

  只有 `schema=rtsp` 记录参与计数，重复记录只计一次。

- [x] **Step 2: Implement ZLM count query**

  调用带 schema 的同步 API：

  ```java
  zlmresTfulUtils.getMediaList(mediaServer, null, null, "rtsp", null)
  ```

  对每条数据提取 `app` 与 `stream`，放入 `Set<String>` 后返回集合大小；ZLM 返回 null、非 0 code、data null 或异常时返回 `failure(reason)`。

- [x] **Step 3: Fix ZLM list iteration**

  将 `ZLMMediaNodeServerService.java:199` 的 `getJSONObject(0)` 改为 `getJSONObject(i)`，新增多流测试确认每个元素只处理一次。

- [x] **Step 4: Implement ABL count and tests**

  调用 `ablresTfulUtils.getMediaList(mediaServer, null, null)`，按 ABL 返回的业务流键去重；非 0 code、null 结果或异常返回 `failure(reason)`，空列表返回成功 0。

- [x] **Step 5: Run provider tests**

  Run: `mvn -q -Dtest=ZLMMediaNodeServerServiceTest,ABLMediaNodeServerServiceTest test`

  Expected: PASS。

## Task 3: 接入节点上线和周期对账

**Files:**
- Modify: `src/main/java/com/genersoft/iot/vmp/media/service/impl/MediaServerServiceImpl.java`
- Modify: `src/main/java/com/genersoft/iot/vmp/conf/MediaConfig.java`
- Modify: `src/main/resources/配置详情.yml`
- Test: `src/test/java/com/genersoft/iot/vmp/media/service/impl/MediaServerServiceImplTest.java`

**Interfaces:**
- Consumes `countActiveStreams` from Task 1/2。
- Produces `reconcileMediaServerLoad(MediaServer)`，只在查询成功时执行 `ZADD onlineKey mediaServerId count`。

- [x] **Step 1: Write failing service tests**

  覆盖以下行为：

  1. 首次上线先创建 ZSET 成员，再异步查询成功后写入实际数量。
  2. 查询成功且数量为 0 时写入 0。
  3. 查询失败时保留已有分数，不将失败写成 0。
  4. 重复调用对账只保留最新绝对值，不使用 `incrementScore` 累加。

- [x] **Step 2: Replace the placeholder**

  删除 `private int getMediaList(...) { return 0; }`，将 `resetOnlineServerItem` 改为：

  ```java
  ensureOnlineZsetMember(serverItem.getId());
  submitLoadReconciliation(serverItem);
  ```

  `submitLoadReconciliation` 使用 Spring `TaskExecutor` 执行节点 HTTP 查询，并在成功回调中使用 `ZADD` 写绝对值；任务捕获运行时异常并记录节点 ID、类型和原因。

- [x] **Step 3: Add bounded query configuration**

  在 `MediaConfig` 增加：

  ```java
  @Value("${media.load-reconcile-interval-ms:60000}")
  private long loadReconcileIntervalMs;

  @Value("${media.load-reconcile-timeout-sec:3}")
  private int loadReconcileTimeoutSec;
  ```

  ZLM/ABL REST 查询使用该超时；不得复用无界或无限等待的客户端配置。

- [x] **Step 4: Add periodic reconciliation**

  在 `MediaServerServiceImpl` 增加固定延迟任务，遍历当前在线节点并提交同一 `submitLoadReconciliation`；使用 `AtomicBoolean` 或按节点 key 的 `ConcurrentHashMap` 防止同一节点的对账任务重入。

- [x] **Step 5: Preserve event counters as fast path**

  保留 `MediaArrivalEvent`/`MediaDepartureEvent` 的增减以提供即时近似值，但周期绝对对账负责纠偏；对账失败不改变当前分数。不得在注销事件中把分数减到负数，必要时用 Redis Lua 将结果下限限制为 0。

- [x] **Step 6: Run service tests**

  Run: `mvn -q -Dtest=MediaServerServiceImplTest test`

  Expected: PASS。

## Task 4: 完成回归验证与文档

**Files:**
- Modify: `docs/项目结构与开发协作指南.md` 或新增专门的媒体负载对账说明
- Create: `docs/真实环境集成测试风险记录.md`

- [x] **Step 1: Run focused regression tests**

  Run: `mvn -q -Dtest=ZLMMediaNodeServerServiceTest,ABLMediaNodeServerServiceTest,MediaServerServiceImplTest test`

- [ ] **Step 2: Run the full test suite**

  Run: `mvn -q test`

  Expected: PASS；如果环境缺少 Redis/ZLM/ABL，记录具体失败原因，不修改测试为跳过真实依赖。

- [x] **Step 3: Record integration risks**

  文档必须记录：ZLM `getMediaList(schema=rtsp)`、ABL 媒体列表、重复 Hook、节点重启、节点 API 超时/非 0 响应、多节点同时对账等场景需要真实环境验证。

- [x] **Step 4: Verify repository state**

  Run: `git status --short`

  确认只包含本方案涉及文件，`t.cap` 未被修改。

- [ ] **Step 5: Commit in Chinese**

  ```bash
  git add src/main/java src/test/java src/main/resources docs
  git commit -m "修复媒体节点负载计数初始化与对账"
  ```

## Self-Review Checklist

- ZLM 只计数 RTSP 且按业务流去重，符合现有 Hook 增减口径。
- 成功返回 0 与查询失败不会混淆。
- 节点 HTTP 查询在异步任务和有限超时内执行，不阻塞 SIP/调度线程。
- 周期绝对对账可以修复 Hook 丢失、重复 Hook、服务重启造成的漂移。
- `ZLMMediaNodeServerService` 不再重复读取第一个媒体列表元素。
- 真实环境集成风险已写入 `docs`，单元测试覆盖 API 成功、空数据、异常和重复流。
