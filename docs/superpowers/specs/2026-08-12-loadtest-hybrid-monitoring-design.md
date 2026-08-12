# 压测混合监控与容量报告设计

## 1. 目标与边界

为 `tools/gb28181-load-test` 增加系统级容量观测能力，将压测工具的业务事件、ZLMediaKit 媒体状态和 Prometheus 资源指标合并为一份可判定的测试报告。

本设计解决“业务请求成功但系统已经资源饱和”以及“资源正常但媒体链路失败”无法区分的问题。单次测试只给出本次负载的 SLO 判定，不把单次结果误认为系统最大容量；稳定容量需要按阶梯负载执行多次测试后比较。

范围包括：

- 压测工具自身业务指标和阶段延迟；
- ZLM HTTP API 的流状态采样和测试结束后的残留流检查；
- Prometheus `query_range` 资源指标采集；
- 业务 SLO、资源 SLO、监控完整性和资源清理的统一判定；
- JSON、阶段 CSV、设备/点播明细 CSV、资源采样 CSV 和 Markdown 人读报告。

不在本次范围内：

- 在 WVP、ZLM、Redis 或 MySQL 中新增 exporter；
- 自动部署 Prometheus、Node Exporter 或数据库 exporter；
- 自动执行多档位压测和跨报告容量二分；
- 将 Redis/MySQL 密码写入压测配置或报告。

## 2. 设计原则

1. **业务与资源分层**：HTTP、SIP、RTP、媒体确认属于业务链路；CPU、内存、线程池、Redis、MySQL、网络属于资源链路，报告分别计算后再汇总。
2. **混合采集**：业务指标由压测工具直接采集；ZLM 流状态直接查询；系统资源使用 Prometheus 统一查询。
3. **缺失不等于零**：Prometheus 或 ZLM 不可用时保存错误和缺失状态，不把缺失样本填成 0，也不判定为通过。
4. **时间窗口统一**：所有采样和 Prometheus 查询使用测试开始、结束时间及配置的 step；查询结果必须记录实际时间范围。
5. **报告脱敏**：URL 只保留协议、主机和端口；ZLM secret、控制 token、设备密码和 SIP Authorization 永不写入报告。
6. **可覆盖查询**：默认 PromQL 提供常用指标，配置可以按部署标签覆盖查询，查询名保持稳定。

## 3. 配置模型

在现有配置增加 `monitoring`：

```yaml
monitoring:
  enabled: true
  sampleInterval: 15s
  prometheus:
    url: http://127.0.0.1:9090
    queryTimeout: 10s
    step: 15s
    queries:
      wvpCpu: process_cpu_usage
      jvmHeap: jvm_memory_used_bytes / jvm_memory_max_bytes * 100
      redisMemory: redis_memory_used_bytes
      mysqlConnections: mysql_global_status_threads_connected
      hostCpu: 100 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100
  zlm:
    enabled: true
    url: http://127.0.0.1:19001
    secret: ""
    mediaServerId: zlmediakit-local
  slo:
    registerSuccessRate: 0.999
    catalogSuccessRate: 0.995
    playSuccessRate: 0.990
    mediaSuccessRate: 0.990
    stopSuccessRate: 0.990
    controlLatencyP95Seconds: 5
    controlLatencyP99Seconds: 10
    maxResidualSessions: 0
    maxResidualStreams: 0
    maxCpuPercent: 85
    maxJvmHeapPercent: 85
    maxRedisCpuPercent: 80
    maxNetworkPercent: 85
```

字段要求：

- `monitoring.enabled=false` 时保持现有压测行为，只输出业务报告；
- `sampleInterval` 和 Prometheus `step` 必须为正时间，默认 15 秒；
- Prometheus、ZLM 地址必须能解析；启用对应采集器时地址不能为空；
- SLO 比例取 `0..1`，资源百分比取 `0..100`，残留数量不能为负数；
- 查询表达式为空时使用内置默认查询；自定义查询只替换对应指标，不影响其他查询；
- 报告配置增加 `resourcesCsv`、`markdown`，为空时分别由 `report.csv` 派生为 `<name>.resources.csv`、`<name>.md`。

## 4. 采集架构

### 4.1 业务指标

沿用现有设备事件和点播调度收集器，并补充以下计数和延迟：

```text
register_requests_total
register_success_total
catalog_responses_total
play_requests_total
play_start_success_total
play_start_error_total
invite_success_total
media_started_total
play_stop_success_total
play_stop_error_total
residual_sessions
residual_streams
```

延迟至少拆分为：

```text
wvp_control_latency_seconds
invite_latency_seconds
media_confirmation_latency_seconds
stop_latency_seconds
```

每次点播明细保存请求开始、INVITE 成功、媒体确认、停止开始和停止完成时间；无法确认的阶段保持空值并记录错误原因。

### 4.2 ZLM 采样器

`ZLMCollector` 在测试开始后按 `sampleInterval` 调用 ZLM HTTP API，采集：

- 采样时间和媒体节点 ID；
- API 请求耗时、HTTP 状态和错误；
- 当前媒体流总数、RTP 流数量、`app/stream` 数量；
- 当前测试业务流数量；
- 测试结束后的最终流数量和残留业务流列表。

采集失败写入 `available=false`、错误信息和 `apiErrors`，不填零。最终残留流检查必须至少执行一次；若 ZLM 不可用，报告判定为 `INCONCLUSIVE`，除非监控被明确关闭。

### 4.3 Prometheus 查询器

`PrometheusClient` 使用 `/api/v1/query_range`，为每个查询传入统一 `start/end/step`，解析矩阵或向量结果为带时间戳的样本。每个资源序列保留：

```text
name, timestamp, value, labels, available, error
```

默认查询覆盖：

- WVP CPU、JVM 堆使用率、GC 暂停、HTTP 延迟、线程池 active/queue；
- 主机 CPU、内存、网络、文件描述符；
- Redis CPU、内存、连接数、阻塞客户端、命令速率；
- MySQL 连接数、运行线程、慢查询。

查询无样本、接口错误或解析错误都计入监控缺失，报告保留具体原因。采集器不要求所有查询都存在：只有配置启用且被 SLO 使用的查询属于关键指标。

## 5. 报告模型

保留现有 `run.json`、`run.csv` 和 `run.details.csv`，新增：

```text
run.resources.csv
run.md
```

JSON 顶层增加：

```json
{
  "summary": {
    "verdict": "PASS|WARNING|FAIL|INCONCLUSIVE",
    "stableCapacity": false,
    "failureReasons": [],
    "businessSloPassed": true,
    "resourceSloPassed": true,
    "monitoringComplete": true
  },
  "business": {
    "registerSuccessRate": 1.0,
    "catalogSuccessRate": 1.0,
    "playSuccessRate": 0.99,
    "mediaSuccessRate": 0.99,
    "stopSuccessRate": 0.99,
    "latency": {
      "controlP95": 0.25,
      "controlP99": 0.50,
      "mediaConfirmationP95": 2.8
    }
  },
  "resources": {
    "wvpCpu": {"avg": 62, "p95": 76, "max": 81},
    "jvmHeapPercent": {"avg": 54, "p95": 72, "max": 78},
    "redisCpu": {"avg": 31, "p95": 48, "max": 55}
  },
  "zlm": {
    "peakStreams": 200,
    "residualStreams": 0,
    "apiErrors": 0
  }
}
```

`resources` 同时保留原始采样摘要和可用性；`zlm` 保留采样峰值、最终数量、残留流和 API 错误。Markdown 报告按“判定、业务、资源、ZLM、错误、建议、缺失数据”顺序输出，便于人工审阅。

资源 CSV 字段至少包括：

```text
source,metric,timestamp,value,labels,available,error
```

## 6. SLO 判定

判定结果为四态：

- `PASS`：业务 SLO、资源 SLO、ZLM 残留检查和关键监控完整性全部通过；
- `WARNING`：业务通过，但资源超过预警线、接近阈值或存在非关键采集缺失；
- `FAIL`：业务成功率、媒体确认、停止清理、延迟、资源硬阈值或残留资源任一失败；
- `INCONCLUSIVE`：关键 Prometheus/ZLM 数据不可用，无法判断系统容量。

判定顺序：先计算业务指标，再计算资源摘要和残留资源，最后执行监控完整性检查。缺失值不能参与平均值、P95 或最大值计算。

单次测试只报告当前负载是否满足 SLO。只有连续多个负载档位均达到规定持续时间，并将最后一个 `PASS` 档位与第一个 `FAIL` 档位对比，才能填写稳定容量。

## 7. 测试与验收

单元测试覆盖：

- 配置解析、默认查询和 SLO 范围校验；
- Prometheus 响应解析、矩阵/向量和错误/空结果；
- ZLM 响应解析、采样失败和残留流计算；
- 业务指标计算、资源 avg/P95/max、四态判定；
- JSON、资源 CSV 和 Markdown 中的脱敏及缺失数据表达。

真实环境集成测试记录在 `docs`：WVP、ZLM、Prometheus 及 exporter 必须已启动；测试结束后核对业务日志、ZLM 流注销、报告 verdict 和资源曲线。Prometheus 或 ZLM 不可用时，应验证报告为 `INCONCLUSIVE`，不能误报 `PASS`。

## 8. 安全与兼容性

- 不记录密码、token、secret、Authorization、完整带查询参数的 URL；
- 现有未开启 monitoring 的配置和报告路径保持可运行；
- 采集器超时、连接失败不能阻塞点播调度，使用独立 context 和有界超时；
- 所有资源采样必须有上限，不能因 Prometheus 高基数结果导致报告无限增长。
