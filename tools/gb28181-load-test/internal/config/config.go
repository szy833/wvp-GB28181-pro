package config

import (
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	RunID       string        `yaml:"runId" json:"runId"`
	SIP         SIPConfig     `yaml:"sip" json:"sip"`
	DeviceCount int           `yaml:"deviceCount" json:"deviceCount"`
	IDPrefix    string        `yaml:"idPrefix" json:"idPrefix"`
	Domain      string        `yaml:"domain" json:"domain"`
	StartRate   float64       `yaml:"startRate" json:"startRate"`
	RegisterTTL time.Duration `yaml:"registerTtl" json:"registerTtl"`
	Keepalive   time.Duration `yaml:"keepalive" json:"keepalive"`
	Device      DeviceConfig  `yaml:"device" json:"device"`
	Scenario    ScenarioConfig `yaml:"scenario" json:"scenario"`
	Control     ControlConfig  `yaml:"control" json:"control"`
	MetricsAddr string        `yaml:"metricsAddr" json:"metricsAddr"`
	Media       MediaConfig   `yaml:"media" json:"media"`
	Report      ReportConfig  `yaml:"report" json:"report"`
}

type DeviceConfig struct {
	Username     string `yaml:"username" json:"username"`
	Password     string `yaml:"password" json:"password"`
	Realm        string `yaml:"realm" json:"realm"`
	ServerID     string `yaml:"serverId" json:"serverId"`
	Name         string `yaml:"name" json:"name"`
	Manufacturer string `yaml:"manufacturer" json:"manufacturer"`
	Model        string `yaml:"model" json:"model"`
}

type ScenarioConfig struct {
	Name          string        `yaml:"name" json:"name"`
	Duration      time.Duration `yaml:"duration" json:"duration"`
	PlayRate      float64       `yaml:"playRate" json:"playRate"`
	MaxConcurrent int           `yaml:"maxConcurrent" json:"maxConcurrent"`
	PlayDuration  time.Duration `yaml:"playDuration" json:"playDuration"`
	StartupGrace  time.Duration `yaml:"startupGrace" json:"startupGrace"`
}

type ControlConfig struct { BaseURL string `yaml:"baseUrl" json:"baseUrl"`; Token string `yaml:"token" json:"token"`; StartPath string `yaml:"startPath" json:"startPath"`; StopPath string `yaml:"stopPath" json:"stopPath"` }

type SIPConfig struct {
	Address string `yaml:"address" json:"address"`
	Local   string `yaml:"local" json:"local"`
	Advertise string `yaml:"advertise" json:"advertise"`
}

type MediaConfig struct {
	File string `yaml:"file" json:"file"`
}

type ReportConfig struct {
	JSON string `yaml:"json" json:"json"`
	CSV  string `yaml:"csv" json:"csv"`
	Addr string `yaml:"addr" json:"addr"`
}

type rawConfig struct {
	RunID       string    `yaml:"runId"`
	SIP         SIPConfig `yaml:"sip"`
	DeviceCount int       `yaml:"deviceCount"`
	IDPrefix    string    `yaml:"idPrefix"`
	Domain      string    `yaml:"domain"`
	StartRate   float64   `yaml:"startRate"`
	RegisterTTL string    `yaml:"registerTtl"`
	Keepalive   string    `yaml:"keepalive"`
	Device      DeviceConfig `yaml:"device"`
	Scenario    rawScenario `yaml:"scenario"`
	Control     ControlConfig `yaml:"control"`
	MetricsAddr string `yaml:"metricsAddr"`
	Media       MediaConfig  `yaml:"media"`
	Report      ReportConfig `yaml:"report"`
}

type rawScenario struct { Name string `yaml:"name"`; Duration string `yaml:"duration"`; PlayRate float64 `yaml:"playRate"`; MaxConcurrent int `yaml:"maxConcurrent"`; PlayDuration string `yaml:"playDuration"`; StartupGrace string `yaml:"startupGrace"` }

func Load(path string) (Config, error) {
	b, err := os.ReadFile(path)
	if err != nil { return Config{}, err }
	var raw rawConfig
	if err := yaml.Unmarshal(b, &raw); err != nil { return Config{}, fmt.Errorf("decode config: %w", err) }
	c := Config{RunID: raw.RunID, SIP: raw.SIP, DeviceCount: raw.DeviceCount, IDPrefix: raw.IDPrefix, Domain: raw.Domain, StartRate: raw.StartRate, Device: raw.Device, Control: raw.Control, MetricsAddr: raw.MetricsAddr, Media: raw.Media, Report: raw.Report, Scenario: ScenarioConfig{Name: raw.Scenario.Name, PlayRate: raw.Scenario.PlayRate, MaxConcurrent: raw.Scenario.MaxConcurrent}}
	if raw.RegisterTTL != "" { c.RegisterTTL, err = time.ParseDuration(raw.RegisterTTL); if err != nil { return Config{}, fmt.Errorf("registerTtl: %w", err) } }
	if raw.Keepalive != "" { c.Keepalive, err = time.ParseDuration(raw.Keepalive); if err != nil { return Config{}, fmt.Errorf("keepalive: %w", err) } }
	if raw.Scenario.Duration != "" { c.Scenario.Duration, err = time.ParseDuration(raw.Scenario.Duration); if err != nil { return Config{}, fmt.Errorf("scenario.duration: %w", err) } }
	if raw.Scenario.PlayDuration != "" { c.Scenario.PlayDuration, err = time.ParseDuration(raw.Scenario.PlayDuration); if err != nil { return Config{}, fmt.Errorf("scenario.playDuration: %w", err) } }
	if raw.Scenario.StartupGrace != "" { c.Scenario.StartupGrace, err = time.ParseDuration(raw.Scenario.StartupGrace); if err != nil { return Config{}, fmt.Errorf("scenario.startupGrace: %w", err) } }
	if err := c.Validate(); err != nil { return Config{}, err }
	return c, nil
}

func (c Config) Validate() error {
	if c.DeviceCount <= 0 { return errors.New("deviceCount must be greater than zero") }
	if c.StartRate < 0 { return errors.New("startRate must not be negative") }
	if strings.TrimSpace(c.IDPrefix) == "" { return errors.New("idPrefix must not be empty") }
	if !strings.HasPrefix(c.IDPrefix, "loadtest-") { return errors.New("idPrefix must start with loadtest-") }
	if strings.TrimSpace(c.Domain) == "" { return errors.New("domain must not be empty") }
	for _, r := range c.Domain { if r < '0' || r > '9' { return errors.New("domain must contain only digits") } }
	if _, _, err := net.SplitHostPort(c.SIP.Address); err != nil { return fmt.Errorf("invalid sip address: %w", err) }
	if c.RegisterTTL <= 0 { return errors.New("registerTtl must be positive") }
	if c.Keepalive <= 0 { return errors.New("keepalive must be positive") }
	if strings.TrimSpace(c.Device.Username) == "" { return errors.New("device.username must not be empty") }
	if strings.TrimSpace(c.Device.ServerID) == "" { return errors.New("device.serverId must not be empty") }
	if c.Scenario.PlayRate < 0 { return errors.New("scenario.playRate must not be negative") }
	if c.Scenario.MaxConcurrent < 0 { return errors.New("scenario.maxConcurrent must not be negative") }
	if c.Scenario.PlayDuration < 0 { return errors.New("scenario.playDuration must not be negative") }
	if c.Scenario.StartupGrace < 0 { return errors.New("scenario.startupGrace must not be negative") }
	if strings.TrimSpace(c.Media.File) == "" { return errors.New("media.file must not be empty") }
	return nil
}
