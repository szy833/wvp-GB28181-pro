package scenario

import (
	"context"
	"time"
	"github.com/shizy/gb28181-load-test/internal/device"
)

type builtin struct { name string; policy PlayPolicy; duration time.Duration }
func (s builtin) Name() string { return s.name }; func (s builtin) PlayPolicy() PlayPolicy { return s.policy }
func (s builtin) Run(ctx context.Context, d *device.Device) error { if s.duration <= 0 { s.duration = time.Minute }; timer := time.NewTimer(s.duration); defer timer.Stop(); select { case <-timer.C: return d.StopPlay(ctx); case <-ctx.Done(): return ctx.Err() } }
func SteadyPlay(duration time.Duration) Scenario { return builtin{name:"steady-play", policy:PolicyContinuous, duration:duration} }
func NoMediaTimeout(duration time.Duration) Scenario { return builtin{name:"no-media-timeout", policy:PolicyNoMedia, duration:duration} }
func DeviceBye(duration time.Duration) Scenario { return builtin{name:"device-bye", policy:PolicyDeviceBye, duration:duration} }
func ChurnPlay(duration time.Duration) Scenario { return builtin{name:"churn-play", policy:PolicyContinuous, duration:duration} }
