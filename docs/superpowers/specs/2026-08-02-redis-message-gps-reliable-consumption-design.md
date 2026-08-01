# Redis 消息与 GPS 可靠消费设计

## 目标

解决报警/GPS Redis 消息消费占用调度线程、内存队列无界、GPS Hash 全量扫描和统一 TTL 导致历史数据残留的问题，同时保证 GPS 开启轨迹保存时不丢失轨迹点。

## 现状与约束

- `RedisAlarmMsgListener` 和 `RedisGpsMsgListener` 使用本地 `ConcurrentLinkedQueue`，由 `@Scheduled` 方法批量消费。
- 当前调度器并发度为 5，消息批处理可能延迟 SIP 超时、播放超时和动态任务。
- GPS 当前写入一个按 `serverId` 区分的 Redis Hash，`getAllGpsMsgInfo()` 使用 `HVALS` 读取全部 value。
- `updateGpsMsgInfo()` 对整个 Hash 设置 TTL，并且会强制将 `stored` 设置为 `false`，导致已入库记录重复处理。
- Redis Pub/Sub 本身不提供持久化和 ACK。仅在监听器收到消息后再写 Stream，应用在两步之间崩溃时仍存在丢失窗口。
- `RedisGpsMsgListener` 当前只更新通道最新坐标，没有经过移动位置历史保存链路。

## 设计

### 1. Redis Stream 作为可靠消费缓冲

为每个 WVP 实例创建独立 Stream 和消费组：

```text
WVP_REDIS_STREAM_ALARM_<serverId>
WVP_REDIS_STREAM_GPS_<serverId>
consumer group: wvp
```

消息字段包括原始 JSON、消息来源和写入时间。消费成功后执行 `XACK`；处理失败不 ACK，由 Pending Entries List 保留并由 `XAUTOCLAIM` 定时重新领取。

现有 Pub/Sub 监听保留为兼容入口，收到消息后只负责写入对应 Stream。项目内的 GPS 发布端同步迁移到 Stream 写入；外部发布方继续使用 Pub/Sub 时，仍保留“回调后写 Stream”的短暂崩溃窗口，并通过日志明确暴露该兼容边界。

### 2. 独立消费线程

报警和 GPS 分别使用独立消费执行器，不在 `@Scheduled` 方法中解析、写库或发送 SIP。消费线程使用固定批量读取 Stream，并在每条消息处理成功后 ACK。

调度任务仅执行以下轻量操作：

- 回收超时 Pending 消息；
- 输出 Stream 积压、Pending 数量和消费耗时指标；
- 清理已经 ACK 且超过保留期限的 Stream 数据。

消费失败不丢消息。超过配置的重试次数后进入死信 Stream，并记录原始消息和异常原因。

### 3. GPS 最新位置缓存

不再使用按服务端聚合的 GPS Hash 作为新写入结构。每个通道使用独立 Redis value：

```text
WVP_STREAM_GPS_MSG_<serverId>:<gbId>
```

每个 value 单独设置 60 秒 TTL。任意通道上报不会刷新其他通道的过期时间。`PlatformServiceImpl` 查询最新位置时优先读取新 Key，并在迁移期回退读取旧 Hash。

### 4. GPS 轨迹与最新位置分离

GPS Stream 保留每一条上报消息，不能用本地队列满时丢弃或默认按通道覆盖。

消费流程如下：

```text
读取 GPS Stream 批次
  -> 解析 GPSMsgInfo
  -> 更新该通道最新位置 value
  -> save-position-history=true 时转换并批量写入移动位置历史表
  -> 最新位置和历史写入均成功后 ACK
```

当 `save-position-history=false` 时，允许在一个消费批次内按通道合并最新位置以减少数据库写入，但每条 Stream 消息仍必须在最新位置更新成功后 ACK。开启轨迹保存时，所有消息都必须保留并按事件顺序进入历史处理链路。

Redis GPS 路径需要补充 `GPSMsgInfo` 到 `MobilePosition` 的转换和通道 ID 映射，复用现有移动位置历史保存配置与批量写入能力。

### 5. 报警消息处理

报警 Stream 消费成功的条件是报警业务处理完成，包括必要的设备/平台查询和 SIP 报警发送。任一步骤失败都不 ACK，保留 Pending 供重试。

报警 Stream 只作为可靠传输缓冲，业务审计数据仍由报警数据库记录负责。已 ACK 消息按配置的时间或数量清理，未 ACK 消息不得被清理。

## 并发与一致性

- GPS 最新位置使用按通道 Key，新消息只覆盖同一通道的最新值，不会被旧 Hash 快照回写覆盖。
- GPS Stream 消息在历史写入和最新位置更新完成前不 ACK，进程崩溃后可以重试。
- Pending 回收必须使用消费组的 claim 机制，避免多个消费者同时处理同一条消息；数据库写入需要具备幂等能力，允许重试。
- Stream 的清理只针对已 ACK 且不再处于 Pending 的消息；清理失败不影响后续消费。
- 消费线程和调度线程隔离，消息突发只会增加 Stream 积压，不会阻塞 SIP/播放定时任务。

## 兼容与迁移

- 新代码优先读取按通道 GPS Key，未命中时读取旧 Hash。
- 新写入不再刷新旧 Hash 的 TTL。
- 提供一次性迁移任务，将旧 Hash 的最新 value 转换为按通道 Key；迁移完成后旧 Hash 按 TTL 自然淘汰。
- 保留 Pub/Sub 监听入口一段时间，逐步迁移项目内发布端和外部集成方到 Stream。

## 验收标准

- 报警/GPS 消费不再占用 `scheduled-*` 调度线程。
- 本地内存不使用无界消息队列；队列或 Stream 积压可观测。
- GPS 新路径不调用 `HVALS`，不存在按整个 Hash 设置 TTL 的写入。
- `stored=true` 不再被写回逻辑覆盖为 `false`。
- 最新位置按通道独立过期。
- 开启轨迹保存时，GPS 消息不会因内存缓冲区满而丢失，失败消息可以重试。
- 关闭轨迹保存时，同一批次可以合并最新位置，但消息 ACK 仍有明确成功条件。
- 数据库失败、Redis 重启和消费者重启后，未 ACK 消息可以继续处理。
- 测试覆盖 Stream ACK/Pending 重试、GPS 最新 Key TTL、轨迹保存开关、并发新旧 GPS、旧 Hash 兼容读取和调度线程隔离。

## 风险与边界

- 如果外部发布方只能使用 Redis Pub/Sub，无法完全消除“消息到达 Pub/Sub 后、写入 Stream 前进程崩溃”的窗口；需要在运维文档中说明并推动发布端迁移。
- 开启完整轨迹保存会增加 Redis Stream 和数据库写入量，需要配置历史保留时间、批量大小和死信告警。
- Stream 清理策略必须与轨迹数据库写入成功和消费组 Pending 状态协调，不能使用会删除未 ACK 消息的无条件截断。
