package runner

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
)

type recordingController struct {
	mu     sync.Mutex
	starts int
	stops  int
}

func TestSchedulerDoesNotOverlapSameDevice(t *testing.T) {
	idf, _ := device.NewIdentityFactory("overlap", "loadtest-", "3402")
	id, _ := idf.New(0)
	c := &recordingController{}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	s := &PlayScheduler{Controller: c, Rate: 1000, MaxConcurrent: 10, Duration: time.Second}
	s.Run(ctx, []*device.Device{device.New(id, device.DeviceHooks{})})
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.starts != 1 {
		t.Fatalf("same device had %d overlapping plays", c.starts)
	}
}

func (c *recordingController) Start(context.Context, *device.Device) error {
	c.mu.Lock()
	c.starts++
	c.mu.Unlock()
	return nil
}

func (c *recordingController) Stop(context.Context, *device.Device) error {
	c.mu.Lock()
	c.stops++
	c.mu.Unlock()
	return nil
}

type recordingObserver struct {
	mu      sync.Mutex
	results []metrics.PlayResult
}

func (o *recordingObserver) RecordPlay(result metrics.PlayResult) {
	o.mu.Lock()
	o.results = append(o.results, result)
	o.mu.Unlock()
}

func TestPlaySchedulerRecordsStartAndStop(t *testing.T) {
	factory, err := device.NewIdentityFactory("run", "loadtest-", "3402")
	if err != nil {
		t.Fatal(err)
	}
	id, err := factory.New(0)
	if err != nil {
		t.Fatal(err)
	}
	target := device.New(id, device.DeviceHooks{})
	controller := &recordingController{}
	observer := &recordingObserver{}
	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()
	scheduler := &PlayScheduler{Controller: controller, Rate: 100, Duration: time.Millisecond, Metrics: metrics.NewRegistry(), Observer: observer}
	scheduler.Run(ctx, []*device.Device{target})
	observer.mu.Lock()
	defer observer.mu.Unlock()
	if len(observer.results) == 0 {
		t.Fatal("expected at least one play result")
	}
	result := observer.results[0]
	if !result.StartSuccess || !result.StopAttempted || !result.StopSuccess {
		t.Fatalf("unexpected result: %+v", result)
	}
	if result.StartLatencySeconds < 0 || result.StopLatencySeconds < 0 {
		t.Fatalf("invalid latency: %+v", result)
	}
}
