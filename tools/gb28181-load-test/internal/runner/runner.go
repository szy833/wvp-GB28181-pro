package runner

import (
	"context"
	"sync"
	"time"

	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
)

type Factory interface { New(int) (*device.Device, error) }
type Runner struct { Metrics *metrics.Registry; PlayController PlayController; PlayRate float64; MaxConcurrent int; PlayDuration time.Duration; StartupGrace time.Duration }

func (r *Runner) Run(ctx context.Context, cfg config.Config, factory Factory) (metrics.Report, error) {
	started := time.Now(); report := metrics.Report{RunID: cfg.RunID, StartedAt: started, DeviceCount: cfg.DeviceCount, Errors: map[string]int{}}
	if r.Metrics == nil { r.Metrics = metrics.NewRegistry() }
	devices := make([]*device.Device, 0, cfg.DeviceCount); var mu sync.Mutex; var wg sync.WaitGroup
	interval := time.Duration(0); if cfg.StartRate > 0 { interval = time.Duration(float64(time.Second) / cfg.StartRate) }
	stage := metrics.Stage{Name: "startup", StartedAt: time.Now()}
	for i := 0; i < cfg.DeviceCount; i++ {
		if err := ctx.Err(); err != nil { report.Errors["cancelled"]++; break }
		d, err := factory.New(i); if err != nil { report.Errors["factory"]++; continue }; devices = append(devices, d)
		wg.Add(1); go func(x *device.Device) { defer wg.Done(); if err := x.Run(ctx); err != nil { mu.Lock(); report.Errors["run"]++; mu.Unlock() } }(d)
		r.Metrics.Counter("devices_started_total", 1)
		if interval > 0 { timer := time.NewTimer(interval); select { case <-ctx.Done(): timer.Stop(); case <-timer.C: } }
	}
	scheduler := &PlayScheduler{Controller: r.PlayController, Rate: r.PlayRate, MaxConcurrent: r.MaxConcurrent, Duration: r.PlayDuration, StartupGrace: r.StartupGrace, Metrics: r.Metrics}
	var schedulerWG sync.WaitGroup; if scheduler.Controller != nil { schedulerWG.Add(1); go func(){ defer schedulerWG.Done(); scheduler.Run(ctx, devices) }() }
	wg.Wait(); schedulerWG.Wait(); stage.FinishedAt = time.Now(); stage.Devices = len(devices); stage.Success = len(devices) - report.Errors["run"]; stage.Failed = report.Errors["run"]; report.Stages = append(report.Stages, stage)
	Cleanup(devices); report.FinishedAt = time.Now(); report.Metrics = r.Metrics.Snapshot(); return report, nil
}
