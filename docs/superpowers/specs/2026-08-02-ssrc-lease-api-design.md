# SSRC 租约接口收口设计

## 背景

SSRC 分配已经通过 `SsrcLease` 管理生命周期，但 `SSRCFactory` 仍保留返回纯字符串的兼容接口。字符串不携带租约身份，调用方无法可靠释放，可能重新产生虚占。

## 目标

- 让生产代码和测试只通过 `SsrcLease` 获取自动分配的 SSRC。
- 删除无法表达释放责任的字符串分配接口，避免新增不可追踪占用。
- 保持现有媒体节点对账、节点级并发控制和释放语义不变。
- 验证释放可复用、重复释放幂等以及随机分配不冲突。

## 方案

删除 `SSRCFactory` 的四个字符串接口：

- `getPlaySsrc(String)`
- `getPlayBackSsrc(String)`
- `getPlaySsrc(MediaServer)`
- `getPlayBackSsrc(MediaServer)`

保留并作为唯一自动分配入口：

- `allocatePlayLease(...)`
- `allocatePlaybackLease(...)`
- `release(SsrcLease)`

调用方必须保存返回的 `SsrcLease`，并在资源创建失败、超时、关闭或业务停止时调用 `release`。现有 RTP 和对讲生命周期清理逻辑继续负责释放租约。

## 兼容性

当前生产代码未发现四个旧字符串接口的调用方；测试迁移到 Lease API。删除方法会让外部扩展在编译期暴露迁移点，避免静默引入无法释放的分配。

## 测试

- Lease 释放后可再次分配同一节点的空闲槽位。
- 重复释放同一 Lease 不影响后续分配。
- 随机分配在并发场景下不产生重复 SSRC。
- Lease API 在对账成功后正常分配，失败状态下继续暂停分配。

