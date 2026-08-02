# 异步执行器改造说明

项目现在通过 `AsyncConfig` 启用 `@Async`，默认使用有界的 `applicationTaskExecutor`：核心线程 8、最大线程 32、队列 2000。队列满时使用调用方执行策略，避免静默丢弃 SIP/媒体事件。

SIP 分发和 ACK 回复使用独立的 `sipTaskExecutor`，不再依赖父类方法的 Spring 自调用代理，避免普通媒体/业务异步任务挤占 SIP 线程池。

## 集成验证风险

- 需要真实设备验证 REGISTER、Keepalive、NOTIFY、Invite/Bye 在高并发下的时序和回复延迟。
- 需要真实 Redis、数据库和 ZLM 验证异步线程池饱和时的调用方执行行为，以及 Hook/媒体事件是否出现重复或乱序。
- 需要观察 `wvp-async-*` 线程数量、队列长度、CallerRuns 次数和异步异常日志，并据此调整 `async.executor.*` 配置。
