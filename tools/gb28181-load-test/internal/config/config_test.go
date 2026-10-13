package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func writeConfig(t *testing.T, text string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(p, []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
	return p
}

func validYAML() string {
	return `runId: run-1
sip:
  address: 127.0.0.1:5060
deviceCount: 10
idPrefix: loadtest-
domain: 3402000000
startRate: 2
registerTtl: 1h
keepalive: 30s
media:
  file: sample.ps
device:
  username: loadtest
  password: secret
  serverId: 34020000002000000001
report:
  json: report.json
`
}

func TestLoadValidYAML(t *testing.T) {
	c, err := Load(writeConfig(t, validYAML()))
	if err != nil {
		t.Fatal(err)
	}
	if c.DeviceCount != 10 || c.RegisterTTL.Hours() != 1 {
		t.Fatalf("unexpected config: %+v", c)
	}
}

func TestLoadNumericIdentity(t *testing.T) {
	text := validYAML() + `identity:
  mode: numeric
  deviceBase: "34020000001110000000"
  channelBase: "34020000001310000000"
`
	c, err := Load(writeConfig(t, text))
	if err != nil {
		t.Fatal(err)
	}
	if c.Identity.Mode != "numeric" || c.Identity.DeviceBase == "" {
		t.Fatalf("unexpected identity: %+v", c.Identity)
	}
}

func TestValidateNumericIdentityRejectsNon20DigitBase(t *testing.T) {
	text := validYAML() + `identity:
  mode: numeric
  deviceBase: "loadtest-000001"
  channelBase: "34020000001310000000"
`
	_, err := Load(writeConfig(t, text))
	if err == nil || !strings.Contains(err.Error(), "deviceBase") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestLegacyConfigRemainsCompatible(t *testing.T) {
	c, err := Load(writeConfig(t, validYAML()))
	if err != nil {
		t.Fatal(err)
	}
	if c.Identity.Mode != "legacy" || c.Identity.LegacyPrefix != "loadtest-" {
		t.Fatalf("unexpected identity: %+v", c.Identity)
	}
}

func TestValidateRejectsInvalidValues(t *testing.T) {
	tests := []struct{ name, replace, want string }{
		{"count", "deviceCount: 0", "deviceCount"},
		{"prefix", "idPrefix: camera-", "idPrefix"},
		{"address", "address: bad", "sip address"},
		{"rate", "startRate: -1", "startRate"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			text := validYAML()
			if tc.name == "address" {
				text = "sip:\n  address: bad\n" + text[strings.Index(text, "deviceCount:"):]
			} else {
				key, value, _ := strings.Cut(tc.replace, ": ")
				lines := strings.Split(text, "\n")
				for i, line := range lines {
					if strings.HasPrefix(line, key+": ") {
						lines[i] = key + ": " + value
						break
					}
				}
				text = strings.Join(lines, "\n")
			}
			_, err := Load(writeConfig(t, text))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error=%v", err)
			}
		})
	}
}

func monitoringYAML() string {
	return validYAML() + `monitoring:
  enabled: true
  sampleInterval: 20s
  prometheus:
    url: http://prometheus.example:9090
    queryTimeout: 3s
    step: 20s
    queries:
      wvpCpu: process_cpu_usage
  zlm:
    enabled: true
    url: http://zlm.example:19001
    secret: should-not-be-in-report
    mediaServerId: zlm-1
  slo:
    registerSuccessRate: 0.99
    maxCpuPercent: 90
`
}

func TestLoadMonitoringConfigParsesDurationsAndQueries(t *testing.T) {
	c, err := Load(writeConfig(t, monitoringYAML()))
	if err != nil {
		t.Fatal(err)
	}
	if !c.Monitoring.Enabled || c.Monitoring.SampleInterval != 20*time.Second {
		t.Fatalf("unexpected monitoring config: %+v", c.Monitoring)
	}
	if c.Monitoring.Prometheus.QueryTimeout != 3*time.Second || c.Monitoring.Prometheus.Step != 20*time.Second {
		t.Fatalf("unexpected prometheus durations: %+v", c.Monitoring.Prometheus)
	}
	if c.Monitoring.Prometheus.Queries.WVPCPU != "process_cpu_usage" {
		t.Fatalf("custom query was not parsed: %+v", c.Monitoring.Prometheus.Queries)
	}
	if !c.Monitoring.ZLM.Enabled || c.Monitoring.ZLM.MediaServerID != "zlm-1" {
		t.Fatalf("unexpected zlm config: %+v", c.Monitoring.ZLM)
	}
	if c.Monitoring.SLO.RegisterSuccessRate != 0.99 || c.Monitoring.SLO.MaxCPUPercent != 90 {
		t.Fatalf("unexpected slo config: %+v", c.Monitoring.SLO)
	}
}

func TestMonitoringDisabledKeepsDefaultsAndSkipsEndpointValidation(t *testing.T) {
	text := validYAML() + `monitoring:
  enabled: false
  prometheus:
    url: ://bad
`
	c, err := Load(writeConfig(t, text))
	if err != nil {
		t.Fatal(err)
	}
	if c.Monitoring.Enabled {
		t.Fatal("monitoring should be disabled")
	}
	if c.Monitoring.SampleInterval != 15*time.Second || c.Monitoring.Prometheus.QueryTimeout != 10*time.Second {
		t.Fatalf("unexpected monitoring defaults: %+v", c.Monitoring)
	}
}

func TestMonitoringEnabledRequiresValidEndpoints(t *testing.T) {
	tests := []struct {
		name string
		text string
		want string
	}{
		{"monitoring source", validYAML() + "monitoring:\n  enabled: true\n", "monitoring source"},
		{"zlm url", validYAML() + "monitoring:\n  enabled: true\n  prometheus:\n    url: http://127.0.0.1:9090\n  zlm:\n    enabled: true\n", "zlm.url"},
		{"sample interval", monitoringYAML() + "", ""},
	}
	for _, tc := range tests[:2] {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(writeConfig(t, tc.text))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("error=%v, want substring %q", err, tc.want)
			}
		})
	}
}

func TestMonitoringAllowsZLMWithoutPrometheus(t *testing.T) {
	_, err := Load(writeConfig(t, validYAML()+"monitoring:\n  enabled: true\n  zlm:\n    enabled: true\n    url: http://127.0.0.1:19001\n"))
	if err != nil {
		t.Fatal(err)
	}
}

func TestMonitoringRejectsInvalidSLO(t *testing.T) {
	for _, replacement := range []string{
		"registerSuccessRate: 1.1",
		"maxCpuPercent: 101",
		"maxResidualStreams: -1",
	} {
		text := validYAML() + "monitoring:\n  enabled: true\n  prometheus:\n    url: http://127.0.0.1:9090\n  slo:\n    " + replacement + "\n"
		if _, err := Load(writeConfig(t, text)); err == nil {
			t.Fatalf("expected validation error for %s", replacement)
		}
	}
}
