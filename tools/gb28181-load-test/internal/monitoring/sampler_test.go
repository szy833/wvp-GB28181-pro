package monitoring

import (
	"context"
	"testing"
	"time"
)

type fakeZLM struct {
	count int
}

func (f *fakeZLM) ListStreams(context.Context) (ZLMSnapshot, error) {
	f.count++
	return ZLMSnapshot{Available: true, SampledAt: time.Now(), TotalStreams: f.count, RTPStreams: f.count, BusinessStreams: f.count}, nil
}

type fakePrometheus struct{}

func (fakePrometheus) QueryRange(context.Context, string, time.Time, time.Time, time.Duration) (QueryResult, error) {
	return QueryResult{Available: true, Samples: []Sample{{Timestamp: time.Now(), Value: 42}}}, nil
}

func TestResourceSamplerSamplesAndFinalZLM(t *testing.T) {
	fake := &fakeZLM{}
	sampler := &ResourceSampler{Interval: time.Millisecond, QueryTimeout: 100 * time.Millisecond, MaxSamples: 10,
		Prometheus: fakePrometheus{}, Queries: map[string]string{"hostCpu": "up"}, ZLM: fake}
	ctx, cancel := context.WithCancel(context.Background())
	out := sampler.Start(ctx, time.Now())
	time.Sleep(8 * time.Millisecond)
	cancel()
	var rows []ResourceSample
	for row := range out {
		rows = append(rows, row)
	}
	if len(rows) == 0 || fake.count == 0 {
		t.Fatalf("expected samples, rows=%d zlm=%d", len(rows), fake.count)
	}
	if rows[len(rows)-1].Source != "zlm" || rows[len(rows)-1].Metric != "final_streams" {
		t.Fatalf("last row is not final ZLM snapshot: %+v", rows[len(rows)-1])
	}
}

func TestResourceSamplerPreservesUnavailableSample(t *testing.T) {
	fake := &fakeUnavailableProm{}
	sampler := &ResourceSampler{Interval: time.Millisecond, QueryTimeout: 100 * time.Millisecond, MaxSamples: 4,
		Prometheus: fake, Queries: map[string]string{"hostCpu": "up"}}
	ctx, cancel := context.WithCancel(context.Background())
	out := sampler.Start(ctx, time.Now())
	time.Sleep(3 * time.Millisecond)
	cancel()
	found := false
	for row := range out {
		if row.Source == "prometheus" && !row.Available {
			found = true
		}
	}
	if !found {
		t.Fatal("expected unavailable Prometheus row")
	}
}

type fakeUnavailableProm struct{}

func (fakeUnavailableProm) QueryRange(context.Context, string, time.Time, time.Time, time.Duration) (QueryResult, error) {
	return QueryResult{Available: false, Error: "endpoint unavailable"}, nil
}
