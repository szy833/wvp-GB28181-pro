package monitoring

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestZLMClientListStreams(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/index/api/getMediaList" {
			t.Fatalf("path = %s", r.URL.Path)
		}
		if r.URL.Query().Get("secret") != "hidden" {
			t.Fatalf("secret was not sent")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"code":0,"data":[
{"schema":"rtp","app":"rtp","stream":"0ABC","originType":3,"originTypeStr":"rtp_push","readerCount":1},
		{"schema":"rtsp","app":"rtp","stream":"device_channel","originType":3,"originTypeStr":"rtp_push"},
		{"schema":"hls","app":"rtp","stream":"device_channel","originType":3,"originTypeStr":"rtp_push"},
{"schema":"hls","app":"live","stream":"other","originType":1}
]}`))
	}))
	defer server.Close()

	client := NewZLMClient(server.URL, "hidden", "node-a", time.Second)
	snapshot, err := client.ListStreams(context.Background())
	if err != nil {
		t.Fatalf("ListStreams() error = %v", err)
	}
	if !snapshot.Available || snapshot.TotalStreams != 4 || snapshot.RTPStreams != 2 || snapshot.BusinessStreams != 2 {
		t.Fatalf("unexpected snapshot: %+v", snapshot)
	}
	if snapshot.APILatencySeconds < 0 || snapshot.HTTPStatus != http.StatusOK {
		t.Fatalf("unexpected timing/status: %+v", snapshot)
	}
}

func TestZLMClientErrorDoesNotExposeSecret(t *testing.T) {
	server := httptest.NewServer(http.NotFoundHandler())
	defer server.Close()
	client := NewZLMClient(server.URL, "very-secret", "node-a", time.Second)
	snapshot, err := client.ListStreams(context.Background())
	if err == nil || snapshot.Available || snapshot.Error == "" {
		t.Fatalf("expected unavailable snapshot: %+v, err=%v", snapshot, err)
	}
	if strings.Contains(snapshot.Error, "very-secret") || strings.Contains(err.Error(), "very-secret") {
		t.Fatalf("secret leaked: snapshot=%q err=%q", snapshot.Error, err)
	}
}

func TestZLMClientTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()
	client := NewZLMClient(server.URL, "", "node-a", 10*time.Millisecond)
	snapshot, err := client.ListStreams(context.Background())
	if err == nil || snapshot.Available || snapshot.Error != "request timeout" {
		t.Fatalf("expected timeout, snapshot=%+v err=%v", snapshot, err)
	}
}
