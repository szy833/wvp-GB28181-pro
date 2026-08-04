package sip

import "testing"

func TestSDPRoundTrip(t *testing.T) { s := BuildPlaySDP(SDPRequest{Address: "127.0.0.1", Port: 9000, SSRC: "0200000001", Mode: "TCP-PASSIVE"}); if !contains(s, "m=video") || !contains(s, "a=rtpmap:96 PS/90000") { t.Fatal(s) }; got, err := ParseSDP([]byte("v=0\r\nc=IN IP4 192.0.2.1\r\nm=video 5000 RTP/AVP 96\r\na=setup:passive\r\na=connection:new\r\ny=0200000001\r\n")); if err != nil || got.Port != 5000 || got.Setup != "passive" { t.Fatalf("%+v %v", got, err) } }
func contains(s, x string) bool { for i := 0; i+len(x) <= len(s); i++ { if s[i:i+len(x)] == x { return true } }; return false }
