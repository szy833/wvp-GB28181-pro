package monitoring

import (
	"context"
	"sync"
	"testing"
	"time"
)

type convergingLister struct {
	mu    sync.Mutex
	calls int
}

func (l *convergingLister) ListStreams(context.Context) (ZLMSnapshot, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.calls++
	n := 1
	if l.calls >= 3 {
		n = 0
	}
	return ZLMSnapshot{Available: true, SampledAt: time.Now(), BusinessStreams: n}, nil
}

func TestFinalSnapshotWaitsForCleanupConvergence(t *testing.T) {
	l := &convergingLister{}
	s := ResourceSampler{ZLM: l, QueryTimeout: time.Second, Interval: time.Second}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var final *ZLMSnapshot
	for sample := range s.Start(ctx, time.Now()) {
		if sample.Metric == "final_streams" {
			final = sample.ZLM
		}
	}
	if final == nil || final.BusinessStreams != 0 {
		t.Fatalf("cleanup checked too early: %+v", final)
	}
}
