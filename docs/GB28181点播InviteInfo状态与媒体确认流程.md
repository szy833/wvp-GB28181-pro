# GB28181 点播 InviteInfo 状态与媒体确认流程

**文档日期：** 2026-07-30

## 1. 结论先行

当前代码中，`InviteInfo.status=ok` 会在两个阶段被写入：

1. SIP 200 OK 到达后，`InviteOKHandler` 先持久化 `ok`。
2. 设备真正发送 RTP、ZLM 触发媒体到达回调后，媒体处理流程再次写入 `ok`，并补充 `streamInfo`。

因此：

```text
status = ok
    表示 SIP 会话已经被设备接受。

status = ok && streamInfo != null
    表示媒体流已经被 WVP/RTP 资源确认。
```

“收到媒体 Hook 后才把 InviteInfo 改为 ok”的说法不完全准确。更准确的表述是：

> SIP 200 OK 后，InviteInfo 可能已经是 `ok`，但 `streamInfo` 仍为空；媒体 Hook 到达后，才完成媒体确认、补充 `streamInfo`，并向点播调用方报告成功。

## 2. 三类对象的职责

### InviteInfo

保存点播业务状态、设备、通道、业务流名、RTP 信息、媒体服务器和 `StreamInfo`。它描述的是业务层的点播状态。

### SipInviteSessionManager

保存 `SsrcTransaction`，建立以下关联：

```text
Call-ID <-> app + stream <-> device/channel/mediaServer
```

它不负责确认 RTP 是否已经到达，而是保证 BYE、INFO、设备离线和异常清理可以找到对应的 SIP Transaction。

### RtpResourceContext

负责确认媒体是否真正到达，状态一般为：

```text
REGISTERING -> WAITING_MEDIA -> SUCCESS
```

`WAITING_MEDIA` 只代表接收端口已创建，`SUCCESS` 才代表收到媒体到达事件。

## 3. 点播流程

### 3.1 创建 RTP 接收资源

`PlayServiceImpl.play()` 首先调用：

```java
receiveRtpServerService.openGbRTPServerForPlay(...)
```

代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:397`

此步骤会分配 SSRC、确定 `app/stream`、创建 RTP 接收端口、注册 `on_media_arrival` Hook、创建 `RtpResourceContext` 并启动收流超时任务。

资源创建成功后，状态通常为：

```text
REGISTERING -> WAITING_MEDIA
```

它只说明 WVP 已准备好收流，不能说明设备已经发 RTP。

### 3.2 保存 `InviteInfo=ready`

RTP 资源准备好后，PlayService 创建并保存 `InviteInfo`：

```java
InviteInfo inviteInfo = InviteInfo.getInviteInfo(
    device.getDeviceId(), channel.getId(), ssrcInfo.getStream(),
    ssrcInfo, mediaServer.getId(), mediaServer.getSdpIp(),
    ssrcInfo.getPort(), device.getStreamMode(),
    InviteSessionType.PLAY, InviteSessionStatus.ready
);
inviteStreamService.updateInviteInfo(inviteInfo);
```

代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:446`

此时：

```text
InviteInfo.status = ready
InviteInfo.streamInfo = null
RtpResourceContext = WAITING_MEDIA
```

`ready` 表示点播正在建立，设备还未通过 SIP 接受请求，媒体也没有确认到达。该状态会使用播放超时时间的两倍作为等待期，防止请求永久残留。

### 3.3 发送 INVITE 并收到 200 OK

PlayService 调用 `cmder.playStreamCmd(...)`，代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:452`。

典型信令时序为：

```text
WVP -> 设备：INVITE
设备 -> WVP：100 Trying
设备 -> WVP：200 OK
WVP -> 设备：ACK
```

设备返回最终成功响应后，SIPCommander 构建 `SsrcTransaction` 并调用：

```java
sessionManager.put(ssrcTransaction);
```

代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/transmit/cmd/impl/SIPCommander.java:287`

管理器保存 Call-ID、设备、通道、app、stream、SSRC、媒体服务器和 `SipTransactionInfo`。这样后续即使媒体还没到达，也可以根据 Call-ID 或 stream 发送 BYE、INFO 或执行清理。

此时的含义是：

```text
SIP 会话：已建立
SsrcTransaction：已保存
RTP 资源：等待媒体
```

### 3.4 SIP 200 OK 后 `InviteInfo` 先变为 `ok`

`sessionManager.put()` 完成后，PlayService 执行 `InviteOKHandler()`：

```java
inviteInfo.setStatus(InviteSessionStatus.ok);
inviteStreamService.updateInviteInfo(inviteInfo);
```

代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:825`

此时可能是：

```text
InviteInfo.status = ok
InviteInfo.streamInfo = null
RtpResourceContext = WAITING_MEDIA
```

这里的 `ok` 表示设备接受了 SIP 点播请求，并且已经存在可用于 BYE/INFO 的 SIP Dialog；它不等于媒体已经可播放。

例如 UDP 网络不通、TCP 连接没有建立、设备返回 200 OK 但没有发送 RTP，都可能出现上述状态。

## 4. 媒体到达并完成播放确认

### 4.1 `on_publish` 不是最终成功信号

`ZLMHttpHookListener.onPublish()` 的 `/on_publish` 主要用于推流鉴权，代码位置：`src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMHttpHookListener.java:103`。

日志中的：

```text
[ZLM HOOK]推流鉴权
[ZLM HOOK]推流鉴权-允许-响应
```

只能说明 ZLM 请求 WVP 判断是否允许发布该流，不能单独说明媒体已经被 WVP 确认。

### 4.2 `on_stream_changed(register)` 触发媒体到达

ZLM 注册 RTP 流后，通过 `/on_stream_changed` 通知 WVP。WVP 发布 `MediaArrivalEvent`，代码位置：`src/main/java/com/genersoft/iot/vmp/media/zlm/ZLMHttpHookListener.java:174`。

`HookSubscribe` 再将该事件分发为 `on_media_arrival`，代码位置：`src/main/java/com/genersoft/iot/vmp/media/event/hook/HookSubscribe.java:33`。

RTP 服务此前已经按照相同的 app、stream 和 mediaServer 注册了 Hook，因此该事件可以匹配当前 `RtpResourceContext`。

### 4.3 RTP 状态进入 `SUCCESS`

RTP 服务收到媒体到达事件后调用：

```java
context.onMediaArrival(contextData);
```

代码位置：`src/main/java/com/genersoft/iot/vmp/service/impl/RtpServerServiceImpl.java:567`

`RtpResourceContext` 将状态从：

```text
WAITING_MEDIA -> SUCCESS
```

并执行成功回调，代码位置：`src/main/java/com/genersoft/iot/vmp/service/bean/RtpResourceContext.java:236`。

如果媒体事件早于 RTP 创建调用返回，资源上下文会暂存 `earlyArrival`，待进入 `WAITING_MEDIA` 后再完成成功转换，避免初始化竞态导致媒体事件丢失。

### 4.4 媒体回调补充 `streamInfo`

RTP 成功回调回到 PlayService 后，执行 `onPublishHandlerForPlay(...)`，代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:401`。

该方法会：

1. 根据 ZLM 媒体信息构造 `StreamInfo`。
2. 调用 `deviceChannelService.startPlay()`。
3. 查找当前设备和通道的 `InviteInfo`。
4. 设置 `status=ok`。
5. 设置 `streamInfo`。
6. 持久化更新 `InviteInfo`。

核心代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:672`。

最终状态为：

```text
InviteInfo.status = ok
InviteInfo.streamInfo != null
RtpResourceContext = SUCCESS
设备通道播放状态 = true
```

随后调用点播成功回调，代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:411`。因此，对外报告“点播成功”是在媒体到达并成功生成 `StreamInfo` 后才发生的。

## 5. 完整时序图

```text
点播 API
  |
  v
PlayServiceImpl
  |
  | 创建 RTP 监听器、注册 on_media_arrival Hook
  v
RtpResourceContext: REGISTERING -> WAITING_MEDIA
  |
  | 保存 InviteInfo=ready
  v
发送 SIP INVITE
  |
  | 设备返回 200 OK
  v
SIPCommander
  |
  | sessionManager.put(SsrcTransaction)
  v
SipInviteSessionManager
  |
  | InviteOKHandler 保存 InviteInfo=ok
  | 但 streamInfo 可能为空
  v
设备开始发送 RTP
  |
  v
ZLM on_stream_changed(register)
  |
  v
MediaArrivalEvent -> on_media_arrival
  |
  v
RtpResourceContext: WAITING_MEDIA -> SUCCESS
  |
  v
onPublishHandlerForPlay
  |
  | 构造 StreamInfo
  | 更新 InviteInfo=ok + streamInfo
  | 设置通道播放状态
  v
点播成功回调
```

## 6. 没有媒体流时的失败路径

如果设备返回 200 OK，但后续没有真正发送 RTP，可能出现：

```text
InviteInfo.status = ok
InviteInfo.streamInfo = null
SsrcTransaction 存在
RtpResourceContext = WAITING_MEDIA
```

等待超时后，RTP 资源转为超时，PlayService 删除 InviteInfo，按 stream 查询 `SsrcTransaction`，尝试发送 BYE，调用 `removeByStream()` 清理 SIP 会话索引，并关闭 RTP 资源。

对应代码位置：`src/main/java/com/genersoft/iot/vmp/gb28181/service/impl/PlayServiceImpl.java:419`。

排查点播是否真正成功时，不能只看 SIP 200 OK 或 `InviteInfo.status=ok`，还应确认：

- `InviteInfo.streamInfo` 不为空；
- `RtpResourceContext` 已经进入 `SUCCESS`；
- 出现 `[RTP媒体到达]` 或 `[点播成功]` 日志；
- ZLM 已注册对应 RTP 流；
- 点播成功回调已经返回 `StreamInfo`。

## 7. 与停止流程的关系

停止点播时，`PlayServiceImpl.stopInviteResourcesAfterRemoval()` 根据 `InviteInfo.status` 判断是否需要发送 BYE：

```java
if (InviteSessionStatus.ok == inviteInfo.getStatus()) {
    safeSendBye(...);
}
```

因此：

- `ready` 阶段停止：通常没有完整 SIP Dialog，不发送 BYE，直接清理 RTP 和 InviteInfo。
- SIP 200 OK 后停止：状态已经是 `ok`，会尝试发送 BYE。
- 媒体已经成功后停止：发送 BYE、关闭 RTP、清理 InviteInfo 和 SsrcTransaction。

这也是 SIP 会话和媒体资源必须分开管理的原因：即使媒体未到达，只要 SIP 200 OK 已建立，仍可能需要通过 `SipInviteSessionManager` 找到 SIP Transaction 并发送 BYE。

## 8. 最终判断条件

当前点播流程可以概括为：

```text
ready
  = RTP 资源准备好，等待 SIP/媒体

SIP 200 OK
  = 设备接受点播，保存 SsrcTransaction，InviteInfo 可能先变为 ok

媒体到达
  = RTP 资源确认成功，补充 streamInfo，向点播调用方报告成功
```

最可靠的业务成功条件是：

```text
InviteInfo.status == ok
&& InviteInfo.streamInfo != null
&& RTP 资源状态 == SUCCESS
```
