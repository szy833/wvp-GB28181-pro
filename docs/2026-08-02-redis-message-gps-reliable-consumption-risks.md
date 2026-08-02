# Redis 消息与 GPS 可靠消费风险

## 迁移方式

- 报警和 GPS 的主链路使用 Redis Stream、消费组和 Pending 重试；业务成功后才 ACK。
- Redis Pub/Sub 监听仅作为兼容入口，由 `redis.stream.pub-sub-compatibility` 控制，默认关闭。
- 兼容模式下，外部发布者发送的消息会先由 Pub/Sub 监听器追加到 Stream；发布者在写入 Stream 前进程崩溃时仍存在短暂丢失窗口，迁移完成后应关闭兼容模式。
- GPS 最新位置使用按通道独立 Key 和 60 秒 TTL；旧 Hash 仅用于读取回退，不再刷新其 TTL。
- 开启轨迹保存时，每条 Stream GPS 记录都会写入轨迹表；关闭轨迹时只更新最新位置，不从消息队列直接丢弃记录。

## 运维与集成测试

- 需要真实 Redis 验证 `XGROUP CREATE ... MKSTREAM`、阻塞读取、Pending claim、重启后的重复投递和 ACK 幂等性。
- 需要真实 Redis 验证 Stream 长度、Pending 数量和消费延迟监控；生产环境应配置告警阈值。
- 需要真实数据库验证 GPS 轨迹批量写入、重复重试不会破坏最新位置，以及旧 Hash 回退读取。
- 需要真实设备/ZLM 环境验证报警 SIP 发送失败后的重试不会造成重复副作用，必要时在业务侧使用幂等键。
