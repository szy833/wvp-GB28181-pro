package metrics

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

type Report struct { RunID string `json:"runId"`; StartedAt time.Time `json:"startedAt"`; FinishedAt time.Time `json:"finishedAt"`; DeviceCount int `json:"deviceCount"`; Stages []Stage `json:"stages"`; Errors map[string]int `json:"errors"`; Metrics map[string]float64 `json:"metrics"` }
type Stage struct { Name string `json:"name"`; StartedAt time.Time `json:"startedAt"`; FinishedAt time.Time `json:"finishedAt"`; Devices int `json:"devices"`; Success int `json:"success"`; Failed int `json:"failed"` }
func WriteJSON(path string, report Report) error { if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil { return err }; b, err := json.MarshalIndent(report, "", "  "); if err != nil { return err }; return os.WriteFile(path, b, 0644) }
func WriteCSV(path string, report Report) error { if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil { return err }; f, err := os.Create(path); if err != nil { return err }; defer f.Close(); w := csv.NewWriter(f); if err := w.Write([]string{"stage", "devices", "success", "failed", "startedAt", "finishedAt"}); err != nil { return err }; for _, s := range report.Stages { if err := w.Write([]string{s.Name, fmt.Sprint(s.Devices), fmt.Sprint(s.Success), fmt.Sprint(s.Failed), s.StartedAt.Format(time.RFC3339), s.FinishedAt.Format(time.RFC3339)}); err != nil { return err } }; w.Flush(); return w.Error() }
