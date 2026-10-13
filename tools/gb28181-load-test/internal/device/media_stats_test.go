package device

import (
	"context"
	"testing"
	"time"

	"github.com/shizy/gb28181-load-test/internal/rtp"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

type measuredSender struct{ fakeSender }

func (*measuredSender) Stats() rtp.SenderStats {
	return rtp.SenderStats{Packets: 10, Bytes: 1000, Errors: 1}
}

func TestPendingInviteDoesNotRecountPreviousSender(t *testing.T) {
	sink := &statSink{}
	d := New(Identity{DeviceID: "d"}, DeviceHooks{Transport: &fakeTransport{}, Sender: &measuredSender{}})
	d.SetEventSink(sink)
	d.emitMediaStats()
	sink.events = nil
	d.Hooks.SenderFactory = func(sip.SDPResponse) (MediaSender, string, error) {
		// The periodic collector can run while the next sender is being built.
		d.emitMediaStats()
		return &measuredSender{}, "", nil
	}
	invite := inviteMessage()
	invite.Body = []byte("c=IN IP4 127.0.0.1\r\nm=video 41000 RTP/AVP 96\r\ny=0200000001\r\n")
	if err := d.HandleMessage(context.Background(), invite); err != nil {
		t.Fatal(err)
	}
	for _, e := range sink.events {
		if e.Name == "media_stats" {
			t.Fatal("previous sender recounted while next sender was pending")
		}
	}
	d.emitMediaStats()
	var packets uint64
	for _, e := range sink.events {
		packets += e.Packets
	}
	if packets != 10 {
		t.Fatalf("recounted previous sender: got %d new packets, want 10", packets)
	}
}

type statSink struct{ events []DeviceEvent }

func (s *statSink) OnDeviceEvent(e DeviceEvent) { s.events = append(s.events, e) }

func TestStoppingDeviceEmitsFinalMediaCounters(t *testing.T) {
	s := &statSink{}
	d := New(Identity{DeviceID: "d"}, DeviceHooks{Transport: &fakeTransport{}, Sender: &measuredSender{}})
	d.SetEventSink(s)
	if err := d.HandleMessage(context.Background(), inviteMessage()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
	defer cancel()
	_ = d.StopPlay(ctx)
	for _, e := range s.events {
		if e.Name == "media_stats" {
			return
		}
	}
	t.Fatal("no actual media counters in report")
}
