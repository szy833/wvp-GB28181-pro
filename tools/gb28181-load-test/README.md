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

`media.file` 应指向逐帧 MPEG-2 PS 文件。工具只在启动时读取一次文件，所有虚拟设备共享只读媒体缓冲区，每个播放建立独立读取游标。按视频 PES 的 DTS（没有 DTS 时使用 PTS）分组并调度，循环时同时推进 RTP、SCR 和 PTS/DTS。

旧脚本生成的 MPEG-1 PS，或通用 `mpeg`/`vob` muxer 聚合多个帧的 PS，不适用于本工具的逐帧时间轴，启动时会拒绝。请使用**最新二进制**重新生成素材，保留旧文件并更新配置中的 `media.file`：

```bash
./scripts/prepare-media.sh input.mp4 media/sample-v2.ps
# 也可以使用原 PS 作为转码输入；输出必须是新路径。
./bin/gb28181-load-test -prepare-media media/sample.ps -media-output media/sample-v2.ps -media-fps 25
```

生成素材需要 FFmpeg（含 libx264），不需要 Go。脚本支持 `GB28181_LOADTEST_BIN` 和 `GB28181_MEDIA_FPS`；默认 25 FPS。离线模式不会读取运行配置或启动设备：先转为 CFR、无 B 帧、带 AUD 的 H.264/yuv420p，再生成 H.264 PSM 和每帧独立 PTS 的 MPEG-2 PS；大帧拆为多个有相同 PTS 的有界 PES。输入至少两帧，原始 H.264 和最终 PS 均限 512 MiB，不覆盖已有输出。每次生成都会转码，不保证与原视频编码参数或码率相同，压测前需要重新校准素材码率。

每次 INVITE 使用独立 sender，停止后不能再次启动旧 sender；迟到或重复 ACK 不会重新打开已清理的媒体连接。TCP 建连和写入均有 5 秒期限，发送循环退出时关闭连接。

`scenario.maxConcurrent` 限制的是完整的“点播请求 → 持续播放 → 停止”生命周期数，不只是等待 HTTP 响应的数量。同一虚拟设备不会被重复调度并发点播。配置了 `scenario.duration` 的在线专项可以将 `playRate` 设置为 `0`，设备保持运行到时长结束，无需填写一个很小的非零点播速率。

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

Prometheus `/metrics` 继续提供原有计数器和延迟的 `_count`、`_sum`、`_max`，并增加 `_p50`、`_p95`、`_p99` 近似分位数，以及 REGISTER、Catalog、INVITE、媒体启动和设备 BYE 阶段计数。`rtp_packets_sent_total`、`rtp_bytes_sent_total`、`rtp_send_errors_total` 每秒更新并在停止时补齐；实际发送速率可以观察 `rate(rtp_bytes_sent_total[1m]) * 8`（bit/s）。TCP 字节数包含工具的 RTP 分帧开销，不含 IP/链路层开销。JSON/CSV 不写入设备密码、控制 token 或 SIP Authorization。

发送终止分类补充：`rtp_local_cancellations_total` 只统计已取消上下文中的明确本地关闭/取消异常，不计入 `rtp_send_errors_total`；正常取消直接结束循环而没有异常时，不增加该计数。`rtp_remote_closures_total` 是发送错误的子集，对端 reset/broken pipe 即使与 Stop 同时发生也不会自动忽略。源失败、超时和其他发送失败仍为错误。JSON 设备记录新增 `rtpLocalCancellations`、`rtpRemoteClosures`、`rtpDiagnosticsDropped` 和 `rtpDiagnostics`；CSV 在原有列后追加这四列，Markdown/终端也输出分类。明细包含时间、Call-ID、通道、错误类别/安全错误码、上下文取消标记及**采样时**设备状态（不能代替错误发生时的操作阶段）。每个发送器保留最近 8 条、每设备报告保留最近 32 条；截断会计数，不存任意原始错误字符串。定位停止问题须结合停止窗口、BYE 和终态资源核验，不能仅凭计数把停止阶段所有错误视为正常。

REGISTER 成功率按设备统计，Digest 401 挑战不是注册失败；续订及请求/挑战原始数量保留在明细。Catalog 仅统计收到 Catalog 查询后的响应发送情况，不包含 DeviceInfo/DeviceStatus，也不代表 WVP 通道持久化已经确认。未执行的检查显示 `N/A`，JSON 对应成功率为 `null`。`senderAt` 是本地发送器启动，`mediaAt` 是 WVP 点播成功响应中确认了对应 `rtp/device_channel`，两者不混用。`deviceByeSent` 不是成功确认；`deviceByeSuccess`、`deviceByeErrors`、`deviceByeTimeouts` 分别记录最终结果。

## 混合监控与容量判定

工具可同时采集业务、ZLM 和 Prometheus 资源指标：

```yaml
monitoring:
  enabled: true
  sampleInterval: 15s
  cleanupTimeout: 30s
  cleanupInterval: 1s
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

Prometheus 需要已部署 WVP、主机、Redis 和 MySQL exporter；不同环境的指标名可通过 `monitoring.prometheus.queries` 覆盖。Prometheus 和 ZLM 可以分别启用：仅使用 ZLM 时省略 `prometheus.url` 即可，但资源容量判定不完整，不能用于证明系统仍有余量。

ZLM 流采样按本次设备/通道编号及 INVITE 中的 SSRC 过滤，多种输出协议按业务流去重；`nodeTotalStreams` 保留节点全局媒体条目数量供参考。结束后在清理宽限期内轮询 `getMediaList`、`listRtpServer` 和 WVP `/api/play/ssrc`，连续两轮确认本次资源归零才认为收敛。接口不可用、检查超时或没有 WVP 控制地址时，相应检查标记不可确认。不会调用全局关闭接口，也不会直接验证/删除 Redis InviteInfo 和 SIP 索引。测试编号段仍必须独占，不能与其他正在运行的测试共用。

明细 CSV 保留原有前 27 列，在末尾追加注册尝试、查询分类、BYE 结果、实际发送计数和本地发送器时间字段。消费 CSV 的脚本应按列名读取。故障注入场景不能套用正常媒体场景的容量成功率阈值。

报告会输出 `PASS`、`WARNING`、`FAIL` 或 `INCONCLUSIVE`：监控不可用时不会把缺失数据当作 0。`run.resources.csv` 保存原始采样，`run.md` 提供人工审阅版容量报告。只有多个阶梯负载都满足 SLO，才能确认稳定容量；单次报告只表示当前负载的判定。

## 测试场景

- `steady-play`：保持设备在线并持续点播。
- `no-media-timeout`：只完成信令，不发送媒体，验证 WVP 收流超时清理。
- `device-bye`：设备主动发送 BYE，验证会话和 RTP 资源释放。
- `churn-play`：周期性创建和销毁播放，观察峰值与资源回收。

当 `control.baseUrl` 不为空时，运行器会按 `scenario.playRate` 调用 WVP `/api/play/start/{deviceId}/{channelId}`，并在 `scenario.playDuration` 后调用停止接口。WVP 需要 JWT 时，将登录返回的 `access-token` 值填入 `control.token`，不要添加 `Bearer` 前缀。实时指标地址由 `metricsAddr` 配置，访问 `/metrics`。

`no-media-timeout` 场景仍返回 INVITE 的 200 OK，但不会发送 RTP，用于验证 WVP 收流超时和资源清理；`device-bye` 场景在媒体启动后由虚拟设备主动发送 BYE。

真实 WVP、Redis、ZLMediaKit 联调需要单独的测试网络和授权，不能由本地单元测试代替。
