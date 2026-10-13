package sip

import (
	"bytes"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/shizy/gb28181-load-test/internal/model"
)

type Message struct {
	StartLine string
	Headers   map[string]string
	Body      []byte
}

func ParseMessage(payload []byte) (Message, error) {
	parts := bytes.SplitN(payload, []byte("\r\n\r\n"), 2)
	if len(parts) != 2 {
		return Message{}, fmt.Errorf("invalid SIP message")
	}
	lines := strings.Split(string(parts[0]), "\r\n")
	if len(lines) == 0 || lines[0] == "" {
		return Message{}, fmt.Errorf("missing SIP start line")
	}
	m := Message{StartLine: lines[0], Headers: make(map[string]string), Body: append([]byte(nil), parts[1]...)}
	for _, line := range lines[1:] {
		p := strings.IndexByte(line, ':')
		if p <= 0 {
			continue
		}
		name, value := strings.ToLower(strings.TrimSpace(line[:p])), strings.TrimSpace(line[p+1:])
		if old := m.Headers[name]; old != "" && (name == "record-route" || name == "via") {
			value = old + ", " + value
		}
		m.Headers[name] = value
	}
	if n, err := strconv.Atoi(m.Headers["content-length"]); err == nil && n >= 0 && n < len(m.Body) {
		m.Body = m.Body[:n]
	}
	return m, nil
}

type Dialog struct {
	NextHop               string
	Target, Local, Remote string
	CallID                string
	LocalTag, RemoteTag   string
	CSeq, InviteCSeq      uint32
	Via                   string
	Routes                []string
}

func ResolveUDPURI(uri string) (*net.UDPAddr, error) {
	value := strings.TrimPrefix(uri, "sip:")
	if at := strings.LastIndexByte(value, '@'); at >= 0 {
		value = value[at+1:]
	}
	value = strings.SplitN(value, ";", 2)[0]
	if _, _, err := net.SplitHostPort(value); err != nil {
		value = net.JoinHostPort(strings.Trim(value, "[]"), "5060")
	}
	return net.ResolveUDPAddr("udp", value)
}

func HeaderURI(value string) string {
	value = strings.TrimSpace(value)
	if start := strings.IndexByte(value, '<'); start >= 0 {
		if end := strings.IndexByte(value[start+1:], '>'); end >= 0 {
			return value[start+1 : start+1+end]
		}
	}
	return strings.TrimSpace(strings.SplitN(value, ";", 2)[0])
}

func HeaderTag(value string) string {
	for _, part := range strings.Split(value, ";")[1:] {
		key, val, ok := strings.Cut(strings.TrimSpace(part), "=")
		if ok && strings.EqualFold(key, "tag") {
			return strings.TrimSpace(val)
		}
	}
	return ""
}

func ParseCSeq(value, method string) (uint32, error) {
	parts := strings.Fields(value)
	if len(parts) != 2 || !strings.EqualFold(parts[1], method) {
		return 0, fmt.Errorf("invalid %s CSeq", method)
	}
	n, err := strconv.ParseUint(parts[0], 10, 31)
	if err != nil || n == 0 {
		return 0, fmt.Errorf("invalid %s CSeq", method)
	}
	return uint32(n), nil
}

var branchCounter atomic.Uint64

func branch() string { return fmt.Sprintf("z9hG4bK-loadtest-%d", branchCounter.Add(1)) }

func baseRequest(method, target string, id model.Identity, local string, cseq uint32, body []byte) []byte {
	return baseRequestCSeq(method, target, id, local, cseq, body)
}

func baseRequestCSeq(method, target string, id model.Identity, local string, cseq uint32, body []byte) []byte {
	if cseq == 0 {
		cseq = 1
	}
	if local == "" {
		local = "127.0.0.1:5060"
	}
	uri := fmt.Sprintf("sip:%s@%s", id.DeviceID, id.Domain)
	if strings.Contains(local, "@") {
		uri = "sip:" + local
	}
	h := fmt.Sprintf("%s %s SIP/2.0\r\nVia: SIP/2.0/UDP %s;branch=%s;rport\r\nFrom: <%s>;tag=%s\r\nTo: <%s>\r\nCall-ID: %s\r\nCSeq: %d %s\r\nMax-Forwards: 70\r\nContact: <%s>\r\nContent-Type: application/sdp\r\nContent-Length: %d\r\n\r\n", method, target, local, branch(), uri, id.CallID, target, id.CallID, cseq, method, uri, len(body))
	return append([]byte(h), body...)
}

func BuildRegister(id model.Identity, target, local string, expires int) ([]byte, error) {
	return BuildRegisterAuth(id, target, local, expires, "")
}

func BuildRegisterAuth(id model.Identity, target, local string, expires int, authorization string) ([]byte, error) {
	return BuildRegisterAuthCSeq(id, target, local, expires, authorization, 1)
}

func BuildRegisterAuthCSeq(id model.Identity, target, local string, expires int, authorization string, cseq uint32) ([]byte, error) {
	if target == "" {
		return nil, fmt.Errorf("target is empty")
	}
	if expires <= 0 {
		return nil, fmt.Errorf("expires must be positive")
	}
	b := baseRequestCSeq("REGISTER", target, id, local, cseq, nil)
	b = bytes.Replace(b, []byte("Content-Type: application/sdp\r\n"), nil, 1)
	b = bytes.Replace(b, []byte("Content-Length: 0\r\n"), []byte(fmt.Sprintf("Expires: %d\r\n%sContent-Length: 0\r\n", expires, authHeader(authorization))), 1)
	return b, nil
}

func authHeader(value string) string {
	if value == "" {
		return ""
	}
	return "Authorization: " + value + "\r\n"
}

func BuildInvite(id model.Identity, target, sdp string) ([]byte, error) {
	if target == "" {
		return nil, fmt.Errorf("target is empty")
	}
	return baseRequest("INVITE", target, id, "", 1, []byte(sdp)), nil
}

func BuildMessage(id model.Identity, target, local string, body []byte, cseq uint32) ([]byte, error) {
	if target == "" {
		return nil, fmt.Errorf("target is empty")
	}
	if cseq == 0 {
		cseq = 1
	}
	if local == "" {
		local = "127.0.0.1:5060"
	}
	uri := fmt.Sprintf("sip:%s@%s", id.DeviceID, id.Domain)
	text := fmt.Sprintf("MESSAGE %s SIP/2.0\r\nVia: SIP/2.0/UDP %s;branch=%s;rport\r\nFrom: <%s>;tag=%s\r\nTo: <%s>\r\nCall-ID: %s\r\nCSeq: %d MESSAGE\r\nMax-Forwards: 70\r\nContent-Type: Application/MANSCDP+xml\r\nContent-Length: %d\r\n\r\n", target, local, branch(), uri, id.CallID, target, id.CallID, cseq, len(body))
	return append([]byte(text), body...), nil
}

func BuildAck(d Dialog) ([]byte, error) { return dialogRequest("ACK", d), nil }
func BuildBye(d Dialog) ([]byte, error) {
	if d.CallID == "" || !strings.HasPrefix(d.Target, "sip:") || !strings.HasPrefix(d.Local, "sip:") || !strings.HasPrefix(d.Remote, "sip:") {
		return nil, fmt.Errorf("invalid BYE dialog")
	}
	return dialogRequest("BYE", d), nil
}

func dialogRequest(method string, d Dialog) []byte {
	to := fmt.Sprintf("<%s>", d.Remote)
	if d.RemoteTag != "" {
		to += ";tag=" + d.RemoteTag
	}
	from := fmt.Sprintf("<%s>", d.Local)
	if d.LocalTag != "" {
		from += ";tag=" + d.LocalTag
	}
	cseq := d.CSeq
	if cseq == 0 {
		cseq = 1
	}
	via := d.Via
	if via == "" {
		via = "127.0.0.1:5060"
	}
	routes := ""
	target := d.Target
	routeSet := d.Routes
	if len(routeSet) > 0 {
		first := HeaderURI(routeSet[0])
		loose := false
		for _, param := range strings.Split(first, ";")[1:] {
			name, _, _ := strings.Cut(param, "=")
			if strings.EqualFold(name, "lr") {
				loose = true
			}
		}
		if !loose {
			target = first
			routeSet = append(append([]string(nil), routeSet[1:]...), "<"+d.Target+">")
		}
	}
	for _, route := range routeSet {
		routes += "Route: " + route + "\r\n"
	}
	return []byte(fmt.Sprintf("%s %s SIP/2.0\r\nVia: SIP/2.0/UDP %s;branch=%s;rport\r\nFrom: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: %d %s\r\nMax-Forwards: 70\r\n%sContent-Length: 0\r\n\r\n", method, target, via, branch(), from, to, d.CallID, cseq, method, routes))
}
