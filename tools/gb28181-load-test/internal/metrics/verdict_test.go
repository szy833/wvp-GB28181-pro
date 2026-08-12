package metrics

import "testing"

func TestEvaluateSLOPass(t *testing.T) {
	result := EvaluateSLO(SLOInput{
		Business:     BusinessSummary{RegisterSuccessRate: 1, CatalogSuccessRate: 1, PlaySuccessRate: 1, MediaSuccessRate: 1, StopSuccessRate: 1, ControlLatencyP95: 1, ControlLatencyP99: 2},
		Resources:    map[string]ResourceSummary{"wvpCpu": {Available: true, Samples: 1, Max: 70}},
		ZLMAvailable: true, ResidualSessions: 0, ResidualStreams: 0,
		ResidualStreamsAvailable: true,
		MonitoringComplete:       true,
		SLO:                      SLOThresholds{RegisterSuccessRate: .999, CatalogSuccessRate: .995, PlaySuccessRate: .99, MediaSuccessRate: .99, StopSuccessRate: .99, ControlLatencyP95Seconds: 5, ControlLatencyP99Seconds: 10, MaxResidualSessions: 0, MaxResidualStreams: 0, MaxCPUPercent: 85},
	})
	if result.Name != "PASS" || !result.BusinessSLOPassed || !result.ResourceSLOPassed || !result.MonitoringComplete {
		t.Fatalf("unexpected verdict: %+v", result)
	}
}

func TestEvaluateSLOFailAndMissing(t *testing.T) {
	thresholds := SLOThresholds{RegisterSuccessRate: .999, CatalogSuccessRate: .995, PlaySuccessRate: .99, MediaSuccessRate: .99, StopSuccessRate: .99, ControlLatencyP95Seconds: 5, ControlLatencyP99Seconds: 10, MaxResidualSessions: 0, MaxResidualStreams: 0, MaxCPUPercent: 85}
	failed := EvaluateSLO(SLOInput{Business: BusinessSummary{RegisterSuccessRate: .9, CatalogSuccessRate: 1, PlaySuccessRate: 1, MediaSuccessRate: 1, StopSuccessRate: 1, ControlLatencyP95: 1, ControlLatencyP99: 2}, Resources: map[string]ResourceSummary{"wvpCpu": {Available: true, Samples: 1, Max: 90}}, ZLMAvailable: true, ResidualSessions: 1, ResidualStreams: 0, ResidualStreamsAvailable: true, MonitoringComplete: true, SLO: thresholds})
	if failed.Name != "FAIL" || len(failed.FailureReasons) < 2 {
		t.Fatalf("unexpected failure: %+v", failed)
	}
	missing := EvaluateSLO(SLOInput{Business: BusinessSummary{RegisterSuccessRate: 1, CatalogSuccessRate: 1, PlaySuccessRate: 1, MediaSuccessRate: 1, StopSuccessRate: 1, ControlLatencyP95: 1, ControlLatencyP99: 2}, Resources: map[string]ResourceSummary{"wvpCpu": {Available: false}}, ZLMAvailable: false, MonitoringComplete: false, SLO: thresholds})
	if missing.Name != "INCONCLUSIVE" || missing.BusinessSLOPassed == false {
		t.Fatalf("unexpected missing verdict: %+v", missing)
	}
}

func TestEvaluateSLOWarningNearResourceLimit(t *testing.T) {
	thresholds := SLOThresholds{RegisterSuccessRate: .999, CatalogSuccessRate: .995, PlaySuccessRate: .99, MediaSuccessRate: .99, StopSuccessRate: .99, ControlLatencyP95Seconds: 5, ControlLatencyP99Seconds: 10, MaxCPUPercent: 85}
	result := EvaluateSLO(SLOInput{Business: BusinessSummary{RegisterSuccessRate: 1, CatalogSuccessRate: 1, PlaySuccessRate: 1, MediaSuccessRate: 1, StopSuccessRate: 1, ControlLatencyP95: 1, ControlLatencyP99: 2}, Resources: map[string]ResourceSummary{"wvpCpu": {Available: true, Samples: 1, Max: 80}}, ZLMAvailable: true, ResidualStreamsAvailable: true, MonitoringComplete: true, SLO: thresholds})
	if result.Name != "WARNING" {
		t.Fatalf("unexpected warning: %+v", result)
	}
}

func TestEvaluateSLONeverTreatsMissingFinalZLMSnapshotAsZero(t *testing.T) {
	result := EvaluateSLO(SLOInput{
		Business:     BusinessSummary{RegisterSuccessRate: 1, CatalogSuccessRate: 1, PlaySuccessRate: 1, MediaSuccessRate: 1, StopSuccessRate: 1},
		ZLMAvailable: true, ResidualStreams: 0, MonitoringComplete: true,
		SLO: SLOThresholds{MaxResidualStreams: 0},
	})
	if result.Name != "INCONCLUSIVE" || result.MonitoringComplete {
		t.Fatalf("missing final snapshot must be inconclusive: %+v", result)
	}
}

func TestEvaluateSLOChecksConfiguredResourceThresholds(t *testing.T) {
	result := EvaluateSLO(SLOInput{
		Business: BusinessSummary{RegisterSuccessRate: 1, CatalogSuccessRate: 1, PlaySuccessRate: 1, MediaSuccessRate: 1, StopSuccessRate: 1},
		Resources: map[string]ResourceSummary{
			"jvmHeap":     {Available: true, Samples: 1, Max: 91},
			"redisCpu":    {Available: true, Samples: 1, Max: 81},
			"hostNetwork": {Available: true, Samples: 1, Max: 86},
		}, ZLMAvailable: true, ResidualStreamsAvailable: true, MonitoringComplete: true,
		SLO: SLOThresholds{MaxJVMHeapPercent: 85, MaxRedisCPUPercent: 80, MaxNetworkPercent: 85},
	})
	if result.Name != "FAIL" || len(result.FailureReasons) != 3 {
		t.Fatalf("resource thresholds were not enforced: %+v", result)
	}
}

func TestEvaluateSLOMarksMissingRequiredResourceInconclusive(t *testing.T) {
	result := EvaluateSLO(SLOInput{
		Business:     BusinessSummary{RegisterSuccessRate: 1, CatalogSuccessRate: 1, PlaySuccessRate: 1, MediaSuccessRate: 1, StopSuccessRate: 1},
		Resources:    map[string]ResourceSummary{"wvpCpu": {Available: true, Samples: 1, Max: 10}},
		ZLMAvailable: true, ResidualStreamsAvailable: true, MonitoringComplete: true,
		SLO: SLOThresholds{MaxCPUPercent: 85, MaxJVMHeapPercent: 85},
	})
	if result.Name != "INCONCLUSIVE" || result.MonitoringComplete {
		t.Fatalf("missing required resource must be inconclusive: %+v", result)
	}
}
