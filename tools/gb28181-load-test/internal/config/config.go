package config

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	RunID       string           `yaml:"runId" json:"runId"`
	SIP         SIPConfig        `yaml:"sip" json:"sip"`
	DeviceCount int              `yaml:"deviceCount" json:"deviceCount"`
	IDPrefix    string           `yaml:"idPrefix" json:"idPrefix"`
	Identity    IdentityConfig   `yaml:"identity" json:"identity"`
	Domain      string           `yaml:"domain" json:"domain"`
	StartRate   float64          `yaml:"startRate" json:"startRate"`
	RegisterTTL time.Duration    `yaml:"registerTtl" json:"registerTtl"`
	Keepalive   time.Duration    `yaml:"keepalive" json:"keepalive"`
	Device      DeviceConfig     `yaml:"device" json:"device"`
	Scenario    ScenarioConfig   `yaml:"scenario" json:"scenario"`
	Control     ControlConfig    `yaml:"control" json:"control"`
	MetricsAddr string           `yaml:"metricsAddr" json:"metricsAddr"`
	Media       MediaConfig      `yaml:"media" json:"media"`
	Report      ReportConfig     `yaml:"report" json:"report"`
	Monitoring  MonitoringConfig `yaml:"monitoring" json:"monitoring"`
}

type IdentityConfig struct {
	Mode         string `yaml:"mode" json:"mode"`
	DeviceBase   string `yaml:"deviceBase" json:"deviceBase"`
	ChannelBase  string `yaml:"channelBase" json:"channelBase"`
	LegacyPrefix string `yaml:"legacyPrefix,omitempty" json:"legacyPrefix,omitempty"`
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

type ControlConfig struct {
	BaseURL   string `yaml:"baseUrl" json:"baseUrl"`
	Token     string `yaml:"token" json:"token"`
	StartPath string `yaml:"startPath" json:"startPath"`
	StopPath  string `yaml:"stopPath" json:"stopPath"`
}

type SIPConfig struct {
	Address   string `yaml:"address" json:"address"`
	Local     string `yaml:"local" json:"local"`
	Advertise string `yaml:"advertise" json:"advertise"`
}

type MediaConfig struct {
	File string `yaml:"file" json:"file"`
}

type ReportConfig struct {
	JSON         string `yaml:"json" json:"json"`
	CSV          string `yaml:"csv" json:"csv"`
	DetailCSV    string `yaml:"detailCsv" json:"detailCsv"`
	ResourcesCSV string `yaml:"resourcesCsv" json:"resourcesCsv"`
	Markdown     string `yaml:"markdown" json:"markdown"`
	Addr         string `yaml:"addr" json:"addr"`
}

// MonitoringConfig controls optional Prometheus and ZLM observation. It is
// deliberately separate from the load generator's own metrics endpoint so
// collection failures cannot affect SIP/play scheduling.
type MonitoringConfig struct {
	Enabled        bool             `yaml:"enabled" json:"enabled"`
	SampleInterval time.Duration    `yaml:"sampleInterval" json:"sampleInterval"`
	Prometheus     PrometheusConfig `yaml:"prometheus" json:"prometheus"`
	ZLM            ZLMConfig        `yaml:"zlm" json:"zlm"`
	SLO            SLOConfig        `yaml:"slo" json:"slo"`
}

type PrometheusConfig struct {
	URL          string            `yaml:"url" json:"url"`
	QueryTimeout time.Duration     `yaml:"queryTimeout" json:"queryTimeout"`
	Step         time.Duration     `yaml:"step" json:"step"`
	Queries      PrometheusQueries `yaml:"queries" json:"queries"`
}

type PrometheusQueries struct {
	WVPCPU              string `yaml:"wvpCpu" json:"wvpCpu"`
	JVMHeap             string `yaml:"jvmHeap" json:"jvmHeap"`
	JVMCGPause          string `yaml:"jvmGcPause" json:"jvmGcPause"`
	WVPHTTP             string `yaml:"wvpHttp" json:"wvpHttp"`
	WVPThreadActive     string `yaml:"wvpThreadActive" json:"wvpThreadActive"`
	WVPThreadQueue      string `yaml:"wvpThreadQueue" json:"wvpThreadQueue"`
	RedisCPU            string `yaml:"redisCpu" json:"redisCpu"`
	RedisMemory         string `yaml:"redisMemory" json:"redisMemory"`
	RedisConnections    string `yaml:"redisConnections" json:"redisConnections"`
	MySQLConnections    string `yaml:"mysqlConnections" json:"mysqlConnections"`
	MySQLThreads        string `yaml:"mysqlThreads" json:"mysqlThreads"`
	MySQLSlowQueries    string `yaml:"mysqlSlowQueries" json:"mysqlSlowQueries"`
	HostCPU             string `yaml:"hostCpu" json:"hostCpu"`
	HostMemory          string `yaml:"hostMemory" json:"hostMemory"`
	HostNetwork         string `yaml:"hostNetwork" json:"hostNetwork"`
	HostFileDescriptors string `yaml:"hostFileDescriptors" json:"hostFileDescriptors"`
}

type ZLMConfig struct {
	Enabled       bool   `yaml:"enabled" json:"enabled"`
	URL           string `yaml:"url" json:"url"`
	Secret        string `yaml:"secret" json:"secret"`
	MediaServerID string `yaml:"mediaServerId" json:"mediaServerId"`
}

type SLOConfig struct {
	RegisterSuccessRate      float64 `yaml:"registerSuccessRate" json:"registerSuccessRate"`
	CatalogSuccessRate       float64 `yaml:"catalogSuccessRate" json:"catalogSuccessRate"`
	PlaySuccessRate          float64 `yaml:"playSuccessRate" json:"playSuccessRate"`
	MediaSuccessRate         float64 `yaml:"mediaSuccessRate" json:"mediaSuccessRate"`
	StopSuccessRate          float64 `yaml:"stopSuccessRate" json:"stopSuccessRate"`
	ControlLatencyP95Seconds float64 `yaml:"controlLatencyP95Seconds" json:"controlLatencyP95Seconds"`
	ControlLatencyP99Seconds float64 `yaml:"controlLatencyP99Seconds" json:"controlLatencyP99Seconds"`
	MaxResidualSessions      int     `yaml:"maxResidualSessions" json:"maxResidualSessions"`
	MaxResidualStreams       int     `yaml:"maxResidualStreams" json:"maxResidualStreams"`
	MaxCPUPercent            float64 `yaml:"maxCpuPercent" json:"maxCpuPercent"`
	MaxJVMHeapPercent        float64 `yaml:"maxJvmHeapPercent" json:"maxJvmHeapPercent"`
	MaxRedisCPUPercent       float64 `yaml:"maxRedisCpuPercent" json:"maxRedisCpuPercent"`
	MaxNetworkPercent        float64 `yaml:"maxNetworkPercent" json:"maxNetworkPercent"`
}

type rawConfig struct {
	RunID       string         `yaml:"runId"`
	SIP         SIPConfig      `yaml:"sip"`
	DeviceCount int            `yaml:"deviceCount"`
	IDPrefix    string         `yaml:"idPrefix"`
	Identity    IdentityConfig `yaml:"identity"`
	Domain      string         `yaml:"domain"`
	StartRate   float64        `yaml:"startRate"`
	RegisterTTL string         `yaml:"registerTtl"`
	Keepalive   string         `yaml:"keepalive"`
	Device      DeviceConfig   `yaml:"device"`
	Scenario    rawScenario    `yaml:"scenario"`
	Control     ControlConfig  `yaml:"control"`
	MetricsAddr string         `yaml:"metricsAddr"`
	Media       MediaConfig    `yaml:"media"`
	Report      ReportConfig   `yaml:"report"`
	Monitoring  rawMonitoring  `yaml:"monitoring"`
}

type rawMonitoring struct {
	Enabled        bool          `yaml:"enabled"`
	SampleInterval string        `yaml:"sampleInterval"`
	Prometheus     rawPrometheus `yaml:"prometheus"`
	ZLM            ZLMConfig     `yaml:"zlm"`
	SLO            rawSLO        `yaml:"slo"`
}
type rawPrometheus struct {
	URL          string            `yaml:"url"`
	QueryTimeout string            `yaml:"queryTimeout"`
	Step         string            `yaml:"step"`
	Queries      PrometheusQueries `yaml:"queries"`
}
type rawSLO struct {
	RegisterSuccessRate      *float64 `yaml:"registerSuccessRate"`
	CatalogSuccessRate       *float64 `yaml:"catalogSuccessRate"`
	PlaySuccessRate          *float64 `yaml:"playSuccessRate"`
	MediaSuccessRate         *float64 `yaml:"mediaSuccessRate"`
	StopSuccessRate          *float64 `yaml:"stopSuccessRate"`
	ControlLatencyP95Seconds *float64 `yaml:"controlLatencyP95Seconds"`
	ControlLatencyP99Seconds *float64 `yaml:"controlLatencyP99Seconds"`
	MaxResidualSessions      *int     `yaml:"maxResidualSessions"`
	MaxResidualStreams       *int     `yaml:"maxResidualStreams"`
	MaxCPUPercent            *float64 `yaml:"maxCpuPercent"`
	MaxJVMHeapPercent        *float64 `yaml:"maxJvmHeapPercent"`
	MaxRedisCPUPercent       *float64 `yaml:"maxRedisCpuPercent"`
	MaxNetworkPercent        *float64 `yaml:"maxNetworkPercent"`
}

type rawScenario struct {
	Name          string  `yaml:"name"`
	Duration      string  `yaml:"duration"`
	PlayRate      float64 `yaml:"playRate"`
	MaxConcurrent int     `yaml:"maxConcurrent"`
	PlayDuration  string  `yaml:"playDuration"`
	StartupGrace  string  `yaml:"startupGrace"`
}

func Load(path string) (Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	var raw rawConfig
	if err := yaml.Unmarshal(b, &raw); err != nil {
		return Config{}, fmt.Errorf("decode config: %w", err)
	}
	c := Config{RunID: raw.RunID, SIP: raw.SIP, DeviceCount: raw.DeviceCount, IDPrefix: raw.IDPrefix,
		Identity: raw.Identity, Domain: raw.Domain, StartRate: raw.StartRate, Device: raw.Device,
		Control: raw.Control, MetricsAddr: raw.MetricsAddr, Media: raw.Media, Report: raw.Report,
		Scenario: ScenarioConfig{Name: raw.Scenario.Name, PlayRate: raw.Scenario.PlayRate, MaxConcurrent: raw.Scenario.MaxConcurrent}}
	c.Monitoring, err = monitoringFromRaw(raw.Monitoring)
	if err != nil {
		return Config{}, err
	}
	if strings.TrimSpace(c.Identity.LegacyPrefix) == "" {
		c.Identity.LegacyPrefix = c.IDPrefix
	}
	if strings.TrimSpace(c.Identity.Mode) == "" {
		switch {
		case c.Identity.DeviceBase != "" || c.Identity.ChannelBase != "":
			c.Identity.Mode = "numeric"
		case strings.TrimSpace(c.IDPrefix) != "":
			c.Identity.Mode = "legacy"
		default:
			c.Identity.Mode = "numeric"
		}
	}
	if raw.RegisterTTL != "" {
		c.RegisterTTL, err = time.ParseDuration(raw.RegisterTTL)
		if err != nil {
			return Config{}, fmt.Errorf("registerTtl: %w", err)
		}
	}
	if raw.Keepalive != "" {
		c.Keepalive, err = time.ParseDuration(raw.Keepalive)
		if err != nil {
			return Config{}, fmt.Errorf("keepalive: %w", err)
		}
	}
	if raw.Scenario.Duration != "" {
		c.Scenario.Duration, err = time.ParseDuration(raw.Scenario.Duration)
		if err != nil {
			return Config{}, fmt.Errorf("scenario.duration: %w", err)
		}
	}
	if raw.Scenario.PlayDuration != "" {
		c.Scenario.PlayDuration, err = time.ParseDuration(raw.Scenario.PlayDuration)
		if err != nil {
			return Config{}, fmt.Errorf("scenario.playDuration: %w", err)
		}
	}
	if raw.Scenario.StartupGrace != "" {
		c.Scenario.StartupGrace, err = time.ParseDuration(raw.Scenario.StartupGrace)
		if err != nil {
			return Config{}, fmt.Errorf("scenario.startupGrace: %w", err)
		}
	}
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

func monitoringFromRaw(raw rawMonitoring) (MonitoringConfig, error) {
	m := MonitoringConfig{Enabled: raw.Enabled, SampleInterval: 15 * time.Second}
	if raw.SampleInterval != "" {
		d, err := time.ParseDuration(raw.SampleInterval)
		if err != nil {
			return MonitoringConfig{}, fmt.Errorf("monitoring.sampleInterval: %w", err)
		}
		m.SampleInterval = d
	}
	m.Prometheus = PrometheusConfig{URL: raw.Prometheus.URL, QueryTimeout: 10 * time.Second, Step: 15 * time.Second, Queries: raw.Prometheus.Queries}
	if raw.Prometheus.QueryTimeout != "" {
		d, err := time.ParseDuration(raw.Prometheus.QueryTimeout)
		if err != nil {
			return MonitoringConfig{}, fmt.Errorf("monitoring.prometheus.queryTimeout: %w", err)
		}
		m.Prometheus.QueryTimeout = d
	}
	if raw.Prometheus.Step != "" {
		d, err := time.ParseDuration(raw.Prometheus.Step)
		if err != nil {
			return MonitoringConfig{}, fmt.Errorf("monitoring.prometheus.step: %w", err)
		}
		m.Prometheus.Step = d
	}
	m.ZLM = raw.ZLM
	// Zero SLO fields mean “use the conservative defaults” for backwards
	// compatibility with existing configs that do not declare monitoring.
	m.SLO = SLOConfig{
		RegisterSuccessRate: .999, CatalogSuccessRate: .995, PlaySuccessRate: .990,
		MediaSuccessRate: .990, StopSuccessRate: .990, ControlLatencyP95Seconds: 5,
		ControlLatencyP99Seconds: 10, MaxResidualSessions: 0, MaxResidualStreams: 0,
		MaxCPUPercent: 85, MaxJVMHeapPercent: 85, MaxRedisCPUPercent: 80, MaxNetworkPercent: 85,
	}
	applySLO(&m.SLO, raw.SLO)
	return m, nil
}

func applySLO(dst *SLOConfig, src rawSLO) {
	if src.RegisterSuccessRate != nil {
		dst.RegisterSuccessRate = *src.RegisterSuccessRate
	}
	if src.CatalogSuccessRate != nil {
		dst.CatalogSuccessRate = *src.CatalogSuccessRate
	}
	if src.PlaySuccessRate != nil {
		dst.PlaySuccessRate = *src.PlaySuccessRate
	}
	if src.MediaSuccessRate != nil {
		dst.MediaSuccessRate = *src.MediaSuccessRate
	}
	if src.StopSuccessRate != nil {
		dst.StopSuccessRate = *src.StopSuccessRate
	}
	if src.ControlLatencyP95Seconds != nil {
		dst.ControlLatencyP95Seconds = *src.ControlLatencyP95Seconds
	}
	if src.ControlLatencyP99Seconds != nil {
		dst.ControlLatencyP99Seconds = *src.ControlLatencyP99Seconds
	}
	if src.MaxResidualSessions != nil {
		dst.MaxResidualSessions = *src.MaxResidualSessions
	}
	if src.MaxResidualStreams != nil {
		dst.MaxResidualStreams = *src.MaxResidualStreams
	}
	if src.MaxCPUPercent != nil {
		dst.MaxCPUPercent = *src.MaxCPUPercent
	}
	if src.MaxJVMHeapPercent != nil {
		dst.MaxJVMHeapPercent = *src.MaxJVMHeapPercent
	}
	if src.MaxRedisCPUPercent != nil {
		dst.MaxRedisCPUPercent = *src.MaxRedisCPUPercent
	}
	if src.MaxNetworkPercent != nil {
		dst.MaxNetworkPercent = *src.MaxNetworkPercent
	}
}

func (c Config) Validate() error {
	if c.DeviceCount <= 0 {
		return errors.New("deviceCount must be greater than zero")
	}
	if c.StartRate < 0 {
		return errors.New("startRate must not be negative")
	}
	switch strings.ToLower(strings.TrimSpace(c.Identity.Mode)) {
	case "numeric":
		devicePrefix, deviceSequence, err := validateNumericBase("deviceBase", c.Identity.DeviceBase)
		if err != nil {
			return err
		}
		channelPrefix, channelSequence, err := validateNumericBase("channelBase", c.Identity.ChannelBase)
		if err != nil {
			return err
		}
		if devicePrefix == channelPrefix {
			return errors.New("deviceBase and channelBase prefixes must differ")
		}
		if c.DeviceCount > 999999-deviceSequence {
			return errors.New("deviceCount exceeds deviceBase sequence range")
		}
		if c.DeviceCount > 999999-channelSequence {
			return errors.New("deviceCount exceeds channelBase sequence range")
		}
	case "legacy":
		prefix := strings.TrimSpace(c.Identity.LegacyPrefix)
		if prefix == "" {
			prefix = strings.TrimSpace(c.IDPrefix)
		}
		if prefix == "" {
			return errors.New("idPrefix must not be empty")
		}
		if !strings.HasPrefix(prefix, "loadtest-") {
			return errors.New("idPrefix must start with loadtest-")
		}
	default:
		return fmt.Errorf("identity.mode must be numeric or legacy")
	}
	if strings.TrimSpace(c.Domain) == "" {
		return errors.New("domain must not be empty")
	}
	for _, r := range c.Domain {
		if r < '0' || r > '9' {
			return errors.New("domain must contain only digits")
		}
	}
	if _, _, err := net.SplitHostPort(c.SIP.Address); err != nil {
		return fmt.Errorf("invalid sip address: %w", err)
	}
	if c.RegisterTTL <= 0 {
		return errors.New("registerTtl must be positive")
	}
	if c.Keepalive <= 0 {
		return errors.New("keepalive must be positive")
	}
	if strings.TrimSpace(c.Device.Username) == "" {
		return errors.New("device.username must not be empty")
	}
	if strings.TrimSpace(c.Device.ServerID) == "" {
		return errors.New("device.serverId must not be empty")
	}
	if c.Scenario.PlayRate < 0 {
		return errors.New("scenario.playRate must not be negative")
	}
	if c.Scenario.MaxConcurrent < 0 {
		return errors.New("scenario.maxConcurrent must not be negative")
	}
	if c.Scenario.PlayDuration < 0 {
		return errors.New("scenario.playDuration must not be negative")
	}
	if c.Scenario.StartupGrace < 0 {
		return errors.New("scenario.startupGrace must not be negative")
	}
	if strings.TrimSpace(c.Media.File) == "" {
		return errors.New("media.file must not be empty")
	}
	if c.Monitoring.SampleInterval <= 0 {
		return errors.New("monitoring.sampleInterval must be positive")
	}
	if c.Monitoring.Prometheus.QueryTimeout <= 0 {
		return errors.New("monitoring.prometheus.queryTimeout must be positive")
	}
	if c.Monitoring.Prometheus.Step <= 0 {
		return errors.New("monitoring.prometheus.step must be positive")
	}
	if c.Monitoring.Enabled {
		if err := validateHTTPURL("monitoring.prometheus.url", c.Monitoring.Prometheus.URL); err != nil {
			return err
		}
		if c.Monitoring.ZLM.Enabled {
			if err := validateHTTPURL("monitoring.zlm.url", c.Monitoring.ZLM.URL); err != nil {
				return err
			}
		}
	}
	for name, value := range map[string]float64{
		"registerSuccessRate": c.Monitoring.SLO.RegisterSuccessRate, "catalogSuccessRate": c.Monitoring.SLO.CatalogSuccessRate,
		"playSuccessRate": c.Monitoring.SLO.PlaySuccessRate, "mediaSuccessRate": c.Monitoring.SLO.MediaSuccessRate,
		"stopSuccessRate": c.Monitoring.SLO.StopSuccessRate,
	} {
		if value < 0 || value > 1 {
			return fmt.Errorf("monitoring.slo.%s must be between 0 and 1", name)
		}
	}
	for name, value := range map[string]float64{
		"maxCpuPercent": c.Monitoring.SLO.MaxCPUPercent, "maxJvmHeapPercent": c.Monitoring.SLO.MaxJVMHeapPercent,
		"maxRedisCpuPercent": c.Monitoring.SLO.MaxRedisCPUPercent, "maxNetworkPercent": c.Monitoring.SLO.MaxNetworkPercent,
	} {
		if value < 0 || value > 100 {
			return fmt.Errorf("monitoring.slo.%s must be between 0 and 100", name)
		}
	}
	if c.Monitoring.SLO.ControlLatencyP95Seconds < 0 || c.Monitoring.SLO.ControlLatencyP99Seconds < 0 {
		return errors.New("monitoring.slo control latency must not be negative")
	}
	if c.Monitoring.SLO.MaxResidualSessions < 0 || c.Monitoring.SLO.MaxResidualStreams < 0 {
		return errors.New("monitoring.slo residual limits must not be negative")
	}
	return nil
}

func validateHTTPURL(name, value string) error {
	u, err := url.Parse(strings.TrimSpace(value))
	if err != nil || u.Scheme != "http" && u.Scheme != "https" || u.Host == "" {
		return fmt.Errorf("%s must be a valid http(s) URL", name)
	}
	return nil
}

func validateNumericBase(name, value string) (string, int, error) {
	if len(value) != 20 {
		return "", 0, fmt.Errorf("%s must be exactly 20 digits", name)
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return "", 0, fmt.Errorf("%s must contain only digits", name)
		}
	}
	sequence := 0
	for _, r := range value[14:] {
		sequence = sequence*10 + int(r-'0')
	}
	if sequence >= 999999 {
		return "", 0, fmt.Errorf("%s sequence leaves no available range", name)
	}
	return value[:14], sequence, nil
}
