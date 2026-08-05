package sip_test

import (
	"strings"
	"testing"

	"github.com/shizy/gb28181-load-test/internal/model"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

func TestBuildRequestsContainRequiredHeaders(t *testing.T) { id := model.Identity{DeviceID: "loadtest-000001", Domain: "3402", CallID: "call-1"}; for _, b := range [][]byte{must(sip.BuildRegister(id, "sip:server@127.0.0.1", "127.0.0.1:1000", 3600)), must(sip.BuildInvite(id, "sip:channel@127.0.0.1", "v=0\r\n"))} { s := string(b); for _, h := range []string{"Via:", "From:", "To:", "Call-ID:", "CSeq:", "Content-Length:"} { if !strings.Contains(s, h) { t.Errorf("missing %s", h) } } } }
func must(b []byte, err error) []byte { if err != nil { panic(err) }; return b }
