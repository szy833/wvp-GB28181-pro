package runner

import (
	"context"
	"sort"
	"sync"
	"time"

	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"github.com/shizy/gb28181-load-test/internal/monitoring"
)

type Factory interface {
	New(int) (*device.Device, error)
}

type Runner struct {
	Metrics         *metrics.Registry
	PlayController  PlayController
	PlayRate        float64
	MaxConcurrent   int
	PlayDuration    time.Duration
	StartupGrace    time.Duration
	ResourceSamples []monitoring.ResourceSample
}

type resultCollector struct {
	mu           sync.Mutex
	devices      map[string]*metrics.DeviceResult
	plays        []metrics.PlayResult
	playEvents   map[string]map[string][]time.Time
	pendingPlays map[string][]int
	metrics      *metrics.Registry
}

func newResultCollector(registry *metrics.Registry) *resultCollector {
	return &resultCollector{devices: make(map[string]*metrics.DeviceResult), playEvents: make(map[string]map[string][]time.Time), pendingPlays: make(map[string][]int), metrics: registry}
}

func (c *resultCollector) RecordDevice(d *device.Device) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, exists := c.devices[d.Identity.DeviceID]; !exists {
		c.devices[d.Identity.DeviceID] = &metrics.DeviceResult{
			DeviceID: d.Identity.DeviceID, ChannelID: d.Identity.ChannelID, Created: true,
		}
	}
}

func (c *resultCollector) MarkStarted(deviceID string, started bool, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	result := c.devices[deviceID]
	if result == nil {
		result = &metrics.DeviceResult{DeviceID: deviceID}
		c.devices[deviceID] = result
	}
	result.Started = started
	if err != nil {
		result.Errors = append(result.Errors, "run: "+err.Error())
	}
}

func (c *resultCollector) OnDeviceEvent(event device.DeviceEvent) {
	c.mu.Lock()
	result := c.devices[event.DeviceID]
	if result == nil {
		result = &metrics.DeviceResult{DeviceID: event.DeviceID, ChannelID: event.ChannelID}
		c.devices[event.DeviceID] = result
	}
	if result.ChannelID == "" {
		result.ChannelID = event.ChannelID
	}
	switch event.Name {
	case "register_request":
		result.RegisterRequests++
	case "register_challenge":
		result.RegisterChallenges++
	case "register_success":
		result.RegisterSuccess++
	case "catalog_response", "deviceinfo_response", "devicestatus_response":
		result.CatalogSuccess++
	case "invite_success":
		result.InviteSuccess++
		c.recordPlayEventLocked(event.DeviceID, "invite_success", event.At)
	case "media_started":
		result.MediaStarted++
		c.recordPlayEventLocked(event.DeviceID, "media_started", event.At)
	case "device_bye":
		result.DeviceBye++
	case "device_bye_sent":
		result.DeviceByeSent++
	case "register_error", "query_error", "invite_error", "media_start_error":
		if event.Error != "" {
			result.Errors = append(result.Errors, event.Name+": "+event.Error)
		} else {
			result.Errors = append(result.Errors, event.Name)
		}
		if c.metrics != nil {
			c.metrics.Counter("sip_event_errors_total", 1)
		}
	}
	c.mu.Unlock()
	if c.metrics == nil {
		return
	}
	counters := map[string]string{
		"register_request":      "sip_register_requests_total",
		"register_challenge":    "sip_register_challenges_total",
		"register_success":      "sip_register_success_total",
		"catalog_response":      "sip_catalog_responses_total",
		"deviceinfo_response":   "sip_deviceinfo_responses_total",
		"devicestatus_response": "sip_devicestatus_responses_total",
		"invite_success":        "sip_invite_success_total",
		"media_started":         "sip_media_started_total",
		"device_bye":            "sip_device_bye_total",
		"device_bye_sent":       "sip_device_bye_sent_total",
	}
	if counter := counters[event.Name]; counter != "" {
		c.metrics.Counter(counter, 1)
	}
}

func (c *resultCollector) recordPlayEventLocked(deviceID, event string, at time.Time) {
	if at.IsZero() {
		at = time.Now()
	}
	if c.playEvents[deviceID] == nil {
		c.playEvents[deviceID] = make(map[string][]time.Time)
	}
	c.playEvents[deviceID][event] = append(c.playEvents[deviceID][event], at)
	c.applyPlayEventsLocked(deviceID)
}

func (c *resultCollector) OnPlayEvent(deviceID, event string, at time.Time) {
	c.mu.Lock()
	c.recordPlayEventLocked(deviceID, event, at)
	c.mu.Unlock()
}

func (c *resultCollector) RecordPlay(result metrics.PlayResult) {
	c.mu.Lock()
	c.plays = append(c.plays, result)
	if result.StartSuccess {
		c.pendingPlays[result.DeviceID] = append(c.pendingPlays[result.DeviceID], len(c.plays)-1)
		c.applyPlayEventsLocked(result.DeviceID)
	}
	c.mu.Unlock()
}

// applyPlayEventsLocked joins asynchronous device events to the oldest play
// on the same device that is still missing that phase. A queue is required:
// one virtual device can be scheduled again before its previous media event
// arrives, so a single timestamp would overwrite an earlier play.
func (c *resultCollector) applyPlayEventsLocked(deviceID string) {
	events := c.playEvents[deviceID]
	indexes := c.pendingPlays[deviceID]
	for _, phase := range []string{"invite_success", "media_started"} {
		queue := events[phase]
		for len(queue) > 0 {
			match := -1
			for _, index := range indexes {
				if index < 0 || index >= len(c.plays) {
					continue
				}
				play := &c.plays[index]
				if phase == "invite_success" && play.InviteAt.IsZero() || phase == "media_started" && play.MediaAt.IsZero() {
					match = index
					break
				}
			}
			if match < 0 {
				break
			}
			at := queue[0]
			queue = queue[1:]
			play := &c.plays[match]
			if phase == "invite_success" {
				play.InviteAt = at
				play.InviteLatencySeconds = at.Sub(play.StartedAt).Seconds()
				if c.metrics != nil {
					c.metrics.Observe("invite_latency_seconds", play.InviteLatencySeconds)
				}
			} else {
				play.MediaAt = at
				play.MediaConfirmationLatencySeconds = at.Sub(play.StartedAt).Seconds()
				if c.metrics != nil {
					c.metrics.Observe("media_confirmation_latency_seconds", play.MediaConfirmationLatencySeconds)
				}
			}
		}
		events[phase] = queue
	}
	remaining := indexes[:0]
	for _, index := range indexes {
		play := &c.plays[index]
		if play.InviteAt.IsZero() || play.MediaAt.IsZero() {
			remaining = append(remaining, index)
		}
	}
	if len(remaining) == 0 {
		delete(c.pendingPlays, deviceID)
	} else {
		c.pendingPlays[deviceID] = remaining
	}
	if len(events["invite_success"]) == 0 && len(events["media_started"]) == 0 {
		delete(c.playEvents, deviceID)
	}
}

func (c *resultCollector) DeviceResults() []metrics.DeviceResult {
	c.mu.Lock()
	defer c.mu.Unlock()
	ids := make([]string, 0, len(c.devices))
	for id := range c.devices {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	results := make([]metrics.DeviceResult, 0, len(ids))
	for _, id := range ids {
		value := *c.devices[id]
		value.Errors = append([]string(nil), value.Errors...)
		results = append(results, value)
	}
	return results
}

func (c *resultCollector) PlayResults() []metrics.PlayResult {
	c.mu.Lock()
	defer c.mu.Unlock()
	results := append([]metrics.PlayResult(nil), c.plays...)
	sort.Slice(results, func(i, j int) bool { return results[i].StartedAt.Before(results[j].StartedAt) })
	return results
}

func (r *Runner) Run(ctx context.Context, cfg config.Config, factory Factory) (metrics.Report, error) {
	started := time.Now()
	report := metrics.Report{
		RunID: started.UTC().Format("20060102T150405.000000000Z07:00"), StartedAt: started,
		DeviceCount: cfg.DeviceCount, Errors: map[string]int{},
		Config: metrics.ConfigSummary{Scenario: cfg.Scenario.Name, StartRate: cfg.StartRate,
			RegisterTTL: cfg.RegisterTTL.String(), Keepalive: cfg.Keepalive.String(), PlayRate: cfg.Scenario.PlayRate,
			MaxConcurrent: cfg.Scenario.MaxConcurrent, PlayDuration: cfg.Scenario.PlayDuration.String(), DeviceCount: cfg.DeviceCount},
	}
	if cfg.RunID != "" {
		report.RunID = cfg.RunID
	}
	if r.Metrics == nil {
		r.Metrics = metrics.NewRegistry()
	}
	collector := newResultCollector(r.Metrics)
	devices := make([]*device.Device, 0, cfg.DeviceCount)
	var mu sync.Mutex
	var wg sync.WaitGroup
	interval := time.Duration(0)
	if cfg.StartRate > 0 {
		interval = time.Duration(float64(time.Second) / cfg.StartRate)
	}
	stage := metrics.Stage{Name: "startup", StartedAt: time.Now()}
	for i := 0; i < cfg.DeviceCount; i++ {
		if err := ctx.Err(); err != nil {
			report.Errors["cancelled"]++
			break
		}
		d, err := factory.New(i)
		if err != nil {
			report.Errors["factory"]++
			continue
		}
		devices = append(devices, d)
		collector.RecordDevice(d)
		d.SetEventSink(collector)
		wg.Add(1)
		go func(x *device.Device) {
			defer wg.Done()
			runErr := x.Run(ctx)
			collector.MarkStarted(x.Identity.DeviceID, runErr == nil, runErr)
			if runErr != nil {
				mu.Lock()
				report.Errors["run"]++
				mu.Unlock()
			}
		}(d)
		r.Metrics.Counter("devices_started_total", 1)
		if interval > 0 {
			timer := time.NewTimer(interval)
			select {
			case <-ctx.Done():
				timer.Stop()
			case <-timer.C:
			}
		}
	}
	// Device.Run starts its receive loop asynchronously, so startup completes
	// once all requested devices have been created and launched.
	wg.Wait()
	stage.FinishedAt = time.Now()
	stage.Devices = len(devices)
	stage.Success = len(devices) - report.Errors["run"]
	stage.Failed = report.Errors["run"]
	report.Stages = append(report.Stages, stage)
	playRate, maxConcurrent := r.PlayRate, r.MaxConcurrent
	playDuration, startupGrace := r.PlayDuration, r.StartupGrace
	if playRate == 0 {
		playRate = cfg.Scenario.PlayRate
	}
	if maxConcurrent == 0 {
		maxConcurrent = cfg.Scenario.MaxConcurrent
	}
	if playDuration == 0 {
		playDuration = cfg.Scenario.PlayDuration
	}
	if startupGrace == 0 {
		startupGrace = cfg.Scenario.StartupGrace
	}
	scheduler := &PlayScheduler{Controller: r.PlayController, Rate: playRate, MaxConcurrent: maxConcurrent,
		Duration: playDuration, StartupGrace: startupGrace, Metrics: r.Metrics, Observer: collector}
	playStage := metrics.Stage{Name: "play", StartedAt: time.Now()}
	var schedulerWG sync.WaitGroup
	if scheduler.Controller != nil {
		schedulerWG.Add(1)
		go func() { defer schedulerWG.Done(); scheduler.Run(ctx, devices) }()
	}
	schedulerWG.Wait()
	if scheduler.Controller != nil {
		playStage.FinishedAt = time.Now()
		plays := collector.PlayResults()
		playStage.Devices = len(plays)
		for _, play := range plays {
			if play.StartSuccess {
				playStage.Success++
			} else {
				playStage.Failed++
				report.Errors["play_start"]++
			}
			if play.StopAttempted && !play.StopSuccess {
				report.Errors["play_stop"]++
			}
		}
		report.Stages = append(report.Stages, playStage)
	}
	Cleanup(devices)
	report.FinishedAt = time.Now()
	report.Devices = collector.DeviceResults()
	report.Plays = collector.PlayResults()
	report.Metrics = r.Metrics.Snapshot()
	report.ResourceSamples = append([]monitoring.ResourceSample(nil), r.ResourceSamples...)
	report.Resources = metrics.SummarizeResources(report.ResourceSamples)
	report.Business = businessSummary(report)
	return report, nil
}

func businessSummary(report metrics.Report) metrics.BusinessSummary {
	registerRequests, registerSuccess, catalog, plays, playSuccess, media, stopAttempted, stopSuccess := 0, 0, 0, 0, 0, 0, 0, 0
	for _, d := range report.Devices {
		registerRequests += d.RegisterRequests
		registerSuccess += d.RegisterSuccess
		catalog += d.CatalogSuccess
		media += d.MediaStarted
	}
	for _, p := range report.Plays {
		plays++
		if p.StartSuccess {
			playSuccess++
		}
		if p.StopAttempted {
			stopAttempted++
			if p.StopSuccess {
				stopSuccess++
			}
		}
	}
	rate := func(success, total int) float64 {
		if total == 0 {
			return 1
		}
		return float64(success) / float64(total)
	}
	return metrics.BusinessSummary{RegisterSuccessRate: rate(registerSuccess, registerRequests), CatalogSuccessRate: rate(catalog, len(report.Devices)), PlaySuccessRate: rate(playSuccess, plays), MediaSuccessRate: rate(media, playSuccess), StopSuccessRate: rate(stopSuccess, stopAttempted), ControlLatencyP95: report.Metrics["wvp_control_latency_seconds_p95"], ControlLatencyP99: report.Metrics["wvp_control_latency_seconds_p99"]}
}
