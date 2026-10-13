package runner

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/shizy/gb28181-load-test/internal/device"
)

type confirmationSink struct{ confirmed bool }

func (s *confirmationSink) OnDeviceEvent(event device.DeviceEvent) {
	if event.Name == "media_confirmed" {
		s.confirmed = true
	}
}

func TestControlRejectsNonJSONSuccess(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("<html>login</html>")) }))
	defer s.Close()
	c := HTTPPlayController{BaseURL: s.URL}
	if err := c.Start(context.Background(), device.New(device.Identity{DeviceID: "d", ChannelID: "c"}, device.DeviceHooks{})); err == nil {
		t.Fatal("HTML 200 must not count as successful play")
	}
}

func TestControlConfirmsMediaOnlyForMatchingStream(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"code":0,"data":{"app":"rtp","stream":"d_c"}}`))
	}))
	defer s.Close()
	d := device.New(device.Identity{DeviceID: "d", ChannelID: "c"}, device.DeviceHooks{})
	sink := &confirmationSink{}
	d.SetEventSink(sink)
	c := HTTPPlayController{BaseURL: s.URL}
	if err := c.Start(context.Background(), d); err != nil {
		t.Fatal(err)
	}
	if !sink.confirmed {
		t.Fatal("WVP media identity not recorded")
	}
}

func TestControlTracksGenerationMediaFromBoundRequest(t *testing.T) {
	const stream = "gb_0123456789abcdef0123456789abcdef"
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"code":0,"data":{"app":"rtp","stream":"` + stream + `"}}`))
	}))
	defer s.Close()
	tracked := ""
	c := HTTPPlayController{BaseURL: s.URL, OnMedia: func(app, value string) { tracked = app + "/" + value }}
	if err := c.Start(context.Background(), device.New(device.Identity{DeviceID: "d", ChannelID: "c"}, device.DeviceHooks{})); err != nil {
		t.Fatal(err)
	}
	if tracked != "rtp/"+stream {
		t.Fatal("actual generation was not added to cleanup scope")
	}
}

func TestControlStopCarriesTheExactStartedGeneration(t *testing.T) {
	const stream = "gb_0123456789abcdef0123456789abcdef"
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/play/stop/d/c" && r.URL.Query().Get("stream") != stream {
			t.Error("stop did not fence the generation returned by start")
		}
		_, _ = w.Write([]byte(`{"code":0,"data":{"app":"rtp","stream":"` + stream + `"}}`))
	}))
	defer s.Close()
	c := HTTPPlayController{BaseURL: s.URL}
	d := device.New(device.Identity{DeviceID: "d", ChannelID: "c"}, device.DeviceHooks{})
	if err := c.Start(context.Background(), d); err != nil {
		t.Fatal(err)
	}
	if err := c.Stop(context.Background(), d); err != nil {
		t.Fatal(err)
	}
}

func TestControlRejectsWrongOrMissingMediaIdentity(t *testing.T) {
	for _, value := range []string{"", "other_camera", "gb_bad"} {
		t.Run(value, func(t *testing.T) {
			s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = w.Write([]byte(`{"code":0,"data":{"app":"rtp","stream":"` + value + `"}}`))
			}))
			defer s.Close()
			c := HTTPPlayController{BaseURL: s.URL, OnMedia: func(string, string) { t.Fatal("invalid media was tracked") }}
			if err := c.Start(context.Background(), device.New(device.Identity{DeviceID: "d", ChannelID: "c"}, device.DeviceHooks{})); err == nil {
				t.Fatal("invalid media counted as successful play")
			}
		})
	}
}
