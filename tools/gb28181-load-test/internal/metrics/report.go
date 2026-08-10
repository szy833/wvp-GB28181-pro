package metrics

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type Report struct {
	RunID       string             `json:"runId"`
	StartedAt   time.Time          `json:"startedAt"`
	FinishedAt  time.Time          `json:"finishedAt"`
	DeviceCount int                `json:"deviceCount"`
	Config      ConfigSummary      `json:"config"`
	Stages      []Stage            `json:"stages"`
	Devices     []DeviceResult     `json:"devices"`
	Plays       []PlayResult       `json:"plays"`
	Errors      map[string]int     `json:"errors"`
	Metrics     map[string]float64 `json:"metrics"`
}

type ConfigSummary struct {
	Scenario      string  `json:"scenario,omitempty"`
	StartRate     float64 `json:"startRate"`
	RegisterTTL   string  `json:"registerTtl"`
	Keepalive     string  `json:"keepalive"`
	PlayRate      float64 `json:"playRate"`
	MaxConcurrent int     `json:"maxConcurrent"`
	PlayDuration  string  `json:"playDuration"`
	DeviceCount   int     `json:"deviceCount"`
}
type Stage struct {
	Name            string    `json:"name"`
	StartedAt       time.Time `json:"startedAt"`
	FinishedAt      time.Time `json:"finishedAt"`
	Devices         int       `json:"devices"`
	Success         int       `json:"success"`
	Failed          int       `json:"failed"`
	DurationSeconds float64   `json:"durationSeconds"`
	SuccessRate     float64   `json:"successRate"`
	LatencyP50      float64   `json:"latencyP50,omitempty"`
	LatencyP95      float64   `json:"latencyP95,omitempty"`
	LatencyP99      float64   `json:"latencyP99,omitempty"`
}
type DeviceResult struct {
	DeviceID           string   `json:"deviceId"`
	ChannelID          string   `json:"channelId"`
	Created            bool     `json:"created"`
	Started            bool     `json:"started"`
	RegisterRequests   int      `json:"registerRequests"`
	RegisterChallenges int      `json:"registerChallenges"`
	RegisterSuccess    int      `json:"registerSuccess"`
	CatalogSuccess     int      `json:"catalogSuccess"`
	InviteSuccess      int      `json:"inviteSuccess"`
	MediaStarted       int      `json:"mediaStarted"`
	DeviceBye          int      `json:"deviceBye"`
	DeviceByeSent      int      `json:"deviceByeSent"`
	Errors             []string `json:"errors,omitempty"`
}
type PlayResult struct {
	ID                  string    `json:"id"`
	DeviceID            string    `json:"deviceId"`
	ChannelID           string    `json:"channelId"`
	StartedAt           time.Time `json:"startedAt"`
	FinishedAt          time.Time `json:"finishedAt"`
	StartSuccess        bool      `json:"startSuccess"`
	StartError          string    `json:"startError,omitempty"`
	StartLatencySeconds float64   `json:"startLatencySeconds"`
	StopAttempted       bool      `json:"stopAttempted"`
	StopSuccess         bool      `json:"stopSuccess"`
	StopError           string    `json:"stopError,omitempty"`
	StopLatencySeconds  float64   `json:"stopLatencySeconds,omitempty"`
}

func normalize(report *Report) {
	for i := range report.Stages {
		s := &report.Stages[i]
		s.DurationSeconds = s.FinishedAt.Sub(s.StartedAt).Seconds()
		if s.Devices > 0 {
			s.SuccessRate = float64(s.Success) / float64(s.Devices)
		}
		if s.Name == "play" {
			s.LatencyP50 = report.Metrics["wvp_control_latency_seconds_p50"]
			s.LatencyP95 = report.Metrics["wvp_control_latency_seconds_p95"]
			s.LatencyP99 = report.Metrics["wvp_control_latency_seconds_p99"]
		}
	}
}
func WriteJSON(path string, report Report) error {
	normalize(&report)
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, b, 0644)
}
func WriteCSV(path string, report Report) error {
	normalize(&report)
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	w := csv.NewWriter(f)
	// Keep the original six columns first; append derived values for new readers.
	if err := w.Write([]string{"stage", "devices", "success", "failed", "startedAt", "finishedAt", "successRate", "durationSeconds"}); err != nil {
		return err
	}
	for _, s := range report.Stages {
		if err := w.Write([]string{s.Name, fmt.Sprint(s.Devices), fmt.Sprint(s.Success), fmt.Sprint(s.Failed), s.StartedAt.Format(time.RFC3339), s.FinishedAt.Format(time.RFC3339), fmt.Sprintf("%.4f", s.SuccessRate), fmt.Sprintf("%.3f", s.DurationSeconds)}); err != nil {
			return err
		}
	}
	w.Flush()
	return w.Error()
}

func WriteDetailCSV(path string, report Report) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	w := csv.NewWriter(f)
	if err := w.Write([]string{"type", "id", "deviceId", "channelId", "created", "started", "registerRequests", "registerChallenges", "registerSuccess", "catalogSuccess", "inviteSuccess", "mediaStarted", "deviceBye", "deviceByeSent", "startSuccess", "startError", "startLatencySeconds", "stopAttempted", "stopSuccess", "stopError", "stopLatencySeconds", "errors"}); err != nil {
		return err
	}
	for _, d := range report.Devices {
		if err := w.Write([]string{"device", "", d.DeviceID, d.ChannelID, fmt.Sprint(d.Created), fmt.Sprint(d.Started), fmt.Sprint(d.RegisterRequests), fmt.Sprint(d.RegisterChallenges), fmt.Sprint(d.RegisterSuccess), fmt.Sprint(d.CatalogSuccess), fmt.Sprint(d.InviteSuccess), fmt.Sprint(d.MediaStarted), fmt.Sprint(d.DeviceBye), fmt.Sprint(d.DeviceByeSent), "", "", "", "", "", "", "", strings.Join(d.Errors, " | ")}); err != nil {
			return err
		}
	}
	for _, p := range report.Plays {
		if err := w.Write([]string{"play", p.ID, p.DeviceID, p.ChannelID, "", "", "", "", "", "", "", "", "", "", fmt.Sprint(p.StartSuccess), p.StartError, fmt.Sprintf("%.3f", p.StartLatencySeconds), fmt.Sprint(p.StopAttempted), fmt.Sprint(p.StopSuccess), p.StopError, fmt.Sprintf("%.3f", p.StopLatencySeconds), ""}); err != nil {
			return err
		}
	}
	w.Flush()
	return w.Error()
}

func DetailCSVPath(summaryPath string) string {
	ext := filepath.Ext(summaryPath)
	if ext == "" {
		return summaryPath + ".details.csv"
	}
	return strings.TrimSuffix(summaryPath, ext) + ".details" + ext
}

func PrintSummary(out io.Writer, report Report, detailPath string) {
	normalize(&report)
	fmt.Fprintf(out, "run=%s devices=%d duration=%.1fs\n", report.RunID, report.DeviceCount, report.FinishedAt.Sub(report.StartedAt).Seconds())
	for _, s := range report.Stages {
		fmt.Fprintf(out, "stage=%s devices=%d success=%d failed=%d successRate=%.1f%% duration=%.3fs\n", s.Name, s.Devices, s.Success, s.Failed, s.SuccessRate*100, s.DurationSeconds)
	}
	registerSuccess, catalogSuccess, inviteSuccess, mediaStarted, deviceBye, deviceByeSent := 0, 0, 0, 0, 0, 0
	for _, d := range report.Devices {
		registerSuccess += d.RegisterSuccess
		catalogSuccess += d.CatalogSuccess
		inviteSuccess += d.InviteSuccess
		mediaStarted += d.MediaStarted
		deviceBye += d.DeviceBye
		deviceByeSent += d.DeviceByeSent
	}
	if len(report.Devices) > 0 {
		fmt.Fprintf(out, "events registerSuccess=%d catalogResponses=%d inviteSuccess=%d mediaStarted=%d deviceBye=%d deviceByeSent=%d\n", registerSuccess, catalogSuccess, inviteSuccess, mediaStarted, deviceBye, deviceByeSent)
	}
	plays := len(report.Plays)
	successful := 0
	stopped := 0
	for _, p := range report.Plays {
		if p.StartSuccess {
			successful++
		}
		if p.StopAttempted && p.StopSuccess {
			stopped++
		}
	}
	if plays > 0 {
		fmt.Fprintf(out, "plays total=%d success=%d failed=%d stopSuccess=%d\n", plays, successful, plays-successful, stopped)
	}
	keys := make([]string, 0, len(report.Metrics))
	for k := range report.Metrics {
		if strings.HasSuffix(k, "_p50") || strings.HasSuffix(k, "_p95") || strings.HasSuffix(k, "_p99") {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	for _, k := range keys {
		base := strings.TrimSuffix(strings.TrimSuffix(strings.TrimSuffix(k, "_p50"), "_p95"), "_p99")
		label := "P50"
		if strings.HasSuffix(k, "_p95") {
			label = "P95"
		}
		if strings.HasSuffix(k, "_p99") {
			label = "P99"
		}
		fmt.Fprintf(out, "latency %s %s=%.3fs\n", base, label, report.Metrics[k])
	}
	if detailPath != "" {
		fmt.Fprintf(out, "details=%s\n", detailPath)
	}
}
