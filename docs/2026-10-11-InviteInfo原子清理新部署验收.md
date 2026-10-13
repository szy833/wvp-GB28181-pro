# InviteInfo 原子清理新部署验收

## 计划与风险边界

用户通知新服务已重启后，先只读核验版本和资源基线，再使用未使用的模拟设备编号复验。新服务 API 构建时间为 `2026-10-11T17:09:45+08:00`，WVP PID 为 13094，节点 `zlmediakit-local` 在线；初始 InviteInfo 为空，索引标记仍为 backfilled。测试前磁盘剩余约 12.94 GiB。

1. 10 路注册、点播和观看，首末两路解码 40 秒，集中设备 BYE 后继续观察至少 70 秒。
2. 第一阶段完整通过后，用另一组新编号进行 10 路并发 API 停止、重复停止，保持设备在线观察至少两个清理周期；再对同一批设备重新点播，核实新 resourceId，首末两路再次解码 40 秒，第二次并发停止后检查归零。
3. 检查主记录、派生索引、版本、到期集合、SIP 会话、业务流、RTP listener、持久化清理待办及通道播放状态。关闭栅栏预期为 closed 且带约 24 小时 TTL，不按残留故障判定。

诊断脚本补充清理待办阶段和 Lua 调用采样。仅保存脱敏命令元数据及本轮 owner，不保存认证信息、Redis 原始值或原始网络抓包。Redis 仅执行读命令，应用正常测试流程产生的写入除外；不进行故障注入、不改索引标记、不删除历史设备、不操作真实摄像头、不重启服务、不提交 Git。

单轮只增加 10 路；磁盘低于 4 GiB、内存可用低于 1 GiB、持续高 CPU、播放/发送/观看错误或清理未收敛时停止加压。测试采集器与媒体观看连接均须收尾。测试数据对应设备自然离线，设备/通道数据库记录保留供追溯。

## 执行结果

### 第一阶段：10 路设备集中 BYE

执行：

```bash
LC_ALL=C python3 tools/gb28181-load-test/reports/media-stair-20261011/invite_retest.py \
  invite-retest-atomic-bye --index 15
```

编号 `37020000001110715001` 至 `37020000001110715010`，对应通道编号加 `200000000`。本轮编号已使用，不可按“新编号”重复执行该命令。

- 10/10 注册、目录入库和点播；10 条业务流、SIP 会话、RTP listener 和观看连接实际被观察到。
- 首末两路各完成 40 秒、1,006 帧 FFmpeg 解码，日志无解码错误；RTP 发送错误为零。
- 设备 BYE 10/10 收到 200。捕获到 10 次原子删除 Lua，返回均为 1；未出现删除重试耗尽或清理失败告警，Redis 采集 blockedFlows=0，三类采集器退出码均为 0。
- 工具媒体清理耗时约 1.015 秒；退出后继续观察至少 70 秒，InviteInfo、五类索引成员、版本、到期集合、Invite/SIP 删除待办均为零，没有退出后的旧 HSET。
- 观察到本轮 10 个 owner 栅栏均为 closed，带约 24 小时 TTL，符合设计；未将关闭栅栏误判为活动资源残留。
- 主机 CPU busy 峰值 26%，WVP RSS 峰值约 839 MiB，无 Full GC。未达到资源保护线，不代表容量极限验收。

仍有重复收尾的“未找到 InviteInfo / 过期 owner”警告，与设备 BYE、媒体离开 Hook 交错一致；此次未伴随资源或业务状态残留。平台同时发出的 5 条反向 BYE 有 4 条收到 200，属于设备集中退出时对端 transport 关闭的已知测试边界，不能据此判定主动停止接口正常，下一阶段单独验证。

证据目录：`tools/gb28181-load-test/reports/media-stair-20261011/`，前缀 `invite-retest-atomic-bye`，主要报告为 `.business-acceptance.json`、`.acceptance.json`、`.evidence-summary.json`；原始脱敏证据为 `.business.jsonl`、`.redis-wire.jsonl`、`.wvp-log.jsonl` 及相邻 `retest-20261011/` 的 `.wire.jsonl`。

### 第二阶段：API 停止与同设备重新点播

执行：

```bash
LC_ALL=C python3 tools/gb28181-load-test/reports/media-stair-20261011/invite_retest.py \
  invite-retest-atomic-api-replay --index 16 --api-stop \
  --duration 215 --hold 300 --replay-after 140
```

使用下一组 10 个新编号。约 65 秒时并发 API 停止，85 秒时重复停止；保持在线并观察清理状态。计划 140 秒后重新点播同一批设备，核实新 resourceId，首末两路再次解码 40 秒，再次并发停止。

实际结果：**本阶段未通过，重新点播部分中止，不计完整验收。**

- 首轮点播 10 路正常；首次 10 路并发 API 停止成功；10 次重复停止成功，停止后通道 streamId 均为空。
- 从约 71 秒到 139 秒，连续约 67.77 秒实际观察到全部设备在线，而 InviteInfo、索引、版本、Invite/SIP 清理待办均为零，说明首轮清理不依赖设备离线。
- 17:20:27 对同一批设备同时发起 10 路重新点播，10 个新 resourceId 和对应媒体流/SIP 会话出现，但只记录到 4 个业务点播成功回调。
- 测试 HTTP 客户端固定 6 秒超时，重新点播阶段发生 TimeoutError，提前停止该阶段。6 秒超时本身不能单独证明服务错误；本次另有真实 Redis 事务中止、状态缺失及残留数据库状态证据，不能仅通过增大测试超时掩盖。
- 未完成第二轮 40 秒解码、第二轮主动停止和预定的退出后连续 70 秒采样；采集器提前终止，第二阶段没有完整的 `.probe.json` / `.business-acceptance.json`，不能复用第一阶段的通过结论。

### 新发现：InviteInfo 写入仍会静默丢失

本轮删除采用 Lua，已避免之前的全局 Hash WATCH 删除冲突；但是创建/更新尚走兼容模式的 WATCH 全局 Hash。

17:20:27.907 至 17:20:28.081 的重新点播事务：

| 类别 | 捕获结果 |
|---|---|
| 创建 ready 的 10 次事务 | 4 次 ARRAY_7 提交，6 次 NULL_ABORT |
| 全部创建及后续状态更新 | 共 11 次 NULL_ABORT，另有 3 次 ARRAY_13 提交 |
| 后续 SIP 更新日志 | 6 台报“未从缓存中读取到 Invite 信息” |
| 业务点播成功回调 | 4 路；不能将 10 条媒体流误报为 10 个 API 点播成功 |

涉及代码：`InviteStreamServiceImpl.java:159` 及同类创建/更新事务使用 `operations.exec() == null` 判断冲突。当前依赖栈将 Redis 的空 EXEC 转为 **空 List 而非 null**，导致未提交却返回成功，不进入预定重试。`PlayServiceImpl.play()` 又在无法确认 ready 持久化的情况下继续发送 INVITE；后续 owner 检查查不到主记录，忽略媒体成功回调。媒体实际到达并不代表业务状态成功。

隔离验证 `RedisExecResultProbe.java` 使用仓库内依赖及实际 GenericFastJsonRedisSerializer，两个独立 LettuceConnectionFactory：第一连接 WATCH Hash，第二连接修改无关 field，第一连接执行 MULTI/HSET/EXEC。输出：

```text
EXEC_IS_NULL=false
EXEC_LIST_SIZE=0
TARGET_UNCHANGED=true
CURRENT_CODE_WOULD_REPORT_SUCCESS=true
```

该验证只连接单独启动的 loopback Redis `127.0.0.1:26390`；运行环境 Redis 没有故障注入或直接写入。隔离 Redis 容器已停止并自动移除，内存测试数据随之消失。源码、脱敏线上事务和隔离验证共同支持此结论，不只依据日志猜测。

失败聚焦报告：`invite-retest-atomic-api-replay.failure-evidence.json`；事务证据：同前缀 `.redis-wire.jsonl`；日志：同前缀 `.wvp-log.jsonl`；隔离输出：`target/invite-atomic-check/exec-abort-probe.log`。

### 最终收尾与残留

只读最终检查见 `invite-atomic-final-verification.json`。WVP PID 13094、ZLM PID 13291 不变，构建版本不变，索引仍为 backfilled，磁盘剩余约 12.91 GiB。

- 两轮模拟设备均已自然离线；全局媒体条目、RTP listener、SIP 会话、InviteInfo 主记录、Invite/SIP 清理待办均为零。
- 本轮已观察 owner 对应的清理 data 和 lease key 均无残留；第一阶段 10 个 owner 栅栏正常 closed 带 TTL。第二阶段观察到 20 个 resourceId，其中 14 个存在 closed 栅栏；未创建成功的 InviteInfo 不会自动获得删除待办或栅栏，不能用待办为空证明没有业务漏洞。
- **第二阶段通道 309、311 的 streamId 仍分别为 `37020000001110716001_37020000001310716001`、`37020000001110716003_37020000001310716003`，虽状态为 OFF，播放标记没有清空。**对应会话曾停留 ready，媒体回调更新没有成功提交。最终报告 `passed=false`、`finalEnvironmentClean=false`；不能仅凭媒体归零宣称整体通过。
- 自有工具、观看、解码、抓包、vmstat/pidstat/jstat 进程均已退出，19102 监控端口已关闭。未手工改数据库播放标记、未删除历史记录，保留残留状态供后续修复核验。

## 后续修复建议（本轮未实施）

1. 统一核对所有事务提交结果，不能只判断 null；为空结果必须判未提交，并验证期望命令数量，避免静默成功。
2. 将 ready 创建、同 owner 更新、SSRC/流名修改等目标 field 写入纳入与删除一致的原始字节 CAS/原子索引维护协议，消除兼容模式共享 Hash 导致的跨设备写入冲突，不直接改运行索引标记绕过问题。
3. ready 写入必须返回明确结果；未确认持久化不得继续发 INVITE，失败按原 owner 回收 RTP/SSRC 并完成 API 失败回调。媒体更新失败不得标记业务成功。
4. 清理状态必须涵盖“媒体到达但 InviteInfo 仍 ready / 缺失”的失败窗口；按 owner 安全恢复通道播放状态，不能依赖设备离线或把 ready 超时当作单纯元数据删除。
5. 修复后先重新验证 10 路同时点播与重复停止，再考虑 25/50 路。真实 Redis 故障注入、阶段重启恢复和新旧 owner 竞争仍需独立安排，本轮未执行。

本轮只修改诊断脚本并记录测试结果，没有继续修改 WVP 生产代码、部署服务或提交 Git。
