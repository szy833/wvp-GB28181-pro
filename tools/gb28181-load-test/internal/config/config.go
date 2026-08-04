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
	Media       MediaConfig   `yaml:"media" json:"media"`
	Report      ReportConfig  `yaml:"report" json:"report"`
}

type SIPConfig struct {
	Address string `yaml:"address" json:"address"`
	Local   string `yaml:"local" json:"local"`
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
	Media       MediaConfig  `yaml:"media"`
	Report      ReportConfig `yaml:"report"`
}

func Load(path string) (Config, error) {
	b, err := os.ReadFile(path)
	if err != nil { return Config{}, err }
	var raw rawConfig
	if err := yaml.Unmarshal(b, &raw); err != nil { return Config{}, fmt.Errorf("decode config: %w", err) }
	c := Config{RunID: raw.RunID, SIP: raw.SIP, DeviceCount: raw.DeviceCount, IDPrefix: raw.IDPrefix, Domain: raw.Domain, StartRate: raw.StartRate, Media: raw.Media, Report: raw.Report}
	if raw.RegisterTTL != "" { c.RegisterTTL, err = time.ParseDuration(raw.RegisterTTL); if err != nil { return Config{}, fmt.Errorf("registerTtl: %w", err) } }
	if raw.Keepalive != "" { c.Keepalive, err = time.ParseDuration(raw.Keepalive); if err != nil { return Config{}, fmt.Errorf("keepalive: %w", err) } }
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
	if strings.TrimSpace(c.Media.File) == "" { return errors.New("media.file must not be empty") }
	return nil
}
