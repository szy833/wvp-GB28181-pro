package metrics

import (
	"bytes"
	"encoding/csv"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestWriteReports(t *testing.T) {
	p := filepath.Join(t.TempDir(), "r.json")
	r := Report{RunID: "x"}
	if err := WriteJSON(p, r); err != nil {
		t.Fatal(err)
	}
	if err := WriteCSV(filepath.Join(t.TempDir(), "r.csv"), r); err != nil {
		t.Fatal(err)
	}
}

func TestWriteDetailCSVIncludesDeviceAndPlayRows(t *testing.T) {
	p := filepath.Join(t.TempDir(), "details.csv")
	r := Report{Devices: []DeviceResult{{DeviceID: "d1", ChannelID: "c1", RegisterSuccess: 1, CatalogSuccess: 1}}, Plays: []PlayResult{{ID: "p1", DeviceID: "d1", ChannelID: "c1", StartSuccess: true, StopSuccess: true}}}
	if err := WriteDetailCSV(p, r); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if !strings.Contains(text, "device") || !strings.Contains(text, "play") || !strings.Contains(text, "d1") || !strings.Contains(text, "p1") {
		t.Fatalf("missing detail rows: %s", text)
	}
	rows, err := csv.NewReader(bytes.NewReader(data)).ReadAll()
	if err != nil || len(rows) != 3 {
		t.Fatalf("unexpected rows: %v", rows)
	}
	if len(rows[0]) != 22 || len(rows[1]) != len(rows[0]) || len(rows[2]) != len(rows[0]) {
		t.Fatalf("unexpected detail columns: header=%d device=%d play=%d", len(rows[0]), len(rows[1]), len(rows[2]))
	}
}

func TestPrintSummaryShowsStagesAndQuantiles(t *testing.T) {
	r := Report{RunID: "run", Stages: []Stage{{Name: "catalog", Devices: 2, Success: 2, Failed: 0, StartedAt: time.Now().Add(-time.Second), FinishedAt: time.Now()}}, Metrics: map[string]float64{"wvp_control_latency_seconds_count": 2, "wvp_control_latency_seconds_p50": .1, "wvp_control_latency_seconds_p95": .2}}
	var out bytes.Buffer
	PrintSummary(&out, r, "details.csv")
	text := out.String()
	for _, expected := range []string{"catalog", "100.0%", "P50", "P95", "details.csv"} {
		if !strings.Contains(text, expected) {
			t.Fatalf("summary missing %q: %s", expected, text)
		}
	}
}
