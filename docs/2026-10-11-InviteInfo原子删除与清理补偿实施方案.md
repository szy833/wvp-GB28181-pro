# InviteInfo 原子删除与清理补偿实施方案

## 目标与边界

消除兼容模式 WATCH 全局 Invite Hash 导致的跨设备删除冲突，并让已终止会话的失败清理可重试、跨 WVP 重启恢复。本轮仅修改源码和本地隔离测试，不部署、不激活运行索引、不连接运行 Redis 做写入或故障注入，不操作真实摄像头。

保留主 Hash、已有索引结构及旧 boolean 条件删除接口；增加明确的删除结果。删除比较 Redis 原始字节，不在 Lua 中解析 Fastjson 数据。新增持久化清理记录、按 serverId 分区的到期队列、租约和 owner 关闭栅栏。Lua 写入前检查 key 类型；当前多 key 结构仅适用于单机 Redis/Sentinel，不声称支持 Redis Cluster 跨槽事务。

## 实施步骤

1. 先补隔离 Redis 回归验证器，覆盖共享 Hash 并发修改、owner 替换、重复删除、序列化长整型、错误 key 类型、故障后重试及服务对象重建恢复。
2. 实现按目标 field 原始值 CAS 的 Lua 删除，原子维护派生索引、版本和清理进度；将删除失败纳入持久化补偿。
3. 将停止和设备 BYE 接入统一清理记录，区分删除认领与幂等完成；资源清理失败只保留补偿记录，不恢复活动 InviteInfo。逐阶段记录进度，租约避免正常情况下的重复消费。
4. 更新、流名迁移、SSRC 修改和恢复增加 owner/关闭栅栏保护；不改变运行索引激活状态。
5. 运行全量生产源码编译、隔离 Redis 回归及 SIP 清理回归，更新文档和真实环境待验收清单。

## 清理规则

- 删除结果区分：本次删除、已经不存在、owner 变化、可重试故障。旧 boolean 仅在本次删除时返回 true，不能将“已不存在”用于认领资源清理。
- 清理意图在删除主记录之前保存；待办无 TTL，使用退避及抖动重试。Redis 不可用时登记可能失败，必须输出 ERROR，不承诺故障期间落盘。
- 待办携带 owner 快照、模式、原 Call-ID 和各步骤完成标记。登记只是清理意图，不授权副作用；实际删除再次检查当前 owner 并比较原始字节。删除主记录与推进阶段在同一次 Lua 中完成，处理“提交成功但响应丢失”的窗口。
- 完成后的 owner 栅栏保留 24 小时，阻止正常异步回调重新建立已终止的同代会话；新 resourceId 不受该栅栏影响。栅栏是有界回调保护，不替代永不过期的全局代次协议。
- 每个资源阶段前复核目标主记录与租约；新 owner 出现时结束旧任务，不操作新会话。SIP 使用核对后的 Call-ID，RTP 使用 resourceId。Redis、数据库、SIP 和 ZLM 不是跨系统原子事务，最终依赖幂等和对账。
- 普通删除任务仅维护 InviteInfo 存储；完整停止任务还处理 BYE、SIP、RTP 和通道播放状态。设备 BYE 的任务不反向发送 BYE。
- BYE/SIP 失败不阻塞独立的 RTP/播放状态清理。BYE 未完成时保留原 SIP 句柄；使用 `SipTransactionInfo` 重载发送 BYE，该重载不会像 Call-ID 重载那样先删除 Redis SIP 会话再发送，避免真实发送失败后失去重试数据。每个步骤完成后单独确认，重启后跳过已确认步骤。

## 集中安排的真实环境验收

| 项目 | 步骤 | 验收与风险 |
|---|---|---|
| 集中退出 | 新模拟设备 10/25/50 路，分阶段注册、点播、观看、BYE | 主记录、索引、待办、SIP、listener 和通道状态收敛；不触碰历史设备 |
| API 停止 | 设备保持在线，重复停止后重新点播 | 不恢复旧 InviteInfo，新 owner 不被旧任务影响 |
| 故障恢复 | 仅隔离环境断 Redis/注入命令错误，恢复并重启 WVP | 已登记任务恢复执行；登记失败必须显式告警，不能隐瞒为成功 |
| 阶段重启 | 分别在删除提交后、BYE/SIP/RTP/状态更新后重启 | 阶段恢复且重复副作用可幂等；不承诺跨系统 exactly-once |
| 拓扑及版本 | 核实 Redis 单机/Sentinel、Lua ACL、持久化和版本 | 多 key Lua 不适用于当前未使用 hash tag 的 Redis Cluster |

## 实施进度

- 已完成原子存储、调用接入、owner/关闭栅栏和有界补偿执行器。
- `InviteInfoCleanupStore` 保存待办；队列按 serverId 隔离，jobId 为主键和 owner 的 SHA-256。待办不设 TTL；租约 30 秒，完成步骤时续租；失败退避 2/4/8/16/32/60 秒，加 0~500ms 抖动。
- `InviteInfoPendingCleanupTask` 每秒提交一次，独立单线程执行，AtomicBoolean 保证最多一个待执行/运行批次；每批最多 100 条，操作之间检查 500ms 预算。不是 Redis 单次命令或媒体调用的硬超时。
- 删除不依赖 `ready` 标记，也不更改标记。其他更新路径仍保留原兼容模式 WATCH，同时监视/检查 owner 关闭栅栏；非 ready 状态更新、SSRC 修改和流名迁移拒绝不同 owner，迁移不覆盖不同 owner 的目标。
- 补充 `SSRCInfo` 无参构造。隔离 Redis 首轮使用实际 GenericFastJsonRedisSerializer 读取快照失败，补充后通过；不是用 JSON 重编码替代原始字节 CAS。

## 本地验证记录

2026-10-11 使用仓库内 `target/session-cleanup-check/lib` 的生产依赖编译，独立输出到 `target/invite-atomic-check`，不覆盖部署包或原编译产物。

| 验证 | 结果 | 边界 |
|---|---|---|
| 788 个生产 Java 文件编译 | 通过 | JDK 21、Lombok，保留参数元数据 |
| 清理编排故障注入 | 21/21 通过 | 实际 PlayServiceImpl；资源接口为确定性代理，不连接真实 SIP/ZLM |
| SIP 清理旧回归 | 23/23 通过 | 实际 SipInviteSessionManager；包括原共享过期集合冲突及补偿 |
| 隔离 Redis 原子回归 | 通过 | Redis 7.4.0，实际 Lettuce、序列化器与 Lua |
| 差异空白检查 | 通过 | `git diff --check` |
| Maven/JUnit 定向测试 | 未执行成功 | 仓库内隔离 Maven 缓存缺少 Spring Boot 3.4.4 父 POM，DNS 不可用；不将独立验证器冒充 Maven 全套结果 |

隔离 Redis 用缓存镜像启动，只发布 `127.0.0.1:26389`，只读容器根文件系统，项目目录内的 `target/invite-atomic-check` 为唯一数据挂载，关闭 RDB/AOF。未连接运行环境 Redis 做写入，未使用真实摄像头。验证覆盖：

1. 原始字节删除、长整型序列化、主记录/派生索引维护、重复删除不认领。
2. 旧 owner 删除/更新/SSRC/流名变更保护，以及已关闭 owner 的 ready/恢复写入被拒绝。
3. 索引 key 类型错误时主记录不被部分删除，待办保留；修复隔离错误后重建存储对象可完成删除。
4. 租约互斥、过期/释放后的旧 token 不能确认进度或删除待办，Call-ID 与步骤标记跨对象重建恢复。
5. 删除后新 owner 接管，旧任务停止且不删除新 owner 的共享索引成员。
6. 实际 PlayServiceImpl 注入 RTP 异常，InviteInfo 不恢复；重建存储和业务对象后，只重试失败的 RTP 步骤，不重复成功的 BYE/播放状态更新。
7. 12 个工作线程并发删除 100 个不同 field，同时另一线程持续 HSET 同一主 Hash 的无关 field；所有目标删除成功，主记录与待办最终为零。

日志：`compile.log`、`journal.log`、`atomic-final.log`、`sip-regression.log`、`maven-test.log` 均位于 `target/invite-atomic-check`。错误类型和资源失败用例会主动打印 WARN/ERROR，这些是隔离故障注入预期输出。

复现独立编译与回归（隔离 Redis 启动后）：

```bash
mkdir -p target/invite-atomic-check/full-classes target/invite-atomic-check/test-classes
mapfile -t invite_sources < <(rg --files src/main/java -g '*.java')
javac -parameters -encoding UTF-8 -J-Xmx2g \
  -cp 'target/session-cleanup-check/lib/*' \
  -processorpath target/session-cleanup-check/lib/lombok-1.18.38.jar \
  -d target/invite-atomic-check/full-classes "${invite_sources[@]}"
javac -proc:none -encoding UTF-8 \
  -cp 'target/invite-atomic-check/full-classes:target/session-cleanup-check/lib/*' \
  -d target/invite-atomic-check/test-classes \
  src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteAtomicCleanupVerifier.java \
  src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteJournalRegressionVerifier.java \
  src/test/java/com/genersoft/iot/vmp/gb28181/service/impl/InviteCleanupRegressionVerifier.java \
  src/test/java/com/genersoft/iot/vmp/gb28181/session/SipInviteSessionConcurrencyVerifier.java
java -cp 'target/invite-atomic-check/test-classes:target/invite-atomic-check/full-classes:target/session-cleanup-check/lib/*' \
  com.genersoft.iot.vmp.gb28181.service.impl.InviteCleanupRegressionVerifier
java -cp 'target/invite-atomic-check/test-classes:target/invite-atomic-check/full-classes:target/session-cleanup-check/lib/*' \
  com.genersoft.iot.vmp.gb28181.service.impl.InviteAtomicCleanupVerifier 26389
java -cp 'target/invite-atomic-check/test-classes:target/invite-atomic-check/full-classes:target/session-cleanup-check/lib/*' \
  com.genersoft.iot.vmp.gb28181.session.SipInviteSessionConcurrencyVerifier extended
```

依赖齐全的构建环境还需执行：

```bash
mvn -DskipTests=false \
  -Dtest=InviteAtomicCleanupRedisTest,InviteCleanupRegressionTest,InviteStreamServiceImplTest,PlayServiceImplStopTest,ByeRequestProcessorTest,InviteStreamServiceRedisIntegrationTest \
  test
```

## 保证范围和残余风险

- 消除的是删除路径因其他 field 写入造成的 WATCH 中止，不宣称所有更新路径的事务冲突、所有 Redis 故障均已消失。
- 已登记的待办可跨 WVP 进程重启恢复；尚未登记时 Redis 完全不可用仍可能失败，会输出 ERROR。Redis 自身数据丢失仍取决于 AOF/RDB、复制及故障恢复策略。
- Lua 的类型检查避免已知 WRONGTYPE 半写入，不保证 Redis OOM/服务崩溃下脚本像关系数据库事务一样回滚。
- 24 小时关闭栅栏是有界延迟回调保护，过期后不保证任意久远快照无法重放。待办未完成时栅栏不设 TTL；需监控长时间失败任务及栅栏容量。
- 资源动作执行后、步骤确认前崩溃可能重复动作；超过 30 秒租约的阻塞调用也可能与新 worker 重叠。依赖原 Call-ID/resourceId 和幂等处理，不声称跨系统 exactly-once。
- 每个步骤前复核 Redis owner，不能把 Redis、数据库、SIP、ZLM 变成一个跨系统事务；同流立即重播时的数据库状态窗口仍须按上表实测和对账。
- RTP 的 false 仍保留现有“未认领/已关闭”语义，不一律判失败。本轮没有重写底层 ZLM HTTP 错误传播，真实 listener 是否消失必须用 ZLM 对账验收。
- 本轮不部署、不重启 WVP/ZLM、不激活索引、不进行真实环境压测、不提交 Git；历史上轮文档保留为阶段记录，以本文说明当前实现。
