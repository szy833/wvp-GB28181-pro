# GB28181 虚拟设备并发压测工具

这是一个独立 Go 工具，用于在测试环境模拟大量 GB28181 设备。工具不修改 WVP 代码，默认只使用 UDP SIP；RTP/PS 发送器和状态机场景可在本地单元测试中验证。

## 构建和运行

需要 Go 1.22 或更高版本：

```bash
go test ./...
go run ./cmd/gb28181-load-test -config configs/example.yaml
```

设备 ID 必须以 `loadtest-` 开头，避免误连生产设备。`media.file` 应指向预编码 PS 文件；第一阶段的 UDP RTP 发送不负责把 MP4 转换成 PS，可使用 `scripts/prepare-media.sh` 生成测试文件。

## 测试场景

- `steady-play`：保持设备在线并持续点播。
- `no-media-timeout`：只完成信令，不发送媒体，验证 WVP 收流超时清理。
- `device-bye`：设备主动发送 BYE，验证会话和 RTP 资源释放。
- `churn-play`：周期性创建和销毁播放，观察峰值与资源回收。

真实 WVP、Redis、ZLMediaKit 联调需要单独的测试网络和授权，不能由本地单元测试代替。
