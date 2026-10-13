package main

import (
	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"github.com/shizy/gb28181-load-test/internal/monitoring"
	"testing"
)

func TestTimedOutCleanupCannotPassEvenWhenLastSnapshotIsZero(t *testing.T) {
	r := metrics.Report{ResourceSamples: []monitoring.ResourceSample{{Source: "zlm", Metric: "final_streams", Available: true, ZLM: &monitoring.ZLMSnapshot{Available: true, ListenersAvailable: true, SessionsAvailable: true, ScopeFiltered: true, CleanupTimedOut: true}}}}
	finalizeMonitoring(&r, config.SLOConfig{}, true)
	if r.Summary.Name != "INCONCLUSIVE" {
		t.Fatalf("unconverged cleanup verdict=%s", r.Summary.Name)
	}
}

func TestRTPOrByeFailureCannotBeMaskedByCleanResidualSnapshot(t *testing.T) {
	r := metrics.Report{Devices: []metrics.DeviceResult{{RTPErrors: 1, DeviceByeErrors: 1}}, ResourceSamples: []monitoring.ResourceSample{{Source: "zlm", Metric: "final_streams", Available: true, ZLM: &monitoring.ZLMSnapshot{Available: true, ListenersAvailable: true, SessionsAvailable: true, ScopeFiltered: true}}}}
	finalizeMonitoring(&r, config.SLOConfig{}, true)
	if r.Summary.Name != "FAIL" {
		t.Fatalf("media/BYE errors were ignored: %s", r.Summary.Name)
	}
}
