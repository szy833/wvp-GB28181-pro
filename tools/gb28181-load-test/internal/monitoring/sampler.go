package monitoring

import (
	"context"
	"sort"
	"strings"
	"time"
)

// ResourceSample is the normalized row consumed by resource reports. A row
// can represent a Prometheus metric or one of the ZLM stream counters.
type ResourceSample struct {
	Source    string            `json:"source"`
	Metric    string            `json:"metric"`
	Timestamp time.Time         `json:"timestamp"`
	Value     float64           `json:"value"`
	Labels    map[string]string `json:"labels,omitempty"`
	Available bool              `json:"available"`
	Error     string            `json:"error,omitempty"`
	// ZLM is populated for the synthetic zlm snapshot row. It is omitted for
	// Prometheus rows and lets the report retain residual stream identifiers.
	ZLM *ZLMSnapshot `json:"zlm,omitempty"`
}

// PrometheusClient is intentionally small so tests and alternate collectors
// can provide a fake without depending on the HTTP implementation.
type PrometheusClient interface {
	QueryRange(ctx context.Context, query string, start, end time.Time, step time.Duration) (QueryResult, error)
}

// ZLMStreamLister permits the sampler to use either the concrete ZLM client or
// a test double.
type ZLMStreamLister interface {
	ListStreams(ctx context.Context) (ZLMSnapshot, error)
}

// ResourceSampler periodically samples Prometheus and ZLM. All requests use a
// per-round timeout and the output channel has a hard upper bound.
type ResourceSampler struct {
	Interval     time.Duration
	QueryTimeout time.Duration
	Step         time.Duration
	Prometheus   PrometheusClient
	Queries      map[string]string
	ZLM          ZLMStreamLister
	MaxSamples   int
	BufferSize   int
}

func (s *ResourceSampler) normalized() (interval, timeout, step time.Duration, max, buffer int) {
	interval = s.Interval
	if interval <= 0 {
		interval = 15 * time.Second
	}
	timeout = s.QueryTimeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	step = s.Step
	if step <= 0 {
		step = interval
	}
	max = s.MaxSamples
	if max <= 0 {
		max = 10000
	}
	buffer = s.BufferSize
	if buffer <= 0 || buffer > max {
		buffer = minInt(max, 256)
	}
	return
}

// Start starts sampling after start (which is used as the first query's
// lower bound). It always performs one final ZLM snapshot when ctx ends.
func (s *ResourceSampler) Start(ctx context.Context, start time.Time) <-chan ResourceSample {
	interval, timeout, step, max, buffer := s.normalized()
	out := make(chan ResourceSample, buffer)
	go func() {
		defer close(out)
		count := 0
		emit := func(sample ResourceSample) bool {
			if count >= max {
				return false
			}
			// A full output channel must not stop SIP/play scheduling. Drop the
			// oldest buffered row before writing the current one.
			select {
			case out <- sample:
				count++
				return true
			default:
				select {
				case <-out:
				default:
				}
				select {
				case out <- sample:
					count++
					return true
				default:
					return false
				}
			}
		}
		// Keep one slot available for the final ZLM residual-stream check.
		emitRegular := func(sample ResourceSample) bool {
			if count >= max-1 {
				return false
			}
			return emit(sample)
		}
		last := start
		sampleRound := func() {
			now := time.Now()
			if now.Before(last) {
				last = now.Add(-interval)
			}
			from := last
			if from.IsZero() {
				from = now.Add(-interval)
			}
			last = now
			roundCtx, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()
			if s.Prometheus != nil {
				keys := sortedQueryKeys(s.Queries)
				for _, name := range keys {
					query := s.Queries[name]
					result, err := s.Prometheus.QueryRange(roundCtx, query, from, now, step)
					if err != nil || !result.Available || len(result.Samples) == 0 {
						errText := result.Error
						if errText == "" && err != nil {
							errText = "Prometheus query failed"
						}
						emitRegular(ResourceSample{Source: "prometheus", Metric: name, Timestamp: now, Available: false, Error: errText})
						continue
					}
					for _, latest := range latestSamples(result.Samples) {
						emitRegular(ResourceSample{Source: "prometheus", Metric: name, Timestamp: latest.Timestamp,
							Value: latest.Value, Labels: cloneLabels(latest.Labels), Available: true})
					}
				}
			}
			if s.ZLM != nil {
				snapshot, err := s.ZLM.ListStreams(roundCtx)
				if err != nil && snapshot.Error == "" {
					snapshot.Error = "ZLM query failed"
				}
				// Keep the complete snapshot as well as scalar rows for CSV and
				// aggregate calculations.
				snapshotCopy := snapshot
				// Keep detailed stream identifiers only in the final snapshot; regular
				// samples retain counters without multiplying report memory by stream count.
				snapshotCopy.Streams = nil
				emitRegular(ResourceSample{Source: "zlm", Metric: "streams", Timestamp: snapshot.SampledAt,
					Value: float64(snapshot.TotalStreams), Available: snapshot.Available, Error: snapshot.Error, ZLM: &snapshotCopy})
				emitRegular(ResourceSample{Source: "zlm", Metric: "rtp_streams", Timestamp: snapshot.SampledAt,
					Value: float64(snapshot.RTPStreams), Available: snapshot.Available, Error: snapshot.Error})
				emitRegular(ResourceSample{Source: "zlm", Metric: "business_streams", Timestamp: snapshot.SampledAt,
					Value: float64(snapshot.BusinessStreams), Available: snapshot.Available, Error: snapshot.Error})
			}
		}

		// Use a timer rather than ticker so a slow round cannot create an
		// unbounded backlog of rounds.
		timer := time.NewTimer(0)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				// A final snapshot is useful for residual stream checks, but do
				// not make shutdown wait for an unavailable Prometheus endpoint.
				if s.ZLM != nil {
					finalCtx, cancel := context.WithTimeout(context.Background(), timeout)
					snapshot, err := s.ZLM.ListStreams(finalCtx)
					cancel()
					if err != nil && snapshot.Error == "" {
						snapshot.Error = "ZLM final query failed"
					}
					copy := snapshot
					emit(ResourceSample{Source: "zlm", Metric: "final_streams", Timestamp: snapshot.SampledAt,
						Value: float64(snapshot.BusinessStreams), Available: snapshot.Available, Error: snapshot.Error, ZLM: &copy})
				}
				return
			case <-timer.C:
				sampleRound()
				timer.Reset(interval)
			}
		}
	}()
	return out
}

func sortedQueryKeys(queries map[string]string) []string {
	keys := make([]string, 0, len(queries))
	for key := range queries {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func latestSample(samples []Sample) Sample {
	latest := samples[0]
	for _, sample := range samples[1:] {
		if sample.Timestamp.After(latest.Timestamp) {
			latest = sample
		}
	}
	return latest
}

func latestSamples(samples []Sample) []Sample {
	latest := make(map[string]Sample)
	for _, sample := range samples {
		key := labelsKey(sample.Labels)
		current, ok := latest[key]
		if !ok || sample.Timestamp.After(current.Timestamp) {
			latest[key] = sample
		}
	}
	result := make([]Sample, 0, len(latest))
	for _, sample := range latest {
		result = append(result, sample)
	}
	sort.Slice(result, func(i, j int) bool { return labelsKey(result[i].Labels) < labelsKey(result[j].Labels) })
	return result
}

func labelsKey(labels map[string]string) string {
	if len(labels) == 0 {
		return ""
	}
	keys := make([]string, 0, len(labels))
	for key := range labels {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	var builder strings.Builder
	for _, key := range keys {
		builder.WriteString(key)
		builder.WriteByte('=')
		builder.WriteString(labels[key])
		builder.WriteByte('\x00')
	}
	return builder.String()
}

func cloneLabels(labels map[string]string) map[string]string {
	if len(labels) == 0 {
		return nil
	}
	copy := make(map[string]string, len(labels))
	for key, value := range labels {
		copy[key] = value
	}
	return copy
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
