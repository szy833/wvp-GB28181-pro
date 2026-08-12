package metrics

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/shizy/gb28181-load-test/internal/monitoring"
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

func TestWriteJSONAndMarkdownContainSafeHybridReportFields(t *testing.T) {
	dir := t.TempDir()
	r := Report{
		RunID: "run-1", DeviceCount: 1, StartedAt: time.Now().Add(-time.Second), FinishedAt: time.Now(),
		Summary:         Verdict{Name: "PASS", MonitoringComplete: true},
		Business:        BusinessSummary{PlaySuccessRate: 1},
		Resources:       map[string]ResourceSummary{"wvpCpu": {Available: true, Samples: 1, Max: 10}},
		ZLM:             ZLMReport{Available: true, ResidualAvailable: true, PeakStreams: 1},
		ResourceSamples: []monitoring.ResourceSample{{Source: "zlm", Metric: "final_streams", Available: true, Value: 0}},
	}
	jsonPath := filepath.Join(dir, "run.json")
	if err := WriteJSON(jsonPath, r); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(jsonPath)
	if err != nil {
		t.Fatal(err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"summary", "business", "resources", "zlm", "resourceSamples"} {
		if _, ok := decoded[key]; !ok {
			t.Fatalf("JSON missing %q: %s", key, data)
		}
	}
	markdownPath := filepath.Join(dir, "run.md")
	if err := WriteMarkdown(markdownPath, r); err != nil {
		t.Fatal(err)
	}
	markdown, err := os.ReadFile(markdownPath)
	if err != nil {
		t.Fatal(err)
	}
	text := string(markdown)
	for _, expected := range []string{"判定：**PASS**", "## 业务指标", "## 资源指标", "## ZLM"} {
		if !strings.Contains(text, expected) {
			t.Fatalf("Markdown missing %q: %s", expected, text)
		}
	}
	for _, secret := range []string{"secret", "token", "password"} {
		if strings.Contains(strings.ToLower(text), secret) || strings.Contains(strings.ToLower(string(data)), secret) {
			t.Fatalf("report leaked sensitive field %q", secret)
		}
	}
}

func TestWriteResourceCSVPreservesUnavailableSamples(t *testing.T) {
	path := filepath.Join(t.TempDir(), "resources.csv")
	samples := []monitoring.ResourceSample{{Source: "prometheus", Metric: "wvpCpu", Timestamp: time.Now(), Available: false, Error: "query timeout", Labels: map[string]string{"b": "2", "a": "1"}}}
	if err := WriteResourceCSV(path, samples); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if !strings.Contains(text, "false") || !strings.Contains(text, "query timeout") || !strings.Contains(text, "a=1,b=2") {
		t.Fatalf("unavailable sample was not preserved: %s", text)
	}
	if strings.Contains(text, ",0.000000,") {
		t.Fatalf("unavailable value must remain empty: %s", text)
	}
}

func TestSummarizeResourcesKeepsUnavailableMetric(t *testing.T) {
	summary := SummarizeResources([]monitoring.ResourceSample{{Metric: "jvmHeap", Available: false, Error: "no samples"}})
	value, ok := summary["jvmHeap"]
	if !ok || value.Available || value.Samples != 0 || value.Error != "no samples" {
		t.Fatalf("unavailable metric was dropped: %+v", summary)
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
	if len(rows[0]) != 27 || len(rows[1]) != len(rows[0]) || len(rows[2]) != len(rows[0]) {
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
