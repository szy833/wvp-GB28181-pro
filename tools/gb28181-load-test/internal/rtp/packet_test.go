package rtp

import "testing"

func TestPacketizerBuildsRTP(t *testing.T) { p := Packetizer{MTU: 100, PayloadType: 96}; packets := p.Packetize(MediaUnit{Data: make([]byte, 250), Timestamp: 123}, 7, 10); if len(packets) != 3 { t.Fatalf("%d", len(packets)) }; if packets[0][0] != 0x80 || packets[0][1] != 96 || packets[2][1] != 0xe0 { t.Fatalf("bad header: %x %x", packets[0][:2], packets[2][:2]) }; for _, b := range packets { if len(b) > 100 { t.Fatal(len(b)) } } }
