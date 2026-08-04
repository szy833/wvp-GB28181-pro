package runner

import (
	"context"
	"sync"
	"time"
	"github.com/shizy/gb28181-load-test/internal/device"
)

type PlayScheduler struct { Controller PlayController; Rate float64; MaxConcurrent int; Duration time.Duration; StartupGrace time.Duration; Metrics interface{ Counter(string, uint64) } }
func (s *PlayScheduler) Run(ctx context.Context, devices []*device.Device) { if s == nil || s.Controller == nil || len(devices) == 0 || s.Rate <= 0 { return }; if s.StartupGrace > 0 { timer := time.NewTimer(s.StartupGrace); select { case <-ctx.Done(): timer.Stop(); return; case <-timer.C: } }; interval := time.Duration(float64(time.Second) / s.Rate); if interval <= 0 { interval = time.Nanosecond }; limit := s.MaxConcurrent; if limit <= 0 { limit = len(devices) }; sem := make(chan struct{}, limit); ticker := time.NewTicker(interval); defer ticker.Stop(); var wg sync.WaitGroup; index := 0; for { select { case <-ctx.Done(): wg.Wait(); return; case <-ticker.C: d := devices[index%len(devices)]; index++; select { case sem <- struct{}{}: default: continue }; wg.Add(1); go func(target *device.Device) { defer wg.Done(); defer func(){ <-sem }(); if s.Metrics != nil { s.Metrics.Counter("play_requests_total", 1) }; if err := s.Controller.Start(ctx, target); err != nil { if s.Metrics != nil { s.Metrics.Counter("play_start_errors_total", 1) }; return }; if s.Duration <= 0 { return }; timer := time.NewTimer(s.Duration); select { case <-ctx.Done(): timer.Stop(); case <-timer.C: if err := s.Controller.Stop(ctx, target); err != nil && s.Metrics != nil { s.Metrics.Counter("play_stop_errors_total", 1) } } }(d) } } }
