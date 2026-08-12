package metrics

import "fmt"

type BusinessSummary struct {
	RegisterSuccessRate float64 `json:"registerSuccessRate"`
	CatalogSuccessRate  float64 `json:"catalogSuccessRate"`
	PlaySuccessRate     float64 `json:"playSuccessRate"`
	MediaSuccessRate    float64 `json:"mediaSuccessRate"`
	StopSuccessRate     float64 `json:"stopSuccessRate"`
	ControlLatencyP95   float64 `json:"controlLatencyP95"`
	ControlLatencyP99   float64 `json:"controlLatencyP99"`
}

type ResourceSummary struct {
	Avg       float64 `json:"avg"`
	P95       float64 `json:"p95"`
	Max       float64 `json:"max"`
	Available bool    `json:"available"`
	Samples   int     `json:"samples"`
	Error     string  `json:"error,omitempty"`
}

type SLOThresholds struct {
	RegisterSuccessRate      float64
	CatalogSuccessRate       float64
	PlaySuccessRate          float64
	MediaSuccessRate         float64
	StopSuccessRate          float64
	ControlLatencyP95Seconds float64
	ControlLatencyP99Seconds float64
	MaxResidualSessions      int
	MaxResidualStreams       int
	MaxCPUPercent            float64
	MaxJVMHeapPercent        float64
	MaxRedisCPUPercent       float64
	MaxNetworkPercent        float64
}

type SLOInput struct {
	Business                 BusinessSummary
	Resources                map[string]ResourceSummary
	ZLMAvailable             bool
	ResidualSessions         int
	ResidualStreams          int
	ResidualStreamsAvailable bool
	MonitoringComplete       bool
	SLO                      SLOThresholds
}

type Verdict struct {
	Name               string   `json:"verdict"`
	BusinessSLOPassed  bool     `json:"businessSloPassed"`
	ResourceSLOPassed  bool     `json:"resourceSloPassed"`
	MonitoringComplete bool     `json:"monitoringComplete"`
	StableCapacity     bool     `json:"stableCapacity"`
	FailureReasons     []string `json:"failureReasons"`
}

func EvaluateSLO(input SLOInput) Verdict {
	result := Verdict{Name: "PASS", BusinessSLOPassed: true, ResourceSLOPassed: true, MonitoringComplete: input.MonitoringComplete, FailureReasons: []string{}}
	b := input.Business
	checks := []struct {
		name         string
		value, limit float64
	}{
		{"REGISTER success rate", b.RegisterSuccessRate, input.SLO.RegisterSuccessRate},
		{"Catalog success rate", b.CatalogSuccessRate, input.SLO.CatalogSuccessRate},
		{"play success rate", b.PlaySuccessRate, input.SLO.PlaySuccessRate},
		{"media success rate", b.MediaSuccessRate, input.SLO.MediaSuccessRate},
		{"stop success rate", b.StopSuccessRate, input.SLO.StopSuccessRate},
	}
	for _, check := range checks {
		if check.limit > 0 && check.value < check.limit {
			result.BusinessSLOPassed = false
			result.FailureReasons = append(result.FailureReasons, fmt.Sprintf("%s %.4f < %.4f", check.name, check.value, check.limit))
		}
	}
	if input.SLO.ControlLatencyP95Seconds > 0 && b.ControlLatencyP95 > input.SLO.ControlLatencyP95Seconds {
		result.BusinessSLOPassed = false
		result.FailureReasons = append(result.FailureReasons, fmt.Sprintf("control P95 %.3fs > %.3fs", b.ControlLatencyP95, input.SLO.ControlLatencyP95Seconds))
	}
	if input.SLO.ControlLatencyP99Seconds > 0 && b.ControlLatencyP99 > input.SLO.ControlLatencyP99Seconds {
		result.BusinessSLOPassed = false
		result.FailureReasons = append(result.FailureReasons, fmt.Sprintf("control P99 %.3fs > %.3fs", b.ControlLatencyP99, input.SLO.ControlLatencyP99Seconds))
	}
	if input.ResidualSessions > input.SLO.MaxResidualSessions {
		result.BusinessSLOPassed = false
		result.FailureReasons = append(result.FailureReasons, fmt.Sprintf("residual sessions %d > %d", input.ResidualSessions, input.SLO.MaxResidualSessions))
	}
	if input.ResidualStreams > input.SLO.MaxResidualStreams {
		result.BusinessSLOPassed = false
		result.FailureReasons = append(result.FailureReasons, fmt.Sprintf("residual streams %d > %d", input.ResidualStreams, input.SLO.MaxResidualStreams))
	}
	if !input.ResidualStreamsAvailable && input.ZLMAvailable {
		result.MonitoringComplete = false
		result.FailureReasons = append(result.FailureReasons, "final ZLM stream snapshot unavailable")
	}
	for name, resource := range input.Resources {
		if !resource.Available || resource.Samples == 0 {
			result.MonitoringComplete = false
			continue
		}
		limit := resourceLimit(name, input.SLO)
		if limit > 0 && resource.Max > limit {
			result.ResourceSLOPassed = false
			result.FailureReasons = append(result.FailureReasons, fmt.Sprintf("%s max %.2f > %.2f", name, resource.Max, limit))
		}
	}
	for _, required := range []struct {
		name  string
		limit float64
	}{
		{"wvpCpu", input.SLO.MaxCPUPercent},
		{"jvmHeap", input.SLO.MaxJVMHeapPercent},
		{"redisCpu", input.SLO.MaxRedisCPUPercent},
		{"hostNetwork", input.SLO.MaxNetworkPercent},
	} {
		if required.limit <= 0 {
			continue
		}
		resource, ok := input.Resources[required.name]
		if !ok || !resource.Available || resource.Samples == 0 {
			result.MonitoringComplete = false
			result.FailureReasons = append(result.FailureReasons, required.name+" resource sample unavailable")
		}
	}
	if !input.ZLMAvailable && input.MonitoringComplete {
		result.MonitoringComplete = false
	}
	if !result.BusinessSLOPassed || !result.ResourceSLOPassed {
		result.Name = "FAIL"
	} else if !result.MonitoringComplete {
		result.Name = "INCONCLUSIVE"
	} else if warningResource(input) {
		result.Name = "WARNING"
	}
	return result
}

func warningResource(input SLOInput) bool {
	for name, resource := range input.Resources {
		limit := resourceLimit(name, input.SLO)
		if limit > 0 && resource.Available && resource.Max >= limit*.9 {
			return true
		}
	}
	return false
}

func resourceLimit(name string, slo SLOThresholds) float64 {
	switch name {
	case "wvpCpu":
		return slo.MaxCPUPercent
	case "jvmHeap":
		return slo.MaxJVMHeapPercent
	case "redisCpu":
		return slo.MaxRedisCPUPercent
	case "hostNetwork":
		return slo.MaxNetworkPercent
	default:
		return 0
	}
}
