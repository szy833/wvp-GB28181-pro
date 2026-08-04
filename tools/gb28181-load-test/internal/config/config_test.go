package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeConfig(t *testing.T, text string) string {
	t.Helper(); p := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(p, []byte(text), 0600); err != nil { t.Fatal(err) }; return p
}

func validYAML() string { return `runId: run-1
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
report:
  json: report.json
` }

func TestLoadValidYAML(t *testing.T) {
	c, err := Load(writeConfig(t, validYAML())); if err != nil { t.Fatal(err) }
	if c.DeviceCount != 10 || c.RegisterTTL.Hours() != 1 { t.Fatalf("unexpected config: %+v", c) }
}

func TestValidateRejectsInvalidValues(t *testing.T) {
	tests := []struct{name, replace, want string}{
		{"count", "deviceCount: 0", "deviceCount"},
		{"prefix", "idPrefix: camera-", "idPrefix"},
		{"address", "address: bad", "sip address"},
		{"rate", "startRate: -1", "startRate"},
	}
	for _, tc := range tests { t.Run(tc.name, func(t *testing.T) {
		text := validYAML(); if tc.name == "address" { text = "sip:\n  address: bad\n" + text[strings.Index(text, "deviceCount:"):]} else { text = strings.Replace(text, strings.Split(tc.replace, ":")[0]+": "+strings.Split(tc.replace, ": ")[1], tc.replace, 1) }
		_, err := Load(writeConfig(t, text)); if err == nil || !strings.Contains(err.Error(), tc.want) { t.Fatalf("error=%v", err) }
	}) }
}
