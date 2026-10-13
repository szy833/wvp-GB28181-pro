# InviteInfo 删除重试与幂等停止修复

> 本文保留第一次修复及其复验记录。后续已按 [原子删除与清理补偿方案](2026-10-11-InviteInfo原子删除与清理补偿实施方案.md) 修改源码：删除不再 WATCH 全局 Hash；停止清理失败保留独立持久化待办，不再恢复活动 InviteInfo。当前实现及新验证结果以新文档为准，不能将本文中的 WATCH/恢复逻辑当作现行设计。

## 背景及边界

2026-10-11 新部署包复验已确认：SIP 会话、ZLM 媒体流及 RTP listener 清理后，部分 InviteInfo 仍被定时任务反复删除、恢复。

证据和阶段结果见 [新部署包 SIP 会话清理验收](2026-10-11-新部署包SIP会话清理验收.md)。本轮针对该链路修复源码，不重启或重新部署服务，不修改运行环境的 Invite 索引激活标记，不执行真实设备压测或 Redis 故障注入。

## 根因

1. 兼容模式中，InviteInfo 条件删除同时 WATCH 目标版本键和全局主 Hash。并发写入其他 Invite 时可能导致 EXEC 中止；删除原先只有一次尝试，调用包装器又没有处理 false。
2. `stopIfOwner()` 删除 InviteInfo 后清理资源。旧 `safeSendBye()` 把 `SsrcTransactionNotFoundException` 与真实发送异常一起判为失败，最终恢复已结束的业务记录，形成约每 30 秒一次的 HDEL/HSET 循环。
3. SIP 清理先检查当前会话是否属于该 Invite，再调用 `removeByStream()` 重新解析 owner，且忽略删除方法返回值。检查与删除之间发生会话替换时，存在误触新会话的窗口。

## 修复设计

### 条件删除

- 最多尝试 3 次。EXEC 返回 null 或空结果、Redis 运行时异常时有限重试，每次重新 WATCH、读取主记录并核对 owner。
- 记录不存在或 owner 已变化时立即返回 false，不授权 `stopIfOwner()` 清理资源，不把其他请求完成的删除当成本请求成功认领。
- 主记录、派生 Set、到期 ZSET 和版本键仍在同一次事务中删除；事务结果中的运行时异常不计成功。
- 每次尝试在 finally 中使用 SessionCallback 绑定连接清理状态：尚处 MULTI 时 DISCARD，之后 UNWATCH，避免异常污染下一次尝试。
- 重试耗尽输出带 field、resourceId、尝试次数的告警并返回 false。上层 void 包装器也能通过告警暴露失败；不以异常打断其后续资源清理。
- 保留兼容模式全局 Hash WATCH。不能为了减少冲突绕过旧写入方保护；本轮不新增后台删除队列，不自动启用索引。持续冲突或 Redis 不可用时，不承诺即时删除，已有到期/孤儿/离线清理路径仍承担后续重试。

### 幂等停止和会话身份

- SIP 会话已不存在时无需再发 BYE；发送期间目标会话被其他清理流程删除而抛出 `SsrcTransactionNotFoundException`，也按该步骤已完成处理，不因此恢复旧 InviteInfo。
- 发送前核对会话的设备、通道、类型、媒体节点及 SSRC；新会话不匹配时不发送旧 BYE、不删除该新会话。
- BYE 使用已核对的 Call-ID，而非再次按 stream 选取任意当前 owner。删除 SIP 会话同样按已核对的 Call-ID 执行，并传播删除失败返回值。
- 缺少 Call-ID 的现存会话视为未确认清理完成，禁止退回按 stream 操作。
- 真实 BYE 发送异常、Redis 查询/删除失败、数据库状态更新异常或 RTP 清理抛出异常，仍判失败并尝试 `restoreInviteInfoIfAbsent()`，不能覆盖已存在的替换 owner。
- `closeRtpResource()` 的 false 表示未认领资源，不等同于 HTTP 关闭失败；已关闭/过期 owner 的安全关闭语义保持不变。本轮未改造 RTP 内部接口的错误传播。
- 重复 `stopIfOwner()` 若已经没有 InviteInfo，仍返回 false 表示“未认领”，不重复清理或恢复。这是有条件停止的保护契约，不应把它等同于通用停止 API 的失败。

## 本地验证

新增 `InviteCleanupRegressionVerifier` 调用实际 InviteStreamServiceImpl 和 PlayServiceImpl，通过 Redis 操作动态代理模拟 WATCH/MULTI/EXEC、索引操作和确定性的故障注入；不会连接运行环境 Redis。`InviteCleanupRegressionTest` 将相同用例接入常规 JUnit 测试发现。

最终 22 个用例在修复前类文件上仅 8/22 通过，修复后 22/22 通过，覆盖：兼容模式 Hash 冲突、已激活索引模式、空 EXEC、重试耗尽、重试间替换 owner、MULTI 异常清理、读取异常重试、EXEC 部分命令错误、重复删除、SIP 缺失、发送期间会话缺失、Call-ID 绑定、新 SIP owner 保护、真实发送/Redis/数据库/RTP 故障及失败恢复保护。

本地直接使用仓库内已提取的生产依赖，输出独立到 `target/invite-cleanup-check`，不覆盖正在部署的类或 JAR：

```bash
mkdir -p target/invite-cleanup-check/full-classes target/invite-cleanup-check/test-classes
mapfile -t invite_sources < <(rg --files src/main/java -g '*.java')
javac -encoding UTF-8 -J-Xmx2g \
  -cp 'target/session-cleanup-check/lib/*' \
  -processorpath target/session-cleanup-check/lib/lombok-1.18.38.jar \
  -d target/invite-cleanup-check/full-classes "${invite_sources[@]}"
javac -proc:none -encoding UTF-8 \
  -cp 'target/invite-cleanup-check/full-classes:target/session-cleanup-check/lib/*' \
  -d target/invite-cleanup-check/test-classes \
  src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteCleanupRegressionVerifier.java \
  src/test/java/com/genersoft/iot/vmp/gb28181/session/SipInviteSessionConcurrencyVerifier.java
java -cp 'target/invite-cleanup-check/test-classes:target/invite-cleanup-check/full-classes:target/session-cleanup-check/lib/*' \
  com.genersoft.iot.vmp.gb28181.service.impl.InviteCleanupRegressionVerifier
java -cp 'target/invite-cleanup-check/test-classes:target/invite-cleanup-check/full-classes:target/session-cleanup-check/lib/*' \
  com.genersoft.iot.vmp.gb28181.session.SipInviteSessionConcurrencyVerifier extended
```

生产源码全量编译通过；前一轮 SIP 会话并发清理回归 23/23 通过。对应日志：`target/invite-cleanup-check/compile.log`、`before.log`、`after.log`、`sip-regression.log`。

本机未找到 JUnit/Mockito 测试依赖，访问 Maven 仓库域名解析超时，尚未执行 Maven/JUnit 全套。独立验证器不替代真实 Redis 连接绑定、Lettuce 返回值及部署后行为的验收。在依赖可用的构建环境需运行：

```bash
mvn -Dtest=InviteCleanupRegressionTest,InviteStreamServiceImplTest,PlayServiceImplStopTest,InviteInfoCleanupTaskTest test
```

## 待集中执行的真实环境验收及风险

以下项目本轮未执行；需部署含本轮修改的包后统一安排。必须使用新的模拟设备编号，限定采集范围，避免影响真实摄像头。Redis 故障或 owner 竞争注入仅允许在隔离测试环境进行。

| 项目 | 步骤 | 验收条件 | 风险/限制 |
|---|---|---|---|
| 10 路集中退出，连续 3 轮 | 注册、目录同步、点播、持续观看、设备 BYE、观察至少两个 30 秒清理周期 | 本轮 SIP/媒体/listener 与 Invite 主记录、派生索引均归零；不存在旧记录 HDEL/HSET 循环 | 保留历史设备；不删除非本轮记录 |
| 10 路并发停止 API | 保持设备在线，调用停止 API，至少观察 60 秒，再次点播 | 无旧 Invite 恢复；新点播正常；BYE/200、媒体与业务状态一致 | 不把 API 成功当作清理完成的唯一证据 |
| 同流重复停止与新 owner | 同一模拟通道停止后快速重新点播，并使旧清理延迟执行 | 新 Call-ID 和 RTP owner 不被旧清理关闭 | 需隔离测试；本轮并未将 Redis、SIP 和数据库操作变成跨系统原子事务 |
| 删除事务冲突 | 隔离 Redis 中并发修改其他 Invite，触发目标 EXEC 中止 | 有限重试后完成，或耗尽告警且不误认领/误删新 owner | 兼容模式共享 WATCH 仍可能在持续高并发时冲突 |
| 真实异常恢复 | 分别注入 Redis/数据库/RTP 异常，恢复服务后再次对账 | 真实失败不被视作幂等成功，补偿后收敛且不覆盖新 owner | Redis EXEC 命令错误不具回滚保证；异常数据类型需另行排查 |

本轮仅完成源码与本地回归，不代表业务状态已在运行环境完全验收，也不代表已达到大规模压测的容量验收条件。

## 后续新部署复验进展

用户重启后的实际复验见 [InviteInfo 清理新部署复验](2026-10-11-InviteInfo清理新部署复验.md)。两轮正式 10 路点播与媒体确认均成功，退出后 Invite 主记录及索引持续为零，没有重现旧记录恢复；运行捕获也确认条件删除发生冲突后重新 WATCH 和重试。

但第二轮在兼容模式共享 Hash 争用下出现一次三次重试耗尽。另一连接随后完成同记录删除，本轮最终无残留；这不等于失败删除路径已具备可靠补偿。后续阶段已暂停，尚需评估并实现保留 owner 身份的删除失败补偿，不能将这次部署标记为完整验收通过。本次复验未继续修改 WVP 生产源码或运行索引状态。
