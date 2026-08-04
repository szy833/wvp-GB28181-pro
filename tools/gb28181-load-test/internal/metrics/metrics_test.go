package metrics

import "testing"

func TestRegistrySnapshot(t *testing.T) { r := NewRegistry(); r.Counter("requests_total", 2); r.Gauge("devices", 3); r.Observe("latency", 0.25); s := r.Snapshot(); if s["requests_total"] != 2 || s["devices"] != 3 || s["latency_count"] != 1 || s["latency_sum"] != 0.25 { t.Fatal(s) } }
