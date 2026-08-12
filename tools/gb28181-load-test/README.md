# GB28181 虚拟设备并发压测工具

完整的安装、配置、WVP 准备、阶梯压测和排障步骤见仓库文档：`../../docs/GB28181虚拟设备压测工具使用手册.md`。

这是一个独立 Go 工具，用于在测试环境模拟大量 GB28181 设备并驱动 WVP 点播。工具不修改 WVP 代码，支持 UDP SIP、Digest REGISTER、Catalog/DeviceInfo/DeviceStatus 查询响应、UDP RTP/PS、TCP-PASSIVE RTP、共享媒体文件、WVP HTTP 点播调度和 Prometheus 指标。

## 构建和运行

需要 Go 1.22 或更高版本：

```bash
go test ./...
go run ./cmd/gb28181-load-test -config configs/example.yaml
```

运行阶段不需要 Go。可以在有 Go 的构建机上生成二进制，再将 `bin/gb28181-load-test`、配置文件和 `media` 目录复制到压测机：

```bash
go build -o bin/gb28181-load-test ./cmd/gb28181-load-test
GB28181_LOADTEST_INTEGRATION=1 \
  ./scripts/run-integration.sh configs/test-10.yaml
```

如果二进制放在其他位置，可通过 `GB28181_LOADTEST_BIN` 指定：

```bash
GB28181_LOADTEST_INTEGRATION=1 \
GB28181_LOADTEST_BIN=/opt/gb28181-load-test \
  ./scripts/run-integration.sh /opt/gb28181-load-test/test.yaml
```

默认 `identity.mode: numeric` 生成 20 位纯数字的设备和通道 ID，可以直接通过 WVP 的严格 GB28181 设备编号校验。`deviceBase` 和 `channelBase` 的前 14 位组成测试编号段，最后 6 位是递增序号；编号段必须是测试专用，不能与生产设备重叠。需要兼容旧配置时可显式使用 `identity.mode: legacy` 和 `idPrefix: loadtest-`，但该模式在 WVP 严格设备 ID 校验开启时会被拒绝。配置中的 `sip.advertise` 必须是 WVP 能访问到的虚拟设备主机地址；`sip.local` 可以使用 `0.0.0.0:0`，工具会为每个设备分配独立端口。`device.username` 支持 `{deviceId}` 模板，推荐使用该模板让每台虚拟设备使用自己的注册用户名。

`media.file` 应指向预编码 PS 文件。工具只在启动时读取一次文件，所有虚拟设备共享只读媒体缓冲区，每个播放建立独立读取游标。

## 压测结果

运行结束后，控制台会打印设备启动阶段、点播阶段、成功率、点播停止结果和控制接口延迟分位数。例如：

```text
run=demo-20260804 devices=1000 duration=600.0s
stage=startup devices=1000 success=1000 failed=0 successRate=100.0% duration=1.245s
stage=play devices=12000 success=11980 failed=20 successRate=99.8% duration=600.0s
events registerSuccess=1000 catalogResponses=1000 inviteSuccess=11980 mediaStarted=11980 deviceBye=0 deviceByeSent=11960
plays total=12000 success=11980 failed=20 stopSuccess=11960
latency wvp_control_latency_seconds P50=0.100s
latency wvp_control_latency_seconds P95=0.500s
latency wvp_control_latency_seconds P99=1.000s
details=./reports/run.details.csv
```

报告文件包含三种粒度：

- `report.json`：完整配置摘要、阶段汇总、每台设备的 REGISTER/Catalog/INVITE/RTP/BYE 事件计数、每次点播的开始/停止结果和错误信息。
- `report.csv`：阶段汇总，保持原有格式兼容。
- `report.details.csv`：设备和点播明细；当 `report.detailCsv` 未配置时，由 `report.csv` 自动派生。例如 `run.csv` 对应 `run.details.csv`。

可以显式指定明细文件：

```yaml
report:
  json: ./reports/run.json
  csv: ./reports/run.csv
  detailCsv: ./reports/run.details.csv
```

Prometheus `/metrics` 继续提供原有计数器和延迟的 `_count`、`_sum`、`_max`，并增加 `_p50`、`_p95`、`_p99` 近似分位数，以及 REGISTER、Catalog、INVITE、媒体启动和设备 BYE 阶段计数。JSON/CSV 不写入设备密码、控制 token 或 SIP Authorization。

## 混合监控与容量判定

工具可同时采集业务、ZLM 和 Prometheus 资源指标：

```yaml
monitoring:
  enabled: true
  sampleInterval: 15s
  prometheus:
    url: http://127.0.0.1:9090
    queryTimeout: 10s
    step: 15s
  zlm:
    enabled: true
    url: http://127.0.0.1:19001
    mediaServerId: zlmediakit-local
report:
  resourcesCsv: ./reports/run.resources.csv
  markdown: ./reports/run.md
```

Prometheus 需要已部署 WVP、主机、Redis 和 MySQL exporter；不同环境的指标名可通过 `monitoring.prometheus.queries` 覆盖。ZLM 采集 `getMediaList` 的流数量、RTP 流、业务流和最终残留流。

报告会输出 `PASS`、`WARNING`、`FAIL` 或 `INCONCLUSIVE`：监控不可用时不会把缺失数据当作 0。`run.resources.csv` 保存原始采样，`run.md` 提供人工审阅版容量报告。只有多个阶梯负载都满足 SLO，才能确认稳定容量；单次报告只表示当前负载的判定。

## 测试场景

- `steady-play`：保持设备在线并持续点播。
- `no-media-timeout`：只完成信令，不发送媒体，验证 WVP 收流超时清理。
- `device-bye`：设备主动发送 BYE，验证会话和 RTP 资源释放。
- `churn-play`：周期性创建和销毁播放，观察峰值与资源回收。

当 `control.baseUrl` 不为空时，运行器会按 `scenario.playRate` 调用 WVP `/api/play/start/{deviceId}/{channelId}`，并在 `scenario.playDuration` 后调用停止接口。WVP 需要 JWT 时，将登录返回的 `access-token` 值填入 `control.token`，不要添加 `Bearer` 前缀。实时指标地址由 `metricsAddr` 配置，访问 `/metrics`。

`no-media-timeout` 场景仍返回 INVITE 的 200 OK，但不会发送 RTP，用于验证 WVP 收流超时和资源清理；`device-bye` 场景在媒体启动后由虚拟设备主动发送 BYE。

真实 WVP、Redis、ZLMediaKit 联调需要单独的测试网络和授权，不能由本地单元测试代替。
