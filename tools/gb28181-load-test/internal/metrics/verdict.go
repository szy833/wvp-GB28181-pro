package metrics

import (
	"encoding/json"
	"fmt"
)

type BusinessCheck struct {
	Attempted int    `json:"attempted"`
	Succeeded int    `json:"succeeded"`
	Available bool   `json:"available"`
	Evidence  string `json:"evidence"`
}

type BusinessSummary struct {
	Checks              map[string]BusinessCheck `json:"checks,omitempty"`
	RegisterSuccessRate float64                  `json:"registerSuccessRate"`
	CatalogSuccessRate  float64                  `json:"catalogSuccessRate"`
	PlaySuccessRate     float64                  `json:"playSuccessRate"`
	MediaSuccessRate    float64                  `json:"mediaSuccessRate"`
	StopSuccessRate     float64                  `json:"stopSuccessRate"`
	ControlLatencyP95   float64                  `json:"controlLatencyP95"`
	ControlLatencyP99   float64                  `json:"controlLatencyP99"`
}

func (b BusinessSummary) MarshalJSON() ([]byte, error) {
	type plain BusinessSummary
	data, err := json.Marshal(plain(b))
	if err != nil {
		return nil, err
	}
	if b.Checks == nil {
		return data, nil
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return nil, err
	}
	for _, name := range []string{"register", "catalog", "play", "media", "stop"} {
		if !b.Checks[name].Available {
			fields[name+"SuccessRate"] = json.RawMessage("null")
		}
	}
	return json.Marshal(fields)
}

func (b BusinessSummary) FormatRate(name string, value float64) string {
	if b.Checks != nil && !b.Checks[name].Available {
		return "N/A"
	}
	return fmt.Sprintf("%.4f", value)
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
	RequireCleanupChecks     bool
	SessionsAvailable        bool
	ListenersAvailable       bool
	ResidualListeners        int
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
		key := map[string]string{"REGISTER success rate": "register", "Catalog success rate": "catalog", "play success rate": "play", "media success rate": "media", "stop success rate": "stop"}[check.name]
		if b.Checks != nil && !b.Checks[key].Available {
			if check.limit > 0 {
				result.MonitoringComplete = false
				result.FailureReasons = append(result.FailureReasons, check.name+" not tested / N/A")
			}
			continue
		}
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
	if input.RequireCleanupChecks {
		if !input.SessionsAvailable || !input.ListenersAvailable {
			result.MonitoringComplete = false
			result.FailureReasons = append(result.FailureReasons, "scoped WVP session / ZLM listener cleanup check unavailable")
		}
		if input.ListenersAvailable && input.ResidualListeners > 0 {
			result.BusinessSLOPassed = false
			result.FailureReasons = append(result.FailureReasons, fmt.Sprintf("residual RTP listeners %d > 0", input.ResidualListeners))
		}
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
