package device

import (
	"context"
	"net"
	"strings"
	"sync"
	"testing"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

type fakeTransport struct { mu sync.Mutex; sent [][]byte }
func (f *fakeTransport) Send(_ context.Context, _ net.Addr, p []byte) error { f.mu.Lock(); defer f.mu.Unlock(); f.sent = append(f.sent, append([]byte(nil), p...)); return nil }
func (f *fakeTransport) Receive(ctx context.Context) (sip.Message, error) { <-ctx.Done(); return sip.Message{}, ctx.Err() }
func (f *fakeTransport) Close() error { return nil }
type fakeSender struct { starts, stops int }
func (f *fakeSender) Start(context.Context) error { f.starts++; return nil }; func (f *fakeSender) Stop() error { f.stops++; return nil }

func TestDeviceHandlesInviteAndStopsOnce(t *testing.T) { f, _ := NewIdentityFactory("run", "loadtest-", "3402"); id, _ := f.New(0); tr := &fakeTransport{}; snd := &fakeSender{}; d := New(id, DeviceHooks{Transport: tr, Sender: snd}); if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "INVITE sip:x SIP/2.0", Headers: map[string]string{"call-id":"c1", "via":"v", "from":"f", "to":"t"}}); err != nil { t.Fatal(err) }; if d.State() != StatePlaying || len(tr.sent) != 1 || !strings.HasPrefix(string(tr.sent[0]), "SIP/2.0 200") { t.Fatal(d.State()) }; if err := d.HandleMessage(context.Background(), sip.Message{StartLine: "ACK sip:x SIP/2.0", Headers: map[string]string{}}); err != nil { t.Fatal(err) }; _ = d.StopPlay(context.Background()); _ = d.StopPlay(context.Background()); if snd.starts != 1 || snd.stops != 1 { t.Fatalf("%d %d", snd.starts, snd.stops) } }
