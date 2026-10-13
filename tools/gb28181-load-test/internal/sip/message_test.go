package sip_test

import (
	"strings"
	"testing"

	"github.com/shizy/gb28181-load-test/internal/model"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

func TestBuildRequestsContainRequiredHeaders(t *testing.T) {
	id := model.Identity{DeviceID: "loadtest-000001", Domain: "3402", CallID: "call-1"}
	for _, b := range [][]byte{must(sip.BuildRegister(id, "sip:server@127.0.0.1", "127.0.0.1:1000", 3600)), must(sip.BuildInvite(id, "sip:channel@127.0.0.1", "v=0\r\n"))} {
		s := string(b)
		for _, h := range []string{"Via:", "From:", "To:", "Call-ID:", "CSeq:", "Content-Length:"} {
			if !strings.Contains(s, h) {
				t.Errorf("missing %s", h)
			}
		}
	}
}
func must(b []byte, err error) []byte {
	if err != nil {
		panic(err)
	}
	return b
}

func TestByeUsesStrictRouteAndRetainsRemoteTarget(t *testing.T) {
	b, err := sip.BuildBye(sip.Dialog{CallID: "dialog", Local: "sip:device@domain", Remote: "sip:server@domain", Target: "sip:server@127.0.0.1:5060", Routes: []string{"<sip:proxy@127.0.0.1:5070>", "<sip:proxy@127.0.0.1:5080;lr>"}})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(string(b), "BYE sip:proxy@127.0.0.1:5070 SIP/2.0") {
		t.Fatalf("strict route ignored: %s", b)
	}
	if !strings.Contains(string(b), "Route: <sip:server@127.0.0.1:5060>") {
		t.Fatal("remote Contact lost from route set")
	}
}

func TestParseMessageKeepsRepeatedRecordRoute(t *testing.T) {
	m, err := sip.ParseMessage([]byte("INVITE sip:x SIP/2.0\r\nRecord-Route: <sip:first;lr>\r\nRecord-Route: <sip:second;lr>\r\nContent-Length: 0\r\n\r\n"))
	if err != nil {
		t.Fatal(err)
	}
	if m.Headers["record-route"] != "<sip:first;lr>, <sip:second;lr>" {
		t.Fatal(m.Headers["record-route"])
	}
}
