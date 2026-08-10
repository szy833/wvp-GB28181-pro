# 压测完整结果输出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 GB28181 压测工具增加完整阶段、设备和点播结果输出，并提供控制台、JSON、CSV 三种可见形式。

**Architecture:** 在 `metrics` 包增加线程安全的结果模型和报告写出器；`runner` 负责收集设备和点播记录；`device` 通过轻量指标接口记录 SIP 关键事件；主程序输出摘要并生成明细 CSV。

**Tech Stack:** Go 1.22、标准库 JSON/CSV/HTTP、现有 Prometheus 文本指标。

## Global Constraints

- 保留现有 `report.json` 和 `report.csv` 的含义与路径。
- 明细 CSV 默认由汇总 CSV 路径派生，不强制修改旧配置。
- 报告不得写入 SIP 密码、控制 token 或敏感认证字段。
- 不修改用户已有未跟踪文件和 `t.cap`。

### Task 1: 扩展报告模型和写出器

**Files:**
- Modify: `tools/gb28181-load-test/internal/metrics/report.go`
- Test: `tools/gb28181-load-test/internal/metrics/report_test.go`

- [ ] 先增加 `DeviceResult`、`PlayResult`、扩展 `Stage` 和 `Report` 的失败测试。
- [ ] 实现 JSON 嵌套明细、阶段耗时/成功率以及派生明细 CSV。
- [ ] 运行 metrics 包测试。

### Task 2: 采集设备和点播结果

**Files:**
- Modify: `tools/gb28181-load-test/internal/runner/runner.go`
- Modify: `tools/gb28181-load-test/internal/runner/scheduler.go`
- Test: `tools/gb28181-load-test/internal/runner/runner_test.go`

- [ ] 先测试一次点播会产生一条成功/失败明细。
- [ ] 增加线程安全收集器，记录设备创建/运行和点播开始/停止结果。
- [ ] 将收集结果放入报告并保持现有 Prometheus 计数器。

### Task 3: 记录 SIP 设备关键事件

**Files:**
- Modify: `tools/gb28181-load-test/internal/device/device.go`
- Modify: `tools/gb28181-load-test/cmd/gb28181-load-test/main.go`
- Test: `tools/gb28181-load-test/internal/device/device_test.go`

- [ ] 先测试 REGISTER 挑战、Catalog 响应、INVITE 200 和媒体启动事件计数。
- [ ] 在 `DeviceHooks` 增加无循环依赖的计数器接口，并在关键处理点计数。
- [ ] 主程序把运行注册表注入每台设备。

### Task 4: 控制台摘要与配置兼容

**Files:**
- Modify: `tools/gb28181-load-test/internal/metrics/report.go`
- Modify: `tools/gb28181-load-test/cmd/gb28181-load-test/main.go`
- Modify: `tools/gb28181-load-test/internal/config/config.go`
- Modify: `tools/gb28181-load-test/README.md`
- Test: `tools/gb28181-load-test/internal/metrics/report_test.go`

- [ ] 先测试摘要包含阶段、成功率、P95 和明细文件路径。
- [ ] 增加可选 `report.detailCsv`，为空时自动派生。
- [ ] 主程序在写出 JSON/CSV 后打印完整摘要。
- [ ] 更新使用文档和验收命令。

### Task 5: 验证和提交

- [ ] 运行受影响包的单元测试。
- [ ] 运行 `go build` 和本地集成测试；记录报告路径和已知环境遗留错误。
- [ ] 检查 `git status --short`，只提交本次源码/文档变更。
- [ ] 使用中文 Git 提交信息。
