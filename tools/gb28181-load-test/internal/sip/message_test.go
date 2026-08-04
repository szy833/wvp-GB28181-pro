package sip

import (
	"strings"
	"testing"
	"github.com/shizy/gb28181-load-test/internal/device"
)

func TestBuildRequestsContainRequiredHeaders(t *testing.T) { f, _ := device.NewIdentityFactory("run", "loadtest-", "3402"); id, _ := f.New(0); for _, b := range [][]byte{must(BuildRegister(id, "sip:server@127.0.0.1", "127.0.0.1:1000", 3600)), must(BuildInvite(id, "sip:channel@127.0.0.1", "v=0\r\n"))} { s := string(b); for _, h := range []string{"Via:", "From:", "To:", "Call-ID:", "CSeq:", "Content-Length:"} { if !strings.Contains(s, h) { t.Errorf("missing %s", h) } } } }
func must(b []byte, err error) []byte { if err != nil { panic(err) }; return b }
