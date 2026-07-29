# SIP Invite Session 索引与生命周期实施计划

## 目标

在不改变 SIP/RTP 业务行为的前提下，为 `SipInviteSessionManager` 增加 V2 数据源、流/设备索引、单会话 TTL 和过期清理，并兼容迁移旧 Hash 数据。

## 实施步骤

1. **测试先行：** 增加 manager 的单元测试，覆盖 V2 key 构造、写入四类索引、设备查询不使用 `HVALS`、owner 安全删除、重复删除、TTL/过期清理及旧记录迁移。
2. **数据模型：** 在 `VideoManagerConstants` 增加 V2 key 前缀；在 `SsrcTransaction` 增加创建/过期时间字段；在 `UserSetting` 增加可配置安全 TTL（默认 7 天）。
3. **核心读写：** 重写 `SipInviteSessionManager`：V2 DATA 为唯一会话内容，STREAM/DEVICE/EXPIRE/META 为派生索引；写入和删除使用 Redis 事务与 owner 校验，重试处理 WATCH 冲突。
4. **兼容迁移：** V2 未命中时读取旧 Stream/Call-ID Hash 并按需迁移；设备查询和全量查询只用 `HSCAN` 兼容扫描；增加定时批量迁移任务，解析失败的数据只告警。
5. **过期清理：** 增加定时任务按 EXPIRE ZSET 批量取到期 callId，校验 score/owner 后清除 DATA、STREAM、DEVICE、EXPIRE，清理孤儿成员并保持幂等。
6. **验证与评审：** 运行定向测试、现有 SIP/播放回归测试和静态检查；按设计文档逐项检查边界，记录 Docker/Testcontainers 等环境阻塞项，不修改 BYE、SIP 报文和 RTP 状态机。

## 验收边界

- 新写入不触碰旧 Hash；四类 V2 索引在一次事务中可见。
- 并发替换时旧 owner 的延迟删除不能删除新 owner。
- `getSsrcTransactionByDeviceId()`、`getAll()` 不调用 `opsForHash().values()`。
- 正常删除、重复删除、过期清理均幂等且无跨会话误删。
- 旧数据可按需/批量迁移，迁移成功后才删除旧字段；脏数据保留并告警。
- 默认 TTL 为 7 天且可配置，Hash 根键不设置全局 TTL。
- DATA 过期时可通过 META 反向定位并删除 STREAM/DEVICE/EXPIRE 孤儿；活跃会话接近 TTL 时刷新安全窗口。
- 不改变 SIP INVITE/200/ACK/BYE、RTP、媒体 Hook 及现有调用接口语义。
