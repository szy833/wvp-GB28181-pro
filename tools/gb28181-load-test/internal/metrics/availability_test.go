package metrics

import (
	"encoding/json"
	"testing"
)

func TestMissingBusinessChecksSerializeAsNullAndAreInconclusive(t *testing.T) {
	b := BusinessSummary{Checks: map[string]BusinessCheck{"register": {Attempted: 1, Succeeded: 1, Available: true}}, RegisterSuccessRate: 1}
	data, err := json.Marshal(b)
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err := json.Unmarshal(data, &fields); err != nil {
		t.Fatal(err)
	}
	if fields["playSuccessRate"] != nil || fields["registerSuccessRate"] != float64(1) {
		t.Fatalf("false success or failure: %s", data)
	}
	v := EvaluateSLO(SLOInput{Business: b, ZLMAvailable: true, ResidualStreamsAvailable: true, MonitoringComplete: true, SLO: SLOThresholds{PlaySuccessRate: .99}})
	if v.Name != "INCONCLUSIVE" || !v.BusinessSLOPassed {
		t.Fatalf("unexecuted check treated as pass/fail: %+v", v)
	}
}
