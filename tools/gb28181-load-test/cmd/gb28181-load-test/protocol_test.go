package main

import (
	"strings"
	"testing"

	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/rtp"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

func TestFactoryAnswersWithOfferedSSRCAndSendonly(t *testing.T) {
	ids, err := device.NewIdentityFactory("protocol", "loadtest-", "3402000000")
	if err != nil {
		t.Fatal(err)
	}
	f := deviceFactory{identities: ids, media: &rtp.SharedMedia{}, cfg: config.Config{SIP: config.SIPConfig{Address: "127.0.0.1:5060", Local: "127.0.0.1:0", Advertise: "127.0.0.1"}, Scenario: config.ScenarioConfig{Name: "no-media-timeout"}}}
	d, err := f.New(0)
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	_, answer, err := d.Hooks.SenderFactory(sip.SDPResponse{Address: "127.0.0.1", Port: 41000, Protocol: "TCP/RTP/AVP", Setup: "passive", SSRC: "0200009413"})
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"y=0200009413", "a=sendonly", "m=video 41000 TCP/RTP/AVP 96", "a=setup:active"} {
		if !strings.Contains(answer, want) {
			t.Fatalf("SDP missing %s: %s", want, answer)
		}
	}
}
