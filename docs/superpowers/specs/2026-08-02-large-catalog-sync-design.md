# 大规模 GB28181 目录同步设计

**日期：** 2026-08-02  
**状态：** 待用户审阅  
**适用入口：** `DeviceQuery.devicesSync()` 及其 Catalog 响应链路

## 1. 背景与目标

当前目录同步链路把每个通道、区域和分组对象逐条写入 Redis Hash，完成后再逐条读取并批量写入数据库。单设备达到几十万通道时，会产生大量 Redis 网络往返、JVM 全量对象和 key 集合、长时间数据库事务，并且“5 秒无新响应即入库”的策略可能把不完整目录覆盖到数据库。

本设计的目标是：

1. 支持单设备几十万通道、多个设备并发同步。
2. 同步过程中继续提供旧目录，只有完整快照验证通过后才切换新目录。
3. 目录数据按批次流转，JVM 内存不随目录总量线性增长。
4. SIP 响应确认、目录解析、数据库写入相互隔离，避免大目录阻塞普通 SIP 业务。
5. 支持重复、乱序、迟到响应，以及数据库、Redis、进程重启等异常恢复。

## 2. 非目标

- 不改变 GB28181 Catalog 请求和响应 XML 协议。
- 不在本次设计中改造前端目录查询接口的业务语义。
- 不把 Redis 继续作为几十万条目录对象的长期暂存层。
- 不在本阶段处理平台级联的全量目录同步，只保留现有级联事件接口。

## 3. 现有链路与问题边界

现有链路为：

```text
DeviceQuery.devicesSync()
  -> DeviceServiceImpl.devicesSync()
  -> DeviceServiceImpl.sync()
  -> SIPCommander.catalogQuery()
  -> SIPProcessorObserver.sipTaskExecutor
  -> CatalogResponseMessageHandler.handForDevice()
  -> CatalogDataManager Redis Hash
  -> resetChannels()/RegionService/GroupService
```

关键问题：

- `CatalogDataManager.put()`、`getDeviceChannelList()` 和清理逻辑按对象逐条访问 Redis。
- `CatalogData` 保存全部 Redis key，并使用非线程安全的 `HashSet`。
- `resetChannels()` 先全量查询旧通道，再同时构造多个全量 Map/List。
- 区域、分组查询和写入没有统一分块策略。
- `timerTask()` 在 5 秒无新响应时直接落库部分数据。
- Catalog 处理和 SIP 其他业务共用 `sipTaskExecutor`，队列满时的 `CallerRunsPolicy` 可能反向占用 SIP 调用线程。

## 4. 总体架构

采用“数据库暂存表 + 目录版本切换 + 专用目录处理线程池”的架构：

```text
设备 Catalog 响应
  -> 快速回复 SIP 200
  -> Catalog 接收队列
  -> XML 解析/坐标转换
  -> 分批写入同步暂存表
  -> 校验 syncId、SN、唯一通道数
  -> 完整快照事务提交
  -> 切换设备当前目录版本
  -> 异步清理旧版本和暂存数据
```

Redis 只保存锁、会话状态和过期索引；不再保存几十万条 `DeviceChannel`、`Region`、`Group` 对象。

## 5. 同步会话模型

每次同步生成唯一 `syncId`。会话状态为：

```text
READY -> RECEIVING -> COMMITTING -> SUCCESS
                 \-> FAILED/TIMEOUT
```

会话至少包含：

```text
syncId
deviceId
deviceDbId
sn
expectedTotal
receivedUniqueCount
lastReceiveTime
firstReceiveTime
deadline
status
errorMessage
```

### 5.1 并发控制

- 使用 `catalog:sync:lock:{deviceId}` 作为设备级 Redis 分布式锁。
- 锁值包含 `syncId`，释放时必须校验锁归属。
- 锁设置 TTL，并由活动会话续期，避免进程崩溃造成永久占用。
- 同一设备只能有一个活动同步；不同设备受全局并发上限限制。
- `deviceId.intern()` 只保留为单 JVM 的兼容保护，不作为跨节点一致性保证。

### 5.2 Redis 状态

建议使用以下 key：

```text
catalog:sync:{syncId}                 会话元数据
catalog:sync:device:{deviceId}        当前 syncId
catalog:sync:lock:{deviceId}          分布式锁
catalog:sync:expire                    过期会话 ZSET
```

状态查询只读取会话元数据中的计数和时间，不再遍历 `CatalogDataManager.dataMap` 或 Redis Hash。

## 6. 数据库暂存表

新增 `wvp_device_channel_sync`，字段与 `wvp_device_channel` 的目录字段保持一致，并增加：

```text
sync_id
device_db_id
device_gb_id
channel_device_id
```

唯一约束：

```text
(sync_id, channel_device_id)
```

区域和分组可分别增加对应暂存表；如果实际规模始终很小，也必须使用 `sync_id` 或目录版本字段，不能与通道快照跨版本混用。

暂存表写入要求：

- Catalog 响应按 500～1000 条分批 UPSERT。
- 重复通道覆盖同一 `sync_id + channel_device_id` 记录，不增加唯一计数。
- 不在 JVM 中累计全部通道。
- 暂存表必须有 `sync_id`、`device_db_id`、`device_gb_id`、`channel_device_id` 索引。
- 清理任务按 `sync_id` 分批删除，避免单次大删除。

## 7. 全量快照提交与版本边界

基线实现不直接改造所有目录查询，而是把 `syncId` 作为新快照版本：

1. 创建新的 `syncId`。
2. 所有 Catalog 数据写入暂存表。
3. 根据暂存表查询唯一通道数并与 `SumNum` 比较。
4. 在一个数据库事务中，将暂存数据批量物化到现有 `wvp_device_channel`、区域和分组表，并删除本次快照中不存在的旧记录。
5. 事务提交点即为逻辑上的“版本切换”：事务提交前查询继续看到旧目录，提交后看到完整新目录。
6. 事务成功后标记 `SUCCESS`，后台异步清理暂存数据。

MySQL InnoDB、PostgreSQL 等目标数据库都必须使用事务一致性读，禁止在物化过程中提交中间批次。现有通道主键、播放状态、级联关联等字段要按保留策略更新，不能通过删除重建破坏外部引用。

后续如果单次物化事务仍然过长，再增加 `wvp_device_catalog_version(active_version)` 和版本化快照表，让查询按激活版本读取；该增强需要同步改造目录查询和级联关联，不能在没有迁移设计的情况下直接增加 `active_version` 字段。

## 8. SIP 与线程模型

### 8.1 SIP 接收线程

Catalog 响应处理必须拆成两步：

1. 尽快发送 SIP 200，不能等待 XML 解析或数据库写入。
2. 将原始 XML 和会话标识投递到专用 Catalog 队列。

SIP 200 的发送不能与大目录入库共用可能长期繁忙的目录工作线程。

### 8.2 Catalog 专用执行器

配置项：

```text
catalog.executor.core-size
catalog.executor.max-size
catalog.executor.queue-capacity
catalog.sync.max-concurrent-devices
catalog.sync.batch-size
```

队列必须有界，但禁止静默丢弃目录页：

- 队列未满：正常投递。
- 队列接近上限：限制新的大目录同步并返回繁忙状态。
- 当前同步任务：允许有限等待或受控同步处理，确保当前目录页不丢失。
- 拒绝策略必须记录设备、syncId、队列深度和原因。

目录线程池不得使用会把大任务反向执行到 SIP 接收线程的无条件 `CallerRunsPolicy`。

## 9. 响应完整性与超时

不再使用固定“5 秒没有新响应就提交”的规则。

建议配置三类超时：

```text
首包超时：例如 2 分钟
分包空闲超时：例如 30 秒，可按设备调整
总同步超时：例如 30 分钟，或按 SumNum 动态计算
```

只有满足以下条件才允许提交：

```text
暂存表唯一通道数 == SumNum
```

计数以数据库暂存表为准，不以并发内存计数为最终依据。

特殊情况：

- `SumNum=0`：创建空快照，完整提交后清理旧通道，不能在收到首个空响应时绕过会话提交逻辑。
- 丢包、解析失败、重复或数量不一致：标记失败/超时，保留旧版本。
- 迟到响应：校验 `deviceId + SN + syncId`，旧会话响应不能写入新会话。

## 10. 数据库提交与回滚

通道、区域和分组应使用同一 `syncId/version`。基线实现的提交事务中：

1. 锁定并校验当前活动会话。
2. 校验暂存数据数量和必要字段。
3. 批量物化暂存数据到现有目录表。
4. 批量更新父节点关系和子节点计数。
5. 在同一事务内完成新旧目录的物化和删除。
6. 提交事务后再标记同步成功。

任何一步失败都不能切换版本。失败批次可有限次重试，超过次数后标记 `FAILED`，并保留旧目录。

通道状态变化的级联通知不能为每个通道单独查询上级平台。应先批量查询发生变化的通道及其关联平台，再按平台和批次发布事件。

区域和分组的 `IN (...)` 查询、INSERT、父节点更新也必须按 500～1000 条分块，避免 SQL 文本和参数数量超过数据库限制。

## 11. 失败恢复与清理

增加两个后台任务：

### 11.1 会话恢复任务

按 `catalog:sync:expire` 和数据库会话表扫描：

- 恢复进程重启前仍处于 `RECEIVING` 的任务；
- 判断是否已超过总超时；
- 超时任务标记失败并释放锁；
- 已完成但未切换版本的任务重新执行提交校验。

### 11.2 暂存数据清理任务

- 只清理 `SUCCESS`、`FAILED`、`TIMEOUT` 且超过保留时间的 `syncId`；
- 分批删除，每轮限制最大行数；
- 清理失败记录告警，下一轮重试；
- 不允许通过全表删除影响其他设备的活动同步。

## 12. 监控与日志

至少记录和监控：

```text
syncId、deviceId、SN
SumNum、暂存唯一数量、重复数量、解析失败数量
Catalog 队列深度、最大等待时间、拒绝次数
批量写入耗时、提交耗时、版本切换耗时
成功、失败、超时、重试次数
活动同步设备数、暂存表行数、清理积压量
```

日志必须带 `syncId`，避免多个设备的大目录日志互相混淆。

## 13. 分阶段实施

### 阶段一：先修复正确性

- 引入 `syncId` 和会话状态。
- 禁止 5 秒空闲直接提交不完整数据。
- 校验重复、乱序、迟到响应。
- 为会话集合和计数增加并发保护。
- 失败时保留原目录。

### 阶段二：降低 Redis 和内存压力

- 暂时使用 Redis pipeline 或批量读写。
- 取消逐条 HGET/HDEL。
- 限制活动同步并发数和批次内存。
- 将目录解析和入库迁移到专用执行器。

### 阶段三：引入数据库暂存表

- 增加通道、区域、分组暂存表。
- Catalog 响应按批次写入暂存表。
- Redis 仅保存会话、锁和过期索引。
- 增加服务重启恢复任务。

### 阶段四：按事务耗时决定是否引入版本指针

- 先以单事务物化作为默认版本切换边界。
- 监控物化事务耗时、锁等待和查询延迟。
- 只有事务过长仍影响线上查询时，才增加设备当前版本指针和版本化快照查询。
- 版本化查询改造完成后，再切换为指针更新和异步清理旧版本。

## 14. 验收标准

必须覆盖：

- 单设备 10 万、50 万通道。
- 5～10 个设备并发同步。
- Catalog 响应乱序、重复和迟到。
- 丢失一个分包、分包间隔超过空闲超时。
- `SumNum=0`。
- 数据库批量写入失败、Redis 短暂不可用。
- WVP 在接收和提交阶段重启。
- 同步期间查询目录，确认始终返回旧版本或完整新版本。

核心指标：

1. 同步期间旧目录不被部分数据覆盖。
2. 不完整目录不会触发删除旧通道。
3. JVM 内存主要由批次大小决定，而不是通道总量决定。
4. Redis 操作量与批次数量相关，不再与通道数一一对应。
5. SIP 200 回复不等待目录入库。
6. 单个大设备不会阻塞其他设备的 SIP 业务。

## 15. 关键风险与取舍

- 引入暂存表需要数据库迁移，单事务物化对现有查询改动较小；属于中等规模改造。
- 版本指针和版本化查询是可选增强，会增加历史版本清理、索引维护和级联关联迁移成本，但可以进一步缩短线上物化锁定时间。
- 专用执行器不能通过丢弃任务实现限流；必须使用拒绝新同步、有限等待或持久化暂存保证目录页不丢失。
- 现有通道状态、播放状态、级联关联字段在版本化写入时必须明确保留策略，不能简单覆盖。
