package metrics

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

func WriteMarkdown(path string, report Report) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	fmt.Fprintf(f, "# GB28181 压测报告\n\n")
	fmt.Fprintf(f, "- 测试编号：`%s`\n- 设备数：`%d`\n- 开始：`%s`\n- 结束：`%s`\n- 判定：**%s**\n\n", report.RunID, report.DeviceCount, report.StartedAt.Format("2006-01-02 15:04:05"), report.FinishedAt.Format("2006-01-02 15:04:05"), report.Summary.Name)
	b := report.Business
	fmt.Fprintf(f, "## 业务指标\n\n| 指标 | 值 |\n| --- | ---: |\n| REGISTER 设备成功率 | %s |\n| Catalog 响应发送率 | %s |\n| 点播成功率 | %s |\n| WVP 媒体确认率 | %s |\n| 停止接口成功率 | %s |\n| 控制接口 P95 | %.3fs |\n| 控制接口 P99 | %.3fs |\n\n", b.FormatRate("register", b.RegisterSuccessRate), b.FormatRate("catalog", b.CatalogSuccessRate), b.FormatRate("play", b.PlaySuccessRate), b.FormatRate("media", b.MediaSuccessRate), b.FormatRate("stop", b.StopSuccessRate), b.ControlLatencyP95, b.ControlLatencyP99)
	fmt.Fprintln(f, "N/A 表示本次未执行或没有可确认的结果；本地发送器启动不代表 ZLM 已收流。")
	fmt.Fprintln(f, "\n| 检查项 | 尝试 | 成功 | 可用 | 证据口径 |\n| --- | ---: | ---: | --- | --- |")
	for _, name := range []string{"register", "catalog", "play", "media", "stop"} {
		if check, ok := b.Checks[name]; ok {
			fmt.Fprintf(f, "| %s | %d | %d | %t | %s |\n", name, check.Attempted, check.Succeeded, check.Available, check.Evidence)
		}
	}
	fmt.Fprintf(f, "## 资源指标\n\n| 指标 | 平均 | P95 | 最大 | 样本 | 可用 | 错误 |\n| --- | ---: | ---: | ---: | ---: | --- | --- |\n")
	resourceNames := make([]string, 0, len(report.Resources))
	for name := range report.Resources {
		resourceNames = append(resourceNames, name)
	}
	sort.Strings(resourceNames)
	for _, name := range resourceNames {
		resource := report.Resources[name]
		errorText := resource.Error
		if errorText == "" {
			errorText = "-"
		}
		fmt.Fprintf(f, "| %s | %.2f | %.2f | %.2f | %d | %t | %s |\n", name, resource.Avg, resource.P95, resource.Max, resource.Samples, resource.Available, errorText)
	}
	fmt.Fprintf(f, "\n## ZLM\n\n- 可用：`%t`\n- 峰值流数：`%d`\n- 残留流数：`%d`\n- 残留检查可用：`%t`\n- API 错误：`%d`\n\n", report.ZLM.Available, report.ZLM.PeakStreams, report.ZLM.ResidualStreams, report.ZLM.ResidualAvailable, report.ZLM.APIErrorCount)
	fmt.Fprintf(f, "- 仅统计本次测试资源：`%t`\n- 残留 RTP listener：`%d`，检查可用：`%t`\n- 残留 WVP 可见会话：`%d`，检查可用：`%t`\n- 清理等待：`%.3fs`，超时：`%t`\n\n", report.ZLM.ScopeFiltered, report.ZLM.ResidualListeners, report.ZLM.ListenersAvailable, report.ZLM.ResidualSessions, report.ZLM.SessionsAvailable, report.ZLM.CleanupSeconds, report.ZLM.CleanupTimedOut)
	for _, err := range report.ZLM.CleanupErrors {
		fmt.Fprintf(f, "- 清理检查错误：%s\n", err)
	}
	fmt.Fprintln(f, "\n## 发送与 BYE 明细\n\n| 设备 | RTP 包数 | 发送字节 | 发送错误 | BYE 已发送 | BYE 成功 | BYE 错误 | BYE 超时 |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
	for _, d := range report.Devices {
		fmt.Fprintf(f, "| %s | %d | %d | %d | %d | %d | %d | %d |\n", d.DeviceID, d.RTPPackets, d.RTPBytes, d.RTPErrors, d.DeviceByeSent, d.DeviceByeSuccess, d.DeviceByeErrors, d.DeviceByeTimeouts)
	}
	fmt.Fprintln(f, "\n## 发送终止分类\n\n本地取消单独计数，不算发送错误；对端关闭仍计入发送错误，不因同时停止而忽略。每发送器保留最近 8 条，每设备报告保留最近 32 条。\n\n| 设备 | 本地取消 | 对端关闭 | 截断明细 |\n| --- | ---: | ---: | ---: |")
	for _, d := range report.Devices {
		fmt.Fprintf(f, "| %s | %d | %d | %d |\n", d.DeviceID, d.RTPLocalCancellations, d.RTPRemoteClosures, d.RTPDiagnosticsDropped)
	}
	fmt.Fprintln(f, "\n| 设备 | Call-ID | 时间 | 类别 | 操作 | 错误码 | 已取消 | 采样状态 |\n| --- | --- | --- | --- | --- | --- | --- | --- |")
	for _, d := range report.Devices {
		for _, detail := range d.RTPDiagnostics {
			fmt.Fprintf(f, "| %s | %s | %s | %s | %s | %s | %t | %s |\n", d.DeviceID, detail.CallID, detail.At.Format("2006-01-02T15:04:05.000Z07:00"), detail.Kind, detail.Operation, detail.Code, detail.Cancelled, detail.DeviceState)
		}
	}
	fmt.Fprintf(f, "## 判定原因\n\n")
	if len(report.Summary.FailureReasons) == 0 {
		fmt.Fprintln(f, "- 无")
	} else {
		for _, reason := range report.Summary.FailureReasons {
			fmt.Fprintf(f, "- %s\n", reason)
		}
	}
	if !report.Summary.MonitoringComplete {
		fmt.Fprintln(f, "\n> 监控数据不完整，本次结果不能用于确认容量上限。")
	}
	return nil
}
