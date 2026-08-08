# GB28181 虚拟设备并发压测工具

完整的安装、配置、WVP 准备、阶梯压测和排障步骤见仓库文档：`../../docs/GB28181虚拟设备压测工具使用手册.md`。

这是一个独立 Go 工具，用于在测试环境模拟大量 GB28181 设备并驱动 WVP 点播。工具不修改 WVP 代码，支持 UDP SIP、Digest REGISTER、Catalog/DeviceInfo/DeviceStatus 查询响应、UDP RTP/PS、TCP-PASSIVE RTP、共享媒体文件、WVP HTTP 点播调度和 Prometheus 指标。

## 构建和运行

需要 Go 1.22 或更高版本：

```bash
go test ./...
go run ./cmd/gb28181-load-test -config configs/example.yaml
```

设备 ID 必须以 `loadtest-` 开头，避免误连生产设备。配置中的 `sip.advertise` 必须是 WVP 能访问到的虚拟设备主机地址；`sip.local` 可以使用 `0.0.0.0:0`，工具会为每个设备分配独立端口。`device.username` 支持 `{deviceId}` 模板，推荐使用该模板让每台虚拟设备使用自己的注册用户名。

`media.file` 应指向预编码 PS 文件。工具只在启动时读取一次文件，所有虚拟设备共享只读媒体缓冲区，每个播放建立独立读取游标。

## 测试场景

- `steady-play`：保持设备在线并持续点播。
- `no-media-timeout`：只完成信令，不发送媒体，验证 WVP 收流超时清理。
- `device-bye`：设备主动发送 BYE，验证会话和 RTP 资源释放。
- `churn-play`：周期性创建和销毁播放，观察峰值与资源回收。

当 `control.baseUrl` 不为空时，运行器会按 `scenario.playRate` 调用 WVP `/api/play/start/{deviceId}/{channelId}`，并在 `scenario.playDuration` 后调用停止接口。WVP 需要 JWT 时，将登录返回的 `access-token` 值填入 `control.token`，不要添加 `Bearer` 前缀。实时指标地址由 `metricsAddr` 配置，访问 `/metrics`。

`no-media-timeout` 场景仍返回 INVITE 的 200 OK，但不会发送 RTP，用于验证 WVP 收流超时和资源清理；`device-bye` 场景在媒体启动后由虚拟设备主动发送 BYE。

真实 WVP、Redis、ZLMediaKit 联调需要单独的测试网络和授权，不能由本地单元测试代替。
