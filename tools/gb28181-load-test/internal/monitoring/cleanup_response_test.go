package monitoring

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCleanupResponseContracts(t *testing.T) {
	for _, tc := range []struct {
		name, body string
		listeners  bool
		want       int
		wantErr    bool
	}{
		{"zlm-empty-omitted-data", `{"code":0}`, true, 0, false},
		{"zlm-empty-array", `{"code":0,"data":[]}`, true, 0, false},
		{"zlm-scoped", `{"code":0,"data":[{"stream_id":"0BEBE6C5"},{"stream_id":"AAAAAAAA"}]}`, true, 1, false},
		{"zlm-error", `{"code":-1}`, true, 0, true},
		{"zlm-no-code", `{}`, true, 0, true},
		{"zlm-null", `{"code":0,"data":null}`, true, 0, true},
		{"zlm-object", `{"code":0,"data":{}}`, true, 0, true},
		{"zlm-invalid-entry", `{"code":0,"data":[{}]}`, true, 0, true},
		{"wvp-nested-empty", `{"code":0,"data":{"data":[],"count":0}}`, false, 0, false},
		{"wvp-nested-scoped", `{"code":0,"data":{"data":[{"deviceId":"d"},{"deviceId":"real"}],"count":2}}`, false, 1, false},
		{"wvp-legacy-array", `{"data":[{"deviceId":"d"},{"platformId":"p"}]}`, false, 1, false},
		{"wvp-legacy-count", `{"data":[],"count":0}`, false, 0, false},
		{"wvp-platform-stream", `{"code":0,"data":[{"deviceId":null,"streamId":"real_stream"}]}`, false, 0, false},
		{"wvp-stream-scope", `{"code":0,"data":[{"deviceId":null,"streamId":"d_c"}]}`, false, 1, false},
		{"wvp-null-code", `{"code":null,"data":[]}`, false, 0, true},
		{"wvp-error", `{"code":401,"data":[]}`, false, 0, true},
		{"wvp-missing", `{"code":0}`, false, 0, true},
		{"wvp-null", `{"code":0,"data":null}`, false, 0, true},
		{"wvp-object", `{"code":0,"data":{}}`, false, 0, true},
		{"wvp-nested-null", `{"code":0,"data":{"data":null,"count":0}}`, false, 0, true},
		{"wvp-nested-missing-count", `{"code":0,"data":{"data":[]}}`, false, 0, true},
		{"wvp-inconsistent-count", `{"code":0,"data":{"data":[],"count":3}}`, false, 0, true},
		{"wvp-invalid-entry", `{"code":0,"data":[{}]}`, false, 0, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = w.Write([]byte(tc.body))
			}))
			defer server.Close()
			scope := NewResourceScope()
			scope.AddDevice("d", "c")
			scope.AddSSRC(200009413)
			var n int
			var err error
			if tc.listeners {
				client := ScopedZLM{Client: NewZLMClient(server.URL, "secret", "n", time.Second), Scope: scope}
				n, err = client.CountListeners(context.Background())
			} else {
				client := WVPClient{BaseURL: server.URL, Timeout: time.Second, Scope: scope}
				n, err = client.CountSessions(context.Background())
			}
			if (err != nil) != tc.wantErr || !tc.wantErr && n != tc.want {
				t.Fatalf("count=%d error=%v, want count=%d error=%v", n, err, tc.want, tc.wantErr)
			}
		})
	}
}

func TestCleanupLiveEmptyResponseConverges(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/index/api/getMediaList":
			_, _ = w.Write([]byte(`{"code":0,"data":[]}`))
		case "/index/api/listRtpServer":
			_, _ = w.Write([]byte(`{"code":0}`))
		case "/api/play/ssrc":
			_, _ = w.Write([]byte(`{"code":0,"data":{"data":[],"count":0}}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	scope := NewResourceScope()
	poller := CleanupPoller{
		ZLM: &ScopedZLM{Client: NewZLMClient(server.URL, "secret", "n", time.Second), Scope: scope},
		WVP: &WVPClient{BaseURL: server.URL, Timeout: time.Second, Scope: scope}, Interval: time.Millisecond,
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	snapshot, err := poller.Wait(ctx)
	if err != nil || snapshot.CleanupTimedOut || !snapshot.ListenersAvailable || !snapshot.SessionsAvailable || len(snapshot.CleanupErrors) != 0 {
		t.Fatalf("valid live response did not converge: %+v, error=%v", snapshot, err)
	}
}
