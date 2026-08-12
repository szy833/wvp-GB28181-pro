# 压测混合监控与容量报告 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 GB28181 压测工具接入 Prometheus/ZLM 混合监控，并输出可解释的业务、资源和容量判定报告。

**Architecture:** 业务事件继续由 `runner` 和 `device` 采集；新增独立 `monitoring` 包负责 Prometheus query_range、ZLM 流采样和有界超时；`metrics` 包负责业务/资源摘要、SLO 四态判定和 JSON/CSV/Markdown 输出。采集失败只标记不可用，不阻塞压测调度。

**Tech Stack:** Go 1.22+、标准库 `net/http`、`encoding/json`、`encoding/csv`、现有 YAML 配置和线程安全指标注册表。

## Global Constraints

- 不修改、删除或提交用户已有的 `tools/application-dev.yml`、`tools/gb28181-load-test/bin/`、`configs/test-10.yaml`、`media/`、`reports/` 和 `t.cap`。
- 不在报告保存密码、控制 token、ZLM secret、SIP Authorization 或带查询参数的完整 URL。
- `monitoring.enabled=false` 时现有配置和业务报告行为保持兼容。
- Prometheus/ZLM 采集使用独立 context 和有界超时，不能阻塞 REGISTER、点播和停止调度。
- 缺失、空结果和采集错误不能填成 0；关键监控缺失时判定 `INCONCLUSIVE`。
- 所有测试和 Git 提交信息使用中文说明；真实 WVP/ZLM/Prometheus 联调风险记录到 `docs`。

---

### Task 1: 监控配置和 Prometheus 客户端

**Files:**
- Modify: `tools/gb28181-load-test/internal/config/config.go`
- Create: `tools/gb28181-load-test/internal/monitoring/prometheus.go`
- Test: `tools/gb28181-load-test/internal/config/config_test.go`
- Test: `tools/gb28181-load-test/internal/monitoring/prometheus_test.go`

**Interfaces:**
- `config.Config.Monitoring MonitoringConfig`
- `monitoring.PrometheusClient.QueryRange(ctx context.Context, query string, start, end time.Time, step time.Duration) (QueryResult, error)`
- `monitoring.QueryResult { Metric string; Samples []Sample; Available bool; Error string }`

- [ ] **Step 1: 写配置失败测试**：覆盖 `sampleInterval`、Prometheus URL、queryTimeout、step、SLO 比例/百分比越界和 `monitoring.enabled=false` 的兼容默认值。
- [ ] **Step 2: 运行配置测试确认失败**：`PATH=/usr/local/go/bin:$PATH go test ./internal/config -run TestMonitoring -count=1`，应因 `MonitoringConfig` 未定义失败。
- [ ] **Step 3: 实现配置模型和校验**：增加 `MonitoringConfig`、`PrometheusConfig`、`ZLMConfig`、`SLOConfig`、`PrometheusQueries`，解析 duration 字符串，按设计默认 `15s/10s`，不复制 secret 到报告摘要。
- [ ] **Step 4: 写 Prometheus HTTP 客户端失败测试**：使用 `httptest.Server` 覆盖矩阵响应、向量响应、HTTP 错误、空 data、超时和 URL 脱敏。
- [ ] **Step 5: 运行客户端测试确认失败**：`PATH=/usr/local/go/bin:$PATH go test ./internal/monitoring -run TestPrometheus -count=1`。
- [ ] **Step 6: 实现 query_range 客户端**：发送 `GET /api/v1/query_range`，传 `query/start/end/step`，解析 `status=data.resultType=matrix|vector`，每个样本保存时间戳、值和 labels；使用 `http.Client` 超时和 context；错误返回 `Available=false`。
- [ ] **Step 7: 运行 Task 1 测试**：`PATH=/usr/local/go/bin:$PATH go test ./internal/config ./internal/monitoring -count=1`，预期全部通过。
- [ ] **Step 8: 提交**：`git add ... && git commit -m "增加压测Prometheus监控配置"`。

### Task 2: ZLM 流采样和资源采样模型

**Files:**
- Create: `tools/gb28181-load-test/internal/monitoring/zlm.go`
- Create: `tools/gb28181-load-test/internal/monitoring/sampler.go`
- Test: `tools/gb28181-load-test/internal/monitoring/zlm_test.go`
- Test: `tools/gb28181-load-test/internal/monitoring/sampler_test.go`

**Interfaces:**
- `monitoring.ZLMClient.ListStreams(ctx context.Context) (ZLMSnapshot, error)`
- `monitoring.ZLMSnapshot { Available bool; SampledAt time.Time; TotalStreams int; RTPStreams int; BusinessStreams int; Streams []ZLMStream; APILatencySeconds float64; Error string }`
- `monitoring.ResourceSampler.Start(ctx, start time.Time) <-chan ResourceSample`

- [ ] **Step 1: 写 ZLM 解析失败测试**：覆盖 ZLM `getMediaList` 响应、RTP origin 识别、业务 stream 过滤、HTTP 错误和残留流计算输入。
- [ ] **Step 2: 运行测试确认失败**：`PATH=/usr/local/go/bin:$PATH go test ./internal/monitoring -run TestZLM -count=1`。
- [ ] **Step 3: 实现 ZLM 客户端**：调用 `/index/api/getMediaList`，传 `secret`（仅请求使用），统计总流、`schema=rtp`/originType 为 RTP 的流和 app/stream；记录 API 延迟和脱敏错误。
- [ ] **Step 4: 写 sampler 失败测试**：验证 sample interval、停止 context、有界样本数、Prometheus/ZLM 错误样本保留。
- [ ] **Step 5: 实现采样器**：按 interval 并发/串行采集，单轮超时不超过 queryTimeout；保存最大样本数，结束时执行一次 final ZLM snapshot；采集线程退出后关闭 channel。
- [ ] **Step 6: 运行 Task 2 测试**：`PATH=/usr/local/go/bin:$PATH go test ./internal/monitoring -run 'Test(ZLM|Sampler)' -count=1`。
- [ ] **Step 7: 提交**：`git add ... && git commit -m "增加压测ZLM和资源采样"`。

### Task 3: 业务延迟、资源摘要和 SLO 判定

**Files:**
- Modify: `tools/gb28181-load-test/internal/metrics/report.go`
- Modify: `tools/gb28181-load-test/internal/runner/scheduler.go`
- Modify: `tools/gb28181-load-test/internal/runner/runner.go`
- Create: `tools/gb28181-load-test/internal/metrics/verdict.go`
- Test: `tools/gb28181-load-test/internal/metrics/verdict_test.go`
- Test: `tools/gb28181-load-test/internal/runner/scheduler_test.go`

**Interfaces:**
- `metrics.BusinessSummary`
- `metrics.ResourceSummary { Avg, P95, Max float64; Available bool; Samples int }`
- `metrics.Verdict { Name string; BusinessSLOPassed bool; ResourceSLOPassed bool; MonitoringComplete bool; FailureReasons []string }`
- `metrics.EvaluateSLO(input SLOInput) Verdict`

- [ ] **Step 1: 写 verdict 失败测试**：覆盖 PASS、WARNING、FAIL、INCONCLUSIVE；验证缺失资源不作为 0、残留流超限失败、延迟 P95/P99 超限失败。
- [ ] **Step 2: 运行测试确认失败**：`PATH=/usr/local/go/bin:$PATH go test ./internal/metrics -run TestEvaluateSLO -count=1`。
- [ ] **Step 3: 实现摘要和四态判定**：从业务事件和直方图计算成功率/延迟，从资源样本计算 avg/P95/max；按设计顺序生成原因列表，区分硬失败和接近阈值 warning。
- [ ] **Step 4: 写 scheduler 延迟失败测试**：验证一次点播记录 INVITE、媒体确认、停止三个阶段延迟，并在媒体未确认时保留空值和错误。
- [ ] **Step 5: 实现事件时间关联**：给 `PlayResult` 增加 `InviteAt/MediaAt/StopStartedAt` 和阶段 latency；事件 collector 按 device/channel 或 play ID 安全关联，不改变现有调度节奏。
- [ ] **Step 6: 运行 Task 3 测试**：`PATH=/usr/local/go/bin:$PATH go test ./internal/metrics ./internal/runner -count=1`。
- [ ] **Step 7: 提交**：`git add ... && git commit -m "增加压测SLO判定和阶段延迟"`。

### Task 4: 完整报告、配置接线和文档

**Files:**
- Modify: `tools/gb28181-load-test/cmd/gb28181-load-test/main.go`
- Modify: `tools/gb28181-load-test/internal/metrics/report.go`
- Modify: `tools/gb28181-load-test/internal/config/config.go`
- Modify: `tools/gb28181-load-test/README.md`
- Create: `tools/gb28181-load-test/internal/metrics/markdown.go`
- Test: `tools/gb28181-load-test/internal/metrics/markdown_test.go`
- Create: `docs/GB28181虚拟设备压测工具混合监控联调记录.md`

**Interfaces:**
- `metrics.WriteResourceCSV(path string, samples []ResourceSample) error`
- `metrics.WriteMarkdown(path string, report Report) error`
- `metrics.DetailCSVPath` extended for resources and markdown paths.

- [ ] **Step 1: 写报告失败测试**：验证 JSON 包含 summary/business/resources/zlm，Markdown 包含 verdict、SLO、资源表和缺失原因，secret/token/password 不出现。
- [ ] **Step 2: 运行报告测试确认失败**：`PATH=/usr/local/go/bin:$PATH go test ./internal/metrics -run 'Test(WriteMarkdown|WriteResource)' -count=1`。
- [ ] **Step 3: 实现资源 CSV 和 Markdown**：固定字段顺序、空值和 available/error 表达；Markdown 输出下一档压测建议，但不虚构稳定容量。
- [ ] **Step 4: 接线主程序**：按配置启动 sampler，结束后停止并汇总；写 JSON、阶段 CSV、明细 CSV、资源 CSV、Markdown；打印 verdict 和路径；monitoring disabled 时不启动采集器。
- [ ] **Step 5: 更新 README**：补充 Prometheus/exporter 部署要求、配置模板、Grafana/PromQL 变量、报告字段和 PASS/WARNING/FAIL/INCONCLUSIVE 含义。
- [ ] **Step 6: 记录真实联调风险**：在 `docs/GB28181虚拟设备压测工具混合监控联调记录.md` 记录 WVP/ZLM/Prometheus/exporter 启动条件、端口、鉴权、测试命令和已知缺失指标。
- [ ] **Step 7: 运行 Task 4 测试**：`PATH=/usr/local/go/bin:$PATH go test ./internal/metrics ./internal/device ./internal/runner -count=1`。
- [ ] **Step 8: 提交**：`git add ... && git commit -m "完善压测混合监控报告输出"`。

### Task 5: 验证和集成测试

**Files:**
- Modify only generated report paths under `tools/gb28181-load-test/reports/`; do not stage them.

- [ ] **Step 1: 运行受影响 Go 单元测试**：`PATH=/usr/local/go/bin:$PATH go test ./internal/config ./internal/monitoring ./internal/metrics ./internal/device ./internal/runner -count=1`。
- [ ] **Step 2: 运行竞态测试**：`PATH=/usr/local/go/bin:$PATH go test -race ./internal/monitoring ./internal/metrics ./internal/runner -count=1`。
- [ ] **Step 3: 构建二进制**：`PATH=/usr/local/go/bin:$PATH go build -o bin/gb28181-load-test ./cmd/gb28181-load-test`；构建产物不提交。
- [ ] **Step 4: 运行集成压测**：配置 WVP、ZLM、Prometheus 和 exporter 后执行 `GB28181_LOADTEST_INTEGRATION=1 ./scripts/run-integration.sh configs/test-10.yaml`。
- [ ] **Step 5: 检查报告和指标**：核对 JSON verdict、资源可用性、ZLM residualStreams、`run.resources.csv`、`run.md` 和 `/metrics`；Prometheus/ZLM 不可用时必须是 `INCONCLUSIVE`。
- [ ] **Step 6: 检查用户文件和提交范围**：`git status --short`，确认不包含 `bin/`、`media/`、`reports/`、`test-10.yaml`、`application-dev.yml` 和 `t.cap`。
