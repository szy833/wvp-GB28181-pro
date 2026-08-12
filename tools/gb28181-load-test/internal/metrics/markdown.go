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
	fmt.Fprintf(f, "## 业务指标\n\n| 指标 | 值 |\n| --- | ---: |\n| REGISTER 成功率 | %.4f |\n| Catalog 成功率 | %.4f |\n| 点播成功率 | %.4f |\n| 媒体确认率 | %.4f |\n| 停止成功率 | %.4f |\n| 控制接口 P95 | %.3fs |\n| 控制接口 P99 | %.3fs |\n\n", report.Business.RegisterSuccessRate, report.Business.CatalogSuccessRate, report.Business.PlaySuccessRate, report.Business.MediaSuccessRate, report.Business.StopSuccessRate, report.Business.ControlLatencyP95, report.Business.ControlLatencyP99)
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
