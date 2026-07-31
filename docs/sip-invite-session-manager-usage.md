# SipInviteSessionManager 使用流程说明

**文档日期：** 2026-07-30

**适用范围：** GB28181 SIP Invite 会话、RTP 收流、点播/回放/下载、语音对讲、平台级联

**核心实现：** `src/main/java/com/genersoft/iot/vmp/gb28181/session/SipInviteSessionManager.java`

## 1. 组件定位

`SipInviteSessionManager` 管理的是 `SsrcTransaction`，不是 `InviteInfo`。

`InviteInfo` 保存点播、回放、下载的业务状态，例如 `ready`、`ok`、超时和清理时间；`SsrcTransaction` 保存 SIP 会话与媒体流之间的关联信息，包括：

- SIP `Call-ID`、From/To Tag、CSeq 等 `SipTransactionInfo`；
- 设备或上级平台编号、通道编号；
- 媒体服务器、RTP `app`、`stream`、SSRC；
- 会话类型：`PLAY`、`PLAYBACK`、`DOWNLOAD`、`TALK`、`BROADCAST`。

GB28181 的不同处理环节掌握的标识不同：

```text
SIP BYE/INFO/MESSAGE       -> Call-ID
ZLM Hook/RTP 资源          -> app + stream
设备离线                   -> deviceId
/ssrc 和媒体服务器下线     -> 全部会话
```

管理器就是这些标识之间的关联层，使 SIP 信令可以找到 RTP 资源，也使媒体侧可以找到 SIP Transaction 并发送 BYE 或 INFO。

需要注意：SIP 会话被 `put()` 保存，不等于媒体已经收到。当前点播流程中，通常先保存 `InviteInfo=ready`；收到 SIP 200 OK 后保存 `SsrcTransaction`，同时 `InviteOKHandler` 会先持久化 `InviteInfo=ok`，但此时 `streamInfo` 可能仍为空。收到 ZLM 的媒体到达 Hook 后，流程会再次更新 `InviteInfo=ok` 并补充 `streamInfo`，只有这一步才代表媒体已经被 WVP/RTP 资源确认。

## 2. Redis 数据模型

V2 Key 定义在 `VideoManagerConstants.java`，所有 Key 都包含 `serverId`，避免多个 WVP 实例互相覆盖。

| Key | Redis 类型 | 内容 | 主要用途 |
|---|---|---|---|
| `VMP_SIP_INVITE_SESSION_V2:DATA:<serverId>:<callId>` | String | 完整 `SsrcTransaction` | 会话主记录，唯一完整数据源 |
| `VMP_SIP_INVITE_SESSION_V2:STREAM:<serverId>:<app><stream>` | String | `callId` | 按流定位会话 |
| `VMP_SIP_INVITE_SESSION_V2:DEVICE:<serverId>:<deviceId>` | Set | 多个 `callId` | 按设备定位会话 |
| `VMP_SIP_INVITE_SESSION_V2:EXPIRE:<serverId>` | ZSet | `callId -> expireAt` | 到期会话扫描 |
| `VMP_SIP_INVITE_SESSION_V2:META:<serverId>:<callId>` | String | `deviceId/app/stream` | 主记录过期后的反向定位 |

旧版本数据位于：

```text
VMP_SIP_INVITE_SESSION_INFO:CALL_ID:<serverId>
VMP_SIP_INVITE_SESSION_INFO:STREAM:<serverId>
```

迁移期间旧 Hash 只读，不再接收新写入。V2 未命中时可以回退到旧 Hash，并按需迁移；后台任务也会分批迁移旧数据。

## 3. 管理器方法职责

### 3.1 `put(SsrcTransaction)`

位置：`SipInviteSessionManager.java` 的 `put()`。

创建或更新会话，写入 DATA、STREAM、DEVICE、EXPIRE、META 五类数据。写入时会设置创建时间和安全过期时间，默认 TTL 为 7 天，配置项为 `sipInviteSessionTtlSeconds`。

写入采用 `WATCH -> MULTI -> EXEC`，并在事务冲突时最多重试 3 次。若同一个 `app + stream` 已被旧 Call-ID 占用，会在同一事务中清理旧会话，再写入新会话。

这样做可以保证：

1. 主记录和派生索引不会出现长时间的半写入状态。
2. 并发点播同一流时，旧会话不会遗留完整索引。
3. 旧会话的延迟清理不会覆盖新会话。

### 3.2 `getSsrcTransactionByStream(app, stream)`

先读取 STREAM 索引得到 Call-ID，再读取 DATA 主记录：

```text
STREAM GET -> callId
DATA GET   -> SsrcTransaction
```

主要用于：

- 按流发送 BYE；
- 点播、回放、下载超时清理；
- RTP 端口或 TCP 主动连接失败后的清理；
- 语音对讲停止；
- 回放 INFO 控制前获取 `SipTransactionInfo`；
- SSRC 修正或单端口流重新登记；
- 平台级联停止和异常处理。

如果 STREAM 存在但 DATA 已不存在，会使用 owner 校验删除孤儿 STREAM；如果 V2 未命中，则回退旧 STREAM Hash 并尝试迁移。

### 3.3 `getSsrcTransactionByCallId(callId)`

直接按 DATA Key 查询完整会话，适合 SIP 信令入口：

- 设备发来 BYE 时查找设备、通道、流和业务类型；
- MESSAGE 的 From 字段不可靠时，根据 Call-ID 修正设备编号；
- 录像下载完成通知根据 Call-ID 找到下载会话；
- 主动停止时根据 Call-ID 生成 BYE；
- 上级平台级联按 Call-ID 停止会话。

V2 未命中时回退旧 Call-ID Hash，读取成功后按需迁移到 V2。

### 3.4 `getSsrcTransactionByDeviceId(deviceId)`

先从设备 Set 读取 Call-ID，再使用 `MGET` 批量读取 DATA，只返回当前设备的会话。

该方法主要服务设备离线清理：

```text
设备离线
  -> 查询该设备的 Call-ID Set
  -> 批量读取会话
  -> 关闭对应 RTP
  -> removeByCallId()
```

如果 Set 中存在 DATA 已过期的 Call-ID，会惰性删除该 Set 成员。迁移期在 Set 为空或发现异常成员时，才有限度地扫描旧 Call-ID Hash。

平台级联会话通常只有 `platformId`，没有 `deviceId`，因此不会进入 DEVICE Set，避免设备离线时误删平台会话。

### 3.5 `removeByStream(app, stream)`

按流删除会话。删除前会重新检查 STREAM 当前 owner 是否仍然是目标 Call-ID，只有 owner 匹配才删除完整索引。

删除内容包括：

- DATA；
- STREAM；
- DEVICE Set 中的 Call-ID；
- EXPIRE ZSet 中的 Call-ID；
- META。

owner 校验主要防止以下竞态：旧会话超时清理开始后，新会话已经接管相同 stream，旧清理不能删除新会话的 STREAM owner。

### 3.6 `removeByCallId(callId)`

按 Call-ID 删除完整会话，重复调用安全。方法会重新读取 DATA 并校验当前 Call-ID；如果 STREAM 已经被新 Call-ID 接管，则只删除旧会话的 DATA、DEVICE、EXPIRE、META，不删除新会话的 STREAM 索引。

主要用于主动发送 BYE 前清理、设备 BYE 处理完成后的清理和设备离线清理。

### 3.7 `getAll()`

通过 EXPIRE ZSet 的 `ZSCAN` 分批读取 Call-ID，再使用 `MGET` 批量读取 DATA。它不再调用旧 Call-ID Hash 的 `HVALS`。

主要调用方：

- `/ssrc` 接口，展示当前所有 SIP Invite 会话；
- ZLM 节点下线时查找使用该媒体服务器的会话。

这是全局查询，复杂度仍与总会话数相关，但采用游标和批量读取，避免一次性将整个 Hash 的 values 加载到内存。

### 3.8 `cleanupExpiredSessions(limit)`

后台按 EXPIRE ZSet 中的过期时间取出会话，重新校验 score 和 STREAM owner 后删除完整索引。

该方法用于兜底处理：JVM 崩溃、BYE 丢失、设备断电、超时回调未执行等异常路径。

`SipInviteSessionCleanupTask` 每 30 秒执行一次，单轮最多处理 200 条，清理失败时等待下一轮重试。

### 3.9 `migrateLegacyBatch(limit)`

后台使用 `HSCAN` 分批扫描旧 Call-ID Hash，将可解析记录写入 V2。只有在 DATA、STREAM、DEVICE、EXPIRE 等索引均验证成功后，才删除旧 Hash 字段。

如果旧数据无法解析，保留原字段并记录告警；如果 V2 已存在活动会话，V2 作为权威数据，旧数据不会覆盖 V2。

`SipInviteSessionMigrationTask` 启动延迟 10 秒、之后每 60 秒执行一次，单轮最多迁移 500 条。

### 3.10 内部 `touchIfNeeded()`

按流或按 Call-ID 查询成功后，会检查会话是否接近安全 TTL。如果剩余时间不足 TTL 的一半，则在事务中刷新 DATA、STREAM、META 的 TTL 和 EXPIRE score。

因此默认 7 天 TTL 是异常会话的回收上限，不会因为正常长期使用而强制截断活跃会话。

## 4. 具体业务流程

### 4.1 普通点播、回放、下载

```text
PlayServiceImpl
  -> 创建 RTP 接收端口并生成 SSRC/app/stream
  -> 保存 InviteInfo=ready
  -> SIPCommander 发送 INVITE
  -> 收到设备 200 OK
  -> sessionManager.put(SsrcTransaction)
  -> InviteOKHandler 持久化 InviteInfo=ok（SIP 已建立，streamInfo 可能为空）
  -> 设备发送 RTP，ZLM 触发媒体 Hook
  -> 媒体回调补充 streamInfo，并再次持久化 InviteInfo=ok
```

停止时：

```text
停止 API 或媒体侧无人观看
  -> 按 stream 查询 SsrcTransaction
  -> 根据 SipTransactionInfo 发送 BYE
  -> 删除 InviteInfo
  -> 关闭 RTP
  -> removeByStream() 或 removeByCallId()
```

点播、回放、下载的 `put()` 分别位于 `SIPCommander.playStreamCmd()`、`playbackStreamCmd()` 和 `downloadStreamCmd()` 的最终响应回调中。

### 4.2 设备主动发送 BYE

```text
设备发送 BYE
  -> 读取 Call-ID
  -> getSsrcTransactionByCallId()
  -> 根据 PLAY/PLAYBACK/DOWNLOAD/TALK/BROADCAST 分支处理
  -> 停止 InviteInfo、关闭 RTP、恢复播放状态
  -> finally 中 removeByCallId()
```

BYE 业务清理失败时，`finally` 仍会尝试删除 SIP 会话，避免只释放了媒体资源却遗留 Redis 会话。

### 4.3 回放 INFO 控制

回放暂停、恢复、拖动、倍速播放需要使用原始 SIP Dialog 信息：

```text
stream
  -> getSsrcTransactionByStream()
  -> 读取 SipTransactionInfo
  -> 创建 INFO 请求并发送
```

`InfoRequestProcessor` 中虽然注入了 `SipInviteSessionManager`，但该字段本身没有直接调用；实际查询发生在 `SIPCommander.playbackControlCmd()`。

### 4.4 设备离线

`DeviceServiceImpl` 通过 `getSsrcTransactionByDeviceId()` 得到该设备的所有 SIP 会话，关闭每个会话的 RTP 后调用 `removeByCallId()`。

### 4.5 媒体服务器下线

`PlayServiceImpl.zlmServerOffline()` 调用 `getAll()`，筛选 `mediaServerId` 匹配的会话，并向相关设备发送 BYE。后续由正常停止或 BYE 流程完成会话清理。

### 4.6 平台级联

上级平台广播、平台停止、SSRC 修正和平台异常清理使用相同的会话模型：

- 平台广播成功后调用 `put()`；
- 按 Call-ID 或 stream 查询会话；
- SSRC 变化时删除旧 stream owner，再修改事务并重新 `put()`；
- 平台停止时删除对应 V2 索引。

`SIPCommanderForPlatform.streamByeCmd(Platform, SendRtpInfo, channel)` 这个重载直接使用 `SendRtpInfo` 生成 BYE，不经过管理器；它依赖后续停止流程或其他清理逻辑释放会话。

## 5. 所有主要调用方汇总

| 调用方 | 方法 | 使用目的 |
|---|---|---|
| `SIPCommander` | `put` | 保存 PLAY、PLAYBACK、DOWNLOAD、TALK 会话 |
| `SIPCommander` | `getSsrcTransactionByCallId` / `getSsrcTransactionByStream` | 主动停止、获取 INFO 的 SIP Transaction |
| `SIPCommander` | `removeByCallId` / `removeByStream` | BYE 前和命令失败时清理 |
| `SIPCommanderForPlatform` | `put` | 保存平台广播会话 |
| `SIPCommanderForPlatform` | 查询、`removeByStream` | 平台停止和异常清理 |
| `InviteRequestProcessor` | `put` | 保存设备发起的语音广播会话 |
| `ByeRequestProcessor` | `getSsrcTransactionByCallId` / `removeByCallId` | 处理设备 BYE |
| `MessageRequestProcessor` | `getSsrcTransactionByCallId` | 根据会话修正设备身份 |
| `MediaStatusNotifyMessageHandler` | `getSsrcTransactionByCallId` | 处理下载结束通知 |
| `PlayServiceImpl` | 按流查询、删除、`getAll` | 点播/回放/下载/对讲的超时、停止、重建和 ZLM 下线 |
| `PlatformServiceImpl` | 按流查询、删除、`put` | 平台级联停止、SSRC 修正和异常清理 |
| `DeviceServiceImpl` | `getSsrcTransactionByDeviceId` / `removeByCallId` | 设备离线释放 RTP 和 SIP 会话 |
| `PlayController` | `getAll` | `/ssrc` 接口展示当前会话 |
| `SipInviteSessionCleanupTask` | `cleanupExpiredSessions` | 定期清理异常残留会话 |
| `SipInviteSessionMigrationTask` | `migrateLegacyBatch` | 定期迁移旧 Hash 数据 |

## 6. 本次优化点

### 6.1 查询复杂度

- 按 Call-ID：单个 DATA Key 查询，近似 O(1)。
- 按 stream：STREAM Key 加 DATA Key，近似 O(1)。
- 按设备：`SMEMBERS + MGET`，复杂度与该设备会话数 K 相关，而不是与全局会话数 N 相关。
- 全量查询：`ZSCAN + MGET` 分批读取，不再执行 `HVALS`。

### 6.2 写入一致性

旧模型的 Stream Hash 和 Call-ID Hash 分开写入，存在短暂不一致。新模型将主记录和索引放入同一个 Redis 事务，减少半写入状态。

### 6.3 并发安全

通过 WATCH、事务重试和 stream owner 校验，避免旧会话的延迟清理删除新会话。

### 6.4 异常回收

单会话 TTL、EXPIRE ZSet、定时清理和 META 反向索引共同处理异常退出，降低 Redis 永久残留风险。

### 6.5 活跃会话续期

活跃会话接近安全 TTL 时自动刷新 TTL 和过期索引，长期正常使用的流不会被 7 天安全 TTL 截断。

### 6.6 兼容迁移

旧数据可以按需迁移，也可以由后台任务分批迁移。迁移成功并完成索引校验后才删除旧字段，降低上线切换风险。

## 7. 运维和边界

- `getAll()` 仍是全局查询，只应用于 `/ssrc`、媒体服务器下线等低频流程，不应放入高频媒体 Hook 热路径。
- DEVICE 是 Redis Set，Set 根 Key 没有按成员设置 TTL；成员依赖正常删除、过期任务和查询时的惰性清理。线上应监控过期清理任务和孤儿成员数量。
- 新旧版本节点同时写入不属于本次兼容范围，部署时应先完成节点升级。
- 尚未收到最终 SIP 响应、没有完整 Transaction 信息的 INVITE，不会作为完整 `SsrcTransaction` 保存。
- 本改造不改变 INVITE、200 OK、ACK、BYE 报文时序，也不改变 RTP 端口、媒体 Hook 和 `InviteInfo` 状态机。
