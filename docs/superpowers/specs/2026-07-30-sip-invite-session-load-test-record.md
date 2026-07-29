# SIP Invite Session V2 线上压测记录

**功能版本：** `3e74f66` 设计 + 当前实现提交
**记录日期：** 2026-07-30
**状态：** 自动化回归已完成，正式线上压测待安排

## 已完成的验证

- `SipInviteSessionManagerTest`：V2 key、事务写入、TTL、设备 Set 查询、旧 Hash `values()` 禁用、owner 安全删除。
- 点播/停止/BYE/播放控制器回归：通过。
- 全量 Maven 测试：通过；Testcontainers Redis 集成测试 3 项通过。
- 静态边界检查：`SipInviteSessionManager` 不包含 `opsForHash().values()`，新 `put()` 不写旧 Stream/Call-ID Hash。

## 线上压测待执行项

压测环境需要 Redis 与 WVP 实例隔离，避免影响生产业务。准备至少 100,000 个会话记录，其中目标设备 K 分别取 1、100、1,000；并发执行设备查询、按流查询、按 Call-ID 查询和正常删除。

需要记录以下指标：

1. Redis `INFO commandstats` 中 `hvals` 应保持为 0（迁移期旧数据扫描只允许 `HSCAN`）。
2. 设备查询 P50/P95/P99 延迟及 Redis CPU、网络出入流量；延迟应主要随 K 变化，不随全局会话数 N 线性增长。
3. 并发替换同一 Stream 时，旧 owner 延迟删除不得删除新 DATA/STREAM 记录。
4. 模拟进程异常后，TTL 与 30 秒清理任务是否在预期窗口内移除 DATA、STREAM、DEVICE、EXPIRE 四类索引。
5. 迁移 10 万旧 Hash 记录的吞吐、Redis CPU 峰值、迁移失败/脏数据数量，以及迁移后旧字段删除比例。

## 发布门槛

- 未完成上述线上压测前，不宣称已证明生产容量上限；仅可确认功能和回归测试通过。
- 若 P99 超过现网 SLA、Redis CPU 持续超过 70% 或出现索引误删，暂停发布并保留抓包、Redis 命令统计和应用日志供复盘。
