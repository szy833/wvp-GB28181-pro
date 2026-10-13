package sip

import (
	"fmt"
	"net"
	"strconv"
	"strings"
)

type SDPRequest struct {
	Address string
	Port    int
	SSRC    string
	Mode    string
}
type SDPResponse struct {
	Address    string
	Port       int
	Protocol   string
	Setup      string
	Connection string
	SSRC       string
}

func BuildPlaySDP(r SDPRequest) string {
	mode := r.Mode
	if mode == "" {
		mode = "TCP-PASSIVE"
	}
	addr := r.Address
	if addr == "" {
		addr = "0.0.0.0"
	}
	return fmt.Sprintf("v=0\r\no=%s 0 0 IN IP4 %s\r\ns=Play\r\nc=IN IP4 %s\r\nt=0 0\r\nm=video %d RTP/AVP 96\r\na=recvonly\r\na=rtpmap:96 PS/90000\r\na=setup:%s\r\na=connection:new\r\ny=%s\r\n", "loadtest", addr, addr, r.Port, strings.ToLower(mode), r.SSRC)
}

func BuildAnswerSDP(r SDPRequest) string {
	protocol, setup := "RTP/AVP", ""
	switch strings.ToUpper(r.Mode) {
	case "TCP-ACTIVE":
		protocol, setup = "TCP/RTP/AVP", "a=setup:active\r\na=connection:new\r\n"
	case "TCP-PASSIVE":
		protocol, setup = "TCP/RTP/AVP", "a=setup:passive\r\na=connection:new\r\n"
	}
	return fmt.Sprintf("v=0\r\no=loadtest 0 0 IN IP4 %s\r\ns=Play\r\nc=IN IP4 %s\r\nt=0 0\r\nm=video %d %s 96\r\na=sendonly\r\na=rtpmap:96 PS/90000\r\n%sy=%s\r\n", r.Address, r.Address, r.Port, protocol, setup, r.SSRC)
}

func ParseSDP(payload []byte) (SDPResponse, error) {
	var out SDPResponse
	text := strings.ReplaceAll(string(payload), "\r\n", "\n")
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(line, "c=IN IP4 "):
			out.Address = strings.TrimSpace(strings.TrimPrefix(line, "c=IN IP4 "))
		case strings.HasPrefix(line, "m=video "):
			p := strings.Fields(line)
			if len(p) > 2 {
				n, err := strconv.Atoi(p[1])
				if err == nil {
					out.Port = n
				}
				out.Protocol = p[2]
			}
		case strings.HasPrefix(line, "a=setup:"):
			out.Setup = strings.TrimPrefix(line, "a=setup:")
		case strings.HasPrefix(line, "a=connection:"):
			out.Connection = strings.TrimPrefix(line, "a=connection:")
		case strings.HasPrefix(line, "y="):
			out.SSRC = strings.TrimSpace(strings.TrimPrefix(line, "y="))
		}
	}
	if net.ParseIP(out.Address) == nil || out.Port <= 0 {
		return SDPResponse{}, fmt.Errorf("invalid SDP media address")
	}
	return out, nil
}
