package monitoring

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPrometheusQueryRangeParsesMatrixAndEscapesParameters(t *testing.T) {
	var request *http.Request
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		request = r
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"job":"wvp"},"values":[[1700000000,"1.25"],[1700000015,"2"]]}]}}`))
	}))
	defer server.Close()

	client := NewPrometheusClient(server.URL, 2*time.Second)
	result, err := client.QueryRange(context.Background(), `rate(http_requests_total{job="wvp"}[1m])`, time.Unix(1700000000, 0), time.Unix(1700000015, 0), 15*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Available || result.Metric == "" || len(result.Samples) != 2 {
		t.Fatalf("unexpected result: %+v", result)
	}
	if result.Samples[1].Value != 2 || result.Samples[1].Labels["job"] != "wvp" {
		t.Fatalf("unexpected samples: %+v", result.Samples)
	}
	if request == nil || request.URL.Path != "/api/v1/query_range" || request.URL.Query().Get("step") != "15s" || request.URL.Query().Get("query") == "" {
		t.Fatalf("unexpected request: %v", request.URL)
	}
}

func TestPrometheusQueryRangeParsesVector(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"status":"success","data":{"resultType":"vector","result":[{"metric":{"instance":"a"},"value":[1700000000,"3.5"]}]}}`))
	}))
	defer server.Close()
	result, err := NewPrometheusClient(server.URL, time.Second).QueryRange(context.Background(), "up", time.Now().Add(-time.Minute), time.Now(), time.Second)
	if err != nil || !result.Available || len(result.Samples) != 1 || result.Samples[0].Value != 3.5 {
		t.Fatalf("unexpected result=%+v err=%v", result, err)
	}
}

func TestPrometheusQueryRangeReportsHTTPAndEmptyErrorsWithoutSecrets(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.RawQuery, "secret") {
			t.Fatal("secret must not be sent by Prometheus client")
		}
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte("upstream failed"))
	}))
	defer server.Close()
	result, err := NewPrometheusClient(server.URL, time.Second).QueryRange(context.Background(), "up", time.Now().Add(-time.Minute), time.Now(), time.Second)
	if err == nil || result.Available || !strings.Contains(result.Error, "HTTP status 502") {
		t.Fatalf("unexpected error result=%+v err=%v", result, err)
	}
}

func TestPrometheusQueryRangeHonorsContextTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	result, err := NewPrometheusClient(server.URL, time.Second).QueryRange(ctx, "up", time.Now().Add(-time.Minute), time.Now(), time.Second)
	if err == nil || result.Available || result.Error == "" {
		t.Fatalf("expected timeout result=%+v err=%v", result, err)
	}
}
