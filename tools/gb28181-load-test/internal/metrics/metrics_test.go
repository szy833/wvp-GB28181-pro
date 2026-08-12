package metrics

import "testing"

func TestSnapshotIncludesLatencyQuantiles(t *testing.T) {
	r := NewRegistry()
	r.Observe("latency", .02)
	r.Observe("latency", .2)
	r.Observe("latency", 2)
	s := r.Snapshot()
	if s["latency_p50"] == 0 || s["latency_p95"] == 0 || s["latency_p99"] == 0 {
		t.Fatalf("missing quantiles: %+v", s)
	}
}

func TestRegistrySnapshot(t *testing.T) {
	r := NewRegistry()
	r.Counter("requests_total", 2)
	r.Gauge("devices", 3)
	r.Observe("latency", 0.25)
	s := r.Snapshot()
	if s["requests_total"] != 2 || s["devices"] != 3 || s["latency_count"] != 1 || s["latency_sum"] != 0.25 {
		t.Fatal(s)
	}
}
