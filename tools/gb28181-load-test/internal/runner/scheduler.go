package runner

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
)

type playMetrics interface {
	Counter(string, uint64)
	Observe(string, float64)
}

type PlayObserver interface {
	RecordPlay(metrics.PlayResult)
}

type PlayScheduler struct {
	Controller    PlayController
	Rate          float64
	MaxConcurrent int
	Duration      time.Duration
	StartupGrace  time.Duration
	Metrics       playMetrics
	Observer      PlayObserver
	sequence      uint64
}

func (s *PlayScheduler) Run(ctx context.Context, devices []*device.Device) {
	if s == nil || s.Controller == nil || len(devices) == 0 || s.Rate <= 0 {
		return
	}
	if s.StartupGrace > 0 {
		timer := time.NewTimer(s.StartupGrace)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
	interval := time.Duration(float64(time.Second) / s.Rate)
	if interval <= 0 {
		interval = time.Nanosecond
	}
	limit := s.MaxConcurrent
	if limit <= 0 {
		limit = len(devices)
	}
	sem := make(chan struct{}, limit)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	var wg sync.WaitGroup
	index := 0
	for {
		select {
		case <-ctx.Done():
			wg.Wait()
			return
		case <-ticker.C:
			d := devices[index%len(devices)]
			index++
			select {
			case sem <- struct{}{}:
			default:
				continue
			}
			wg.Add(1)
			go s.runOne(ctx, &wg, sem, d)
		}
	}
}

func (s *PlayScheduler) runOne(ctx context.Context, wg *sync.WaitGroup, sem chan struct{}, target *device.Device) {
	defer wg.Done()
	defer func() { <-sem }()
	started := time.Now()
	result := metrics.PlayResult{
		ID:        fmt.Sprintf("play-%d", atomic.AddUint64(&s.sequence, 1)),
		DeviceID:  target.Identity.DeviceID,
		ChannelID: target.Identity.ChannelID,
		StartedAt: started,
	}
	if s.Metrics != nil {
		s.Metrics.Counter("play_requests_total", 1)
	}
	if err := s.Controller.Start(ctx, target); err != nil {
		result.StartError = err.Error()
		if s.Metrics != nil {
			s.Metrics.Counter("play_start_errors_total", 1)
		}
	} else {
		result.StartSuccess = true
		if s.Metrics != nil {
			s.Metrics.Counter("play_start_success_total", 1)
		}
	}
	result.StartLatencySeconds = time.Since(started).Seconds()
	if result.StartSuccess && s.Duration > 0 {
		result.StopAttempted = true
		timer := time.NewTimer(s.Duration)
		select {
		case <-ctx.Done():
			timer.Stop()
			result.StopAttempted = false
		case <-timer.C:
			stopStarted := time.Now()
			result.StopStartedAt = stopStarted
			if err := s.Controller.Stop(ctx, target); err != nil {
				result.StopError = err.Error()
				if s.Metrics != nil {
					s.Metrics.Counter("play_stop_errors_total", 1)
				}
			} else {
				result.StopSuccess = true
				if s.Metrics != nil {
					s.Metrics.Counter("play_stop_success_total", 1)
				}
			}
			result.StopLatencySeconds = time.Since(stopStarted).Seconds()
			if s.Metrics != nil {
				s.Metrics.Observe("stop_latency_seconds", result.StopLatencySeconds)
			}
		}
	}
	result.FinishedAt = time.Now()
	if s.Observer != nil {
		s.Observer.RecordPlay(result)
	}
}
