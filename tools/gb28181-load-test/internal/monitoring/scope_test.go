package monitoring

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestScopedCleanupIgnoresRealCameraAndWaitsForTestResources(t *testing.T) {
	var rounds atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/index/api/getMediaList":
			if rounds.Add(1) < 3 {
				_, _ = w.Write([]byte(`{"code":0,"data":[{"app":"rtp","stream":"d_c","schema":"rtsp"},{"app":"rtp","stream":"real_camera","schema":"rtsp"}]}`))
			} else {
				_, _ = w.Write([]byte(`{"code":0,"data":[{"app":"rtp","stream":"real_camera","schema":"rtsp"}]}`))
			}
		case "/index/api/listRtpServer":
			if rounds.Load() < 3 {
				_, _ = w.Write([]byte(`{"code":0,"data":[{"stream_id":"0BEBE6C5"},{"stream_id":"AAAAAAAA"}]}`))
			} else {
				_, _ = w.Write([]byte(`{"code":0,"data":[{"stream_id":"AAAAAAAA"}]}`))
			}
		case "/api/play/ssrc":
			if rounds.Load() < 3 {
				_, _ = w.Write([]byte(`{"data":[{"deviceId":"d"},{"deviceId":"real"}]}`))
			} else {
				_, _ = w.Write([]byte(`{"data":[{"deviceId":"real"}]}`))
			}
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	scope := NewResourceScope()
	scope.AddDevice("d", "c")
	scope.AddSSRC(200009413)
	p := CleanupPoller{ZLM: &ScopedZLM{Client: NewZLMClient(server.URL, "secret", "node", time.Second), Scope: scope}, WVP: &WVPClient{BaseURL: server.URL, Timeout: time.Second, Scope: scope}, Interval: time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	snapshot, err := p.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.CleanupTimedOut || snapshot.BusinessStreams != 0 || snapshot.ResidualListeners != 0 || snapshot.ResidualSessions != 0 || !snapshot.ScopeFiltered || !snapshot.ListenersAvailable || !snapshot.SessionsAvailable {
		t.Fatalf("bad cleanup result: %+v", snapshot)
	}
	if rounds.Load() < 4 || snapshot.NodeTotalStreams != 1 {
		t.Fatalf("did not wait for two scoped zero rounds: %+v", snapshot)
	}
}

func TestCleanupMissingAPIsNeverMeansZeroConfirmed(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/index/api/getMediaList" {
			_, _ = w.Write([]byte(`{"code":0,"data":[]}`))
			return
		}
		http.NotFound(w, r)
	}))
	defer s.Close()
	p := CleanupPoller{ZLM: &ScopedZLM{Client: NewZLMClient(s.URL, "secret", "n", time.Second), Scope: NewResourceScope()}, Interval: time.Millisecond}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	snapshot, _ := p.Wait(ctx)
	if !snapshot.CleanupTimedOut || snapshot.ListenersAvailable || snapshot.SessionsAvailable || len(snapshot.CleanupErrors) == 0 {
		t.Fatalf("missing API treated as empty: %+v", snapshot)
	}
}

func TestGenerationScopeLearnsOnlyFromOwnedExactOrigin(t *testing.T) {
	scope := NewResourceScope()
	scope.AddSSRC(200009413)
	const generation = "0123456789ABCDEF0123456789ABCDEF"
	listener := "0BEBE6C5_" + generation
	stream := "gb_0123456789abcdef0123456789abcdef"
	if !scope.matchesListener(listener) || scope.matchesListener("AAAAAAAA_"+generation) || scope.matchesListener("0BEBE6C5_bad") {
		t.Fatal("generation listener ownership not respected")
	}
	scope.learnOrigin(ZLMStream{App: "rtp", Stream: stream, OriginURL: "rtp://__defaultVhost__/rtp/AAAAAAAA_" + generation})
	if scope.matchesStream("rtp", stream) {
		t.Fatal("real camera origin leaked into test scope")
	}
	scope.learnOrigin(ZLMStream{App: "rtp", Stream: "gb_ffffffffffffffffffffffffffffffff", OriginURL: "rtp://__defaultVhost__/rtp/" + listener})
	if scope.matchesStream("rtp", "gb_ffffffffffffffffffffffffffffffff") {
		t.Fatal("generation mismatch was accepted")
	}
	scope.learnOrigin(ZLMStream{App: "rtp", Stream: stream, OriginURL: "rtp://__defaultVhost__/rtp/" + listener})
	if !scope.matchesStream("rtp", stream) || scope.matchesStream("other", stream) {
		t.Fatal("lost HTTP response media is not scoped precisely")
	}
}
