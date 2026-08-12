package metrics

import (
	"encoding/csv"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/shizy/gb28181-load-test/internal/monitoring"
)

func SummarizeResources(samples []monitoring.ResourceSample) map[string]ResourceSummary {
	values := make(map[string][]float64)
	available := make(map[string]bool)
	errors := make(map[string]string)
	seen := make(map[string]struct{})
	for _, sample := range samples {
		seen[sample.Metric] = struct{}{}
		if !sample.Available {
			if sample.Error != "" {
				errors[sample.Metric] = sample.Error
			}
			continue
		}
		available[sample.Metric] = true
		values[sample.Metric] = append(values[sample.Metric], sample.Value)
	}
	result := make(map[string]ResourceSummary)
	keys := make(map[string]struct{})
	for key := range values {
		keys[key] = struct{}{}
	}
	for key := range errors {
		keys[key] = struct{}{}
	}
	for key := range seen {
		keys[key] = struct{}{}
	}
	for key := range keys {
		vals := values[key]
		summary := ResourceSummary{Available: available[key], Samples: len(vals), Error: errors[key]}
		if len(vals) > 0 {
			sort.Float64s(vals)
			var sum float64
			for _, value := range vals {
				sum += value
			}
			summary.Avg = sum / float64(len(vals))
			summary.P95 = percentile(vals, .95)
			summary.Max = vals[len(vals)-1]
		}
		result[key] = summary
	}
	return result
}

func percentile(values []float64, ratio float64) float64 {
	if len(values) == 0 {
		return 0
	}
	index := int(float64(len(values)-1) * ratio)
	if index < 0 {
		index = 0
	}
	if index >= len(values) {
		index = len(values) - 1
	}
	return values[index]
}

func WriteResourceCSV(path string, samples []monitoring.ResourceSample) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	w := csv.NewWriter(f)
	if err := w.Write([]string{"source", "metric", "timestamp", "value", "labels", "available", "error"}); err != nil {
		return err
	}
	for _, sample := range samples {
		labels := make([]string, 0, len(sample.Labels))
		for key, value := range sample.Labels {
			labels = append(labels, key+"="+value)
		}
		sort.Strings(labels)
		value := ""
		if sample.Available {
			value = fmt.Sprintf("%.6f", sample.Value)
		}
		if err := w.Write([]string{sample.Source, sample.Metric, sample.Timestamp.Format(time.RFC3339Nano), value, strings.Join(labels, ","), fmt.Sprint(sample.Available), sample.Error}); err != nil {
			return err
		}
	}
	w.Flush()
	return w.Error()
}
