package metrics

import "testing"

func TestRegistrySnapshot(t *testing.T) { r := NewRegistry(); r.Counter("requests_total", 2); r.Gauge("devices", 3); s := r.Snapshot(); if s["requests_total"] != 2 || s["devices"] != 3 { t.Fatal(s) } }
