package device

import (
	"context"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/shizy/gb28181-load-test/internal/sip"
)

type channelSink chan DeviceEvent

func (s channelSink) OnDeviceEvent(e DeviceEvent) { s <- e }

func TestShutdownStillReceivesByeResponseAfterWorkloadCancellation(t *testing.T) {
	server, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	tr, err := sip.NewUDPTransport("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	d := New(Identity{DeviceID: "device", ChannelID: "channel"}, DeviceHooks{Transport: tr, Peer: server.LocalAddr(), Local: tr.LocalAddr().String(), ByeTimeout: time.Second})
	events := make(channelSink, 32)
	d.SetEventSink(events)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := d.Run(ctx); err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	buf := make([]byte, 4096)
	_ = server.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := server.ReadFromUDP(buf); err != nil {
		t.Fatal(err)
	}
	i := inviteMessage()
	i.Headers["contact"] = "<sip:server@" + server.LocalAddr().String() + ">"
	payload := fmt.Sprintf("%s\r\nVia: %s\r\nFrom: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: %s\r\nContact: %s\r\nContent-Length: 0\r\n\r\n", i.StartLine, i.Headers["via"], i.Headers["from"], i.Headers["to"], i.Headers["call-id"], i.Headers["cseq"], i.Headers["contact"])
	if _, err := server.WriteToUDP([]byte(payload), tr.LocalAddr().(*net.UDPAddr)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := server.ReadFromUDP(buf); err != nil {
		t.Fatal(err)
	}
	cancel()
	n, peer, err := server.ReadFromUDP(buf)
	if err != nil {
		t.Fatal(err)
	}
	bye, err := sip.ParseMessage(buf[:n])
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(bye.StartLine, "BYE ") {
		t.Fatal(bye.StartLine)
	}
	response := fmt.Sprintf("SIP/2.0 200 OK\r\nCall-ID: %s\r\nCSeq: %s\r\nContent-Length: 0\r\n\r\n", bye.Headers["call-id"], bye.Headers["cseq"])
	if _, err := server.WriteToUDP([]byte(response), peer); err != nil {
		t.Fatal(err)
	}
	if err := d.Close(); err != nil {
		t.Fatal(err)
	}
	close(events)
	found := false
	for e := range events {
		if e.Name == "device_bye_success" {
			found = true
		}
		if e.Name == "device_bye_timeout" {
			t.Fatal("receive loop stopped before response")
		}
	}
	if !found {
		t.Fatal("missing acknowledged BYE")
	}
}
