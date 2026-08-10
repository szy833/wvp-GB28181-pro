package device

import (
	"context"
	"github.com/shizy/gb28181-load-test/internal/sip"
	"net"
	"strings"
	"sync"
	"testing"
)

type fakeTransport struct {
	mu   sync.Mutex
	sent [][]byte
}

func (f *fakeTransport) Send(_ context.Context, _ net.Addr, p []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent = append(f.sent, append([]byte(nil), p...))
	return nil
}
func (f *fakeTransport) Receive(ctx context.Context) (sip.Message, error) {
	<-ctx.Done()
	return sip.Message{}, ctx.Err()
}
func (f *fakeTransport) Close() error { return nil }

type fakeSender struct{ starts, stops int }

func (f *fakeSender) Start(context.Context) error { f.starts++; return nil }
func (f *fakeSender) Stop() error                 { f.stops++; return nil }

type eventSink struct{ names []string }

func (s *eventSink) OnDeviceEvent(event DeviceEvent) { s.names = append(s.names, event.Name) }

func TestDeviceHandlesInviteAndStopsOnce(t *testing.T) {
	f, _ := NewIdentityFactory("run", "loadtest-", "3402")
	id, _ := f.New(0)
	tr := &fakeTransport{}
	snd := &fakeSender{}
	d := New(id, DeviceHooks{Transport: tr, Sender: snd})
	if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "INVITE sip:x SIP/2.0", Headers: map[string]string{"call-id": "c1", "via": "v", "from": "f", "to": "t"}}); err != nil {
		t.Fatal(err)
	}
	if d.State() != StatePlaying || len(tr.sent) != 1 || !strings.HasPrefix(string(tr.sent[0]), "SIP/2.0 200") {
		t.Fatal(d.State())
	}
	if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "ACK sip:x SIP/2.0", Headers: map[string]string{}}); err != nil {
		t.Fatal(err)
	}
	_ = d.StopPlay(context.Background())
	_ = d.StopPlay(context.Background())
	if snd.starts != 1 || snd.stops != 1 {
		t.Fatalf("%d %d", snd.starts, snd.stops)
	}
}

func TestDeviceEmitsProtocolEvents(t *testing.T) {
	f, _ := NewIdentityFactory("run", "loadtest-", "3402")
	id, _ := f.New(0)
	sink := &eventSink{}
	d := New(id, DeviceHooks{Transport: &fakeTransport{}, Sender: &fakeSender{}, ServerID: "34020000002000000001", Name: "cam"})
	d.SetEventSink(sink)
	if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "SIP/2.0 200 OK", Headers: map[string]string{"cseq": "1 REGISTER"}}); err != nil {
		t.Fatal(err)
	}
	query := []byte("<Query><CmdType>Catalog</CmdType><SN>1</SN><DeviceID>server</DeviceID></Query>")
	if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "MESSAGE sip:x SIP/2.0", Headers: map[string]string{"via": "v", "from": "f", "to": "t", "call-id": "q", "cseq": "1 MESSAGE"}, Body: query}); err != nil {
		t.Fatal(err)
	}
	if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "INVITE sip:x SIP/2.0", Headers: map[string]string{"call-id": "c1", "via": "v", "from": "f", "to": "t"}}); err != nil {
		t.Fatal(err)
	}
	if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "ACK sip:x SIP/2.0", Headers: map[string]string{}}); err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, name := range sink.names {
		seen[name] = true
	}
	for _, name := range []string{"register_success", "catalog_response", "invite_success", "media_started"} {
		if !seen[name] {
			t.Fatalf("missing %s in %#v", name, sink.names)
		}
	}
}
