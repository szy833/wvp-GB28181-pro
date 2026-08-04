package sip

import (
	"strings"
	"testing"
)

func TestKeepaliveXMLEscapesDevice(t *testing.T) { b := BuildKeepaliveXML("a&b", "vendor"); s := string(b); if !strings.Contains(s, "a&amp;b") || !strings.Contains(s, "<CmdType>Keepalive</CmdType>") { t.Fatal(s) } }
