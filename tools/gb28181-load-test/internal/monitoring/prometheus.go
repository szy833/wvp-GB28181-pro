package monitoring

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Sample is one Prometheus time series value. Timestamp is kept with
// nanosecond precision so query_range results can be joined to local samples.
type Sample struct {
	Timestamp time.Time         `json:"timestamp"`
	Value     float64           `json:"value"`
	Labels    map[string]string `json:"labels,omitempty"`
}

type QueryResult struct {
	Metric    string   `json:"metric"`
	Samples   []Sample `json:"samples,omitempty"`
	Available bool     `json:"available"`
	Error     string   `json:"error,omitempty"`
}

// HTTPPrometheusClient implements the query_range subset needed by the
// resource sampler. It only sends the configured PromQL query; no credentials
// or report-only secrets are embedded in errors.
type HTTPPrometheusClient struct {
	BaseURL    string
	HTTPClient *http.Client
	Timeout    time.Duration
}

func NewPrometheusClient(baseURL string, timeout time.Duration) *HTTPPrometheusClient {
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	return &HTTPPrometheusClient{BaseURL: strings.TrimRight(baseURL, "/"), Timeout: timeout,
		HTTPClient: &http.Client{Timeout: timeout}}
}

func (c *HTTPPrometheusClient) QueryRange(ctx context.Context, query string, start, end time.Time, step time.Duration) (QueryResult, error) {
	result := QueryResult{Metric: query}
	if c == nil || strings.TrimSpace(c.BaseURL) == "" {
		result.Error = "Prometheus URL is empty"
		return result, fmt.Errorf("prometheus URL is empty")
	}
	if strings.TrimSpace(query) == "" {
		result.Error = "Prometheus query is empty"
		return result, fmt.Errorf("prometheus query is empty")
	}
	endpoint, err := url.Parse(strings.TrimRight(c.BaseURL, "/") + "/api/v1/query_range")
	if err != nil {
		result.Error = "invalid Prometheus URL"
		return result, fmt.Errorf("parse Prometheus URL: %w", err)
	}
	if step <= 0 {
		step = time.Second
	}
	values := endpoint.Query()
	values.Set("query", query)
	values.Set("start", formatPromTime(start))
	values.Set("end", formatPromTime(end))
	values.Set("step", formatStep(step))
	endpoint.RawQuery = values.Encode()

	reqCtx := ctx
	var cancel context.CancelFunc
	if c.Timeout > 0 {
		reqCtx, cancel = context.WithTimeout(ctx, c.Timeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		result.Error = "build Prometheus request failed"
		return result, err
	}
	client := c.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: c.Timeout}
	}
	resp, err := client.Do(req)
	if err != nil {
		result.Error = safePrometheusError(err)
		return result, err
	}
	defer resp.Body.Close()
	body, readErr := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if readErr != nil {
		result.Error = "read Prometheus response failed"
		return result, readErr
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		result.Error = fmt.Sprintf("Prometheus HTTP status %d", resp.StatusCode)
		return result, fmt.Errorf("prometheus HTTP status %d", resp.StatusCode)
	}
	var payload promResponse
	if err := json.Unmarshal(body, &payload); err != nil {
		result.Error = "decode Prometheus response failed"
		return result, fmt.Errorf("decode Prometheus response: %w", err)
	}
	if payload.Status != "success" {
		if payload.Error != "" {
			result.Error = "Prometheus query failed: " + payload.Error
		} else {
			result.Error = "Prometheus query failed"
		}
		return result, fmt.Errorf("prometheus query failed")
	}
	if payload.Data.ResultType != "matrix" && payload.Data.ResultType != "vector" {
		result.Error = "unsupported Prometheus result type"
		return result, fmt.Errorf("unsupported Prometheus result type %q", payload.Data.ResultType)
	}
	for _, item := range payload.Data.Result {
		labels := cloneLabels(item.Metric)
		values := item.Values
		if len(values) == 0 && len(item.Value) > 0 {
			values = [][]json.RawMessage{item.Value}
		}
		for _, pair := range values {
			if len(pair) < 2 {
				continue
			}
			ts, err := rawFloat(pair[0])
			if err != nil {
				continue
			}
			value, err := rawPromValue(pair[1])
			if err != nil {
				continue
			}
			result.Samples = append(result.Samples, Sample{Timestamp: time.Unix(0, int64(ts*float64(time.Second))), Value: value, Labels: labels})
		}
	}
	if len(result.Samples) == 0 {
		result.Error = "Prometheus query returned no samples"
		return result, fmt.Errorf("prometheus query returned no samples")
	}
	result.Available = true
	return result, nil
}

type promResponse struct {
	Status string `json:"status"`
	Error  string `json:"error"`
	Data   struct {
		ResultType string       `json:"resultType"`
		Result     []promSeries `json:"result"`
	} `json:"data"`
}
type promSeries struct {
	Metric map[string]string   `json:"metric"`
	Values [][]json.RawMessage `json:"values"`
	Value  []json.RawMessage   `json:"value"`
}

func rawFloat(raw json.RawMessage) (float64, error) {
	var value float64
	if err := json.Unmarshal(raw, &value); err == nil {
		return value, nil
	}
	var text string
	if err := json.Unmarshal(raw, &text); err != nil {
		return 0, err
	}
	return strconv.ParseFloat(text, 64)
}

func rawPromValue(raw json.RawMessage) (float64, error) {
	var text string
	if err := json.Unmarshal(raw, &text); err == nil {
		return strconv.ParseFloat(text, 64)
	}
	return rawFloat(raw)
}

func formatPromTime(value time.Time) string {
	return strconv.FormatFloat(float64(value.UnixNano())/float64(time.Second), 'f', 6, 64)
}

func formatStep(value time.Duration) string {
	if value%time.Second == 0 {
		return strconv.FormatInt(int64(value/time.Second), 10) + "s"
	}
	return strconv.FormatFloat(value.Seconds(), 'f', 3, 64)
}

func safePrometheusError(err error) string {
	if err == nil {
		return ""
	}
	if errorsIsTimeout(err) {
		return "Prometheus request timeout"
	}
	return "Prometheus request failed"
}

func errorsIsTimeout(err error) bool {
	type timeout interface{ Timeout() bool }
	t, ok := err.(timeout)
	return ok && t.Timeout()
}
