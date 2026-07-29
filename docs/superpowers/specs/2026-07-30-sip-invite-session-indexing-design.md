# SIP Invite Session 索引与生命周期设计

**日期：** 2026-07-30  
**状态：** 已确认，进入实现阶段  
**基线提交：** `08bfda9`

## 目标

解决 `SipInviteSessionManager` 的两个问题：

1. 按设备查询使用 Call-ID Hash 的全量 `HVALS`，随会话数量增长产生 O(N) Redis 和网络开销。
2. Stream Hash 与 Call-ID Hash 分两次写入且没有 TTL，异常退出或并发替换可能留下永久孤儿和双索引不一致。

本次改造不改变 SIP 信令、BYE 处理、RTP 收流和现有业务接口，只替换 SIP Invite Session 的 Redis 存储、查询和回收机制。

## 现状与边界

- 旧数据位于 `VMP_SIP_INVITE_SESSION_INFO:CALL_ID:<serverId>` 和 `VMP_SIP_INVITE_SESSION_INFO:STREAM:<serverId>` 两个 Hash。
- 旧 Hash 在迁移期间只读，不再作为新会话写入目标。
- 新旧版本节点混跑不属于本次兼容边界；部署新版本前需要完成节点升级。新版本可以读取并迁移旧数据。
- `t.cap` 等抓包文件不参与本功能提交。
- 不通过给整个 Hash 设置 TTL 的方式处理过期；Hash 根 TTL 会导致同一服务器的所有会话一起失效。

## 新数据模型

每个会话使用 Call-ID 作为唯一标识。`serverId` 参与所有 Key，避免多 WVP 实例相互覆盖。

```text
VMP_SIP_INVITE_SESSION_V2:DATA:<serverId>:<callId>
  String -> SsrcTransaction，带安全 TTL

VMP_SIP_INVITE_SESSION_V2:STREAM:<serverId>:<app><stream>
  String -> callId，带与主记录相同的安全 TTL

VMP_SIP_INVITE_SESSION_V2:DEVICE:<serverId>:<deviceId>
  Set    -> callId 集合

VMP_SIP_INVITE_SESSION_V2:EXPIRE:<serverId>
  ZSET   -> member=callId，score=expireAtMillis
```

- DATA Key 是唯一会话内容来源，避免同一个 `SsrcTransaction` 在两个 Hash 中重复保存。
- STREAM Key 只保存 Call-ID，按流查询为两次 O(1) 读取。
- DEVICE Set 使设备查询复杂度变为 O(K)，K 为该设备的会话数。
- EXPIRE ZSET 用于清理没有正常 BYE/停止回调的会话；DATA 和 STREAM 的 TTL 是 Redis 层的最后兜底。
- 默认安全 TTL 为 7 天，通过 `user-settings.sip-invite-session-ttl-seconds` 配置。该 TTL 是异常回收上限，不代表主动终止正常的长期会话；正常 BYE/停止仍立即删除。

## 写入与删除流程

### 新会话写入

`put()` 使用 `SessionCallback` 执行 `WATCH -> MULTI -> EXEC`：

1. 监听当前 Stream Key 和当前 Call-ID DATA Key。
2. 读取当前 Stream owner；若存在旧 owner，读取旧会话的设备、流信息并监听对应 Key。
3. 事务内删除旧 owner 的 DATA、DEVICE 成员和 EXPIRE 成员。
4. 写入新的 DATA、STREAM、DEVICE 和 EXPIRE 索引。
5. `EXEC` 返回空时重试，最多 3 次；重试耗尽则记录错误且不报告写入成功。

所有新索引在同一个 Redis 事务中提交，避免新结构出现半写入状态。

### 按流删除

`removeByStream()` 先读取 STREAM owner，并监听 STREAM/DATA/DEVICE Key。事务中只有当 STREAM 仍指向目标 Call-ID 时才删除 DATA、STREAM、DEVICE 成员和 EXPIRE 成员。这样旧会话的延迟清理不会删除已经替换的新会话。

### 按 Call-ID 删除

`removeByCallId()` 从 DATA 读取流和设备信息，监听相关 Key 后执行同样的 owner 校验和全量索引删除。重复删除返回幂等成功。

## 兼容迁移

- `getSsrcTransactionByStream()` 和 `getSsrcTransactionByCallId()` 先读 V2；V2 未命中时读取对应旧 Hash。
- 旧记录读取成功后立即执行单条会话迁移；迁移成功并验证 DATA、STREAM、DEVICE、EXPIRE 均存在后，才删除旧 Hash 中对应字段。
- `getSsrcTransactionByDeviceId()` 在 V2 Device Set 不存在或为空时，使用旧 Call-ID Hash 的 `HSCAN` 分批兼容读取并迁移匹配记录；新路径不再使用 `HVALS`。
- 新增迁移任务定期扫描旧 Call-ID Hash，按批次迁移可解析记录。无法解析的脏数据只记录告警，不自动删除。
- 迁移过程中旧数据仍可被读取；迁移任务和按需迁移均可重复执行。

## 过期清理

新增 `SipInviteSessionCleanupTask`，按固定周期从当前服务器的 EXPIRE ZSET 取出到期 Call-ID：

1. 对每个 Call-ID 监听 DATA Key 和 EXPIRE ZSET。
2. 重新读取 score，只有 score 仍不大于当前时间时才执行删除。
3. 删除 DATA、STREAM、DEVICE 和 EXPIRE 成员；owner 不匹配时只清理过期索引成员。
4. 每轮限制批量大小，避免清理任务阻塞 Redis。

## 查询行为

- 设备查询使用 `SMEMBERS` + 批量 DATA 读取，发现 DATA 不存在时惰性清理孤儿成员。
- `/ssrc` 对应的 `getAll()` 使用 EXPIRE ZSET 的游标分批读取并批量加载 DATA，不再使用 Call-ID Hash 的 `HVALS`。
- 迁移期 `getAll()` 额外使用旧 Call-ID Hash 的 `HSCAN` 发现尚未迁移的数据，迁移完成后不再访问旧 Hash。

## 验收边界

### 必须满足

- 新 `put()` 不再向旧 Stream Hash 和旧 Call-ID Hash 写入新会话。
- 新会话写入后，DATA、STREAM、DEVICE、EXPIRE 四类索引同时可见。
- 并发替换同一 Stream 时，旧会话删除不能删除新会话。
- 任意重复 `removeByStream()`、`removeByCallId()` 和过期清理均幂等。
- 设备查询实现中不存在 `opsForHash().values()`。
- 过期会话在正常清理周期内从所有 V2 索引移除。
- 旧 Hash 记录能够按需迁移和批量迁移，迁移成功后旧字段被删除。

### 不在本次范围

- 不修改 SIP INVITE、200 OK、ACK、BYE 报文和时序。
- 不修改 RTP 端口、媒体 Hook、RTP 资源状态机。
- 不保证新旧版本 WVP 节点同时写入时的双向可见性。
- 不自动删除无法反序列化的旧 Hash 脏数据。

## 测试计划

- 单元测试：Key 构造、V2 写入/查询、设备索引过滤、owner 校验、重复删除和过期判定。
- Redis 集成测试：验证事务提交、Stream 替换、TTL/ZSET 清理、旧 Hash 回填和旧字段删除。
- 回归测试：现有 SIP Invite、BYE、播放/回放/下载和设备离线清理测试全部通过。
- 性能验收：构造至少 10 万会话，确认设备查询不产生 `HVALS`，Redis 命令耗时与目标设备会话数 K 相关，而不是与总会话数 N 相关。
