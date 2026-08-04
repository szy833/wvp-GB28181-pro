package sip

import (
	"bytes"
	"fmt"
	"strconv"
	"strings"
	"sync/atomic"

	"github.com/shizy/gb28181-load-test/internal/device"
)

type Message struct { StartLine string; Headers map[string]string; Body []byte }

func ParseMessage(payload []byte) (Message, error) {
	parts := bytes.SplitN(payload, []byte("\r\n\r\n"), 2); if len(parts) != 2 { return Message{}, fmt.Errorf("invalid SIP message") }
	lines := strings.Split(string(parts[0]), "\r\n"); if len(lines) == 0 || lines[0] == "" { return Message{}, fmt.Errorf("missing SIP start line") }
	m := Message{StartLine: lines[0], Headers: make(map[string]string), Body: append([]byte(nil), parts[1]...)}
	for _, line := range lines[1:] { p := strings.IndexByte(line, ':'); if p <= 0 { continue }; m.Headers[strings.ToLower(strings.TrimSpace(line[:p]))] = strings.TrimSpace(line[p+1:]) }
	if n, err := strconv.Atoi(m.Headers["content-length"]); err == nil && n >= 0 && n < len(m.Body) { m.Body = m.Body[:n] }
	return m, nil
}

type Dialog struct { Target, Local, Remote string; CallID string; LocalTag, RemoteTag string; CSeq uint32 }

var branchCounter atomic.Uint64

func branch() string { return fmt.Sprintf("z9hG4bK-loadtest-%d", branchCounter.Add(1)) }

func baseRequest(method, target string, id device.Identity, local string, cseq uint32, body []byte) []byte {
	if cseq == 0 { cseq = 1 }
	if local == "" { local = "127.0.0.1:5060" }
	uri := fmt.Sprintf("sip:%s@%s", id.DeviceID, id.Domain)
	if strings.Contains(local, "@") { uri = "sip:" + local }
	h := fmt.Sprintf("%s %s SIP/2.0\r\nVia: SIP/2.0/UDP %s;branch=%s;rport\r\nFrom: <%s>;tag=%s\r\nTo: <%s>\r\nCall-ID: %s\r\nCSeq: %d %s\r\nMax-Forwards: 70\r\nContact: <%s>\r\nContent-Type: application/sdp\r\nContent-Length: %d\r\n\r\n", method, target, local, branch(), uri, id.CallID, target, id.CallID, cseq, method, uri, len(body))
	return append([]byte(h), body...)
}

func BuildRegister(id device.Identity, target, local string, expires int) ([]byte, error) {
	if target == "" { return nil, fmt.Errorf("target is empty") }; if expires <= 0 { return nil, fmt.Errorf("expires must be positive") }
	b := baseRequest("REGISTER", target, id, local, 1, nil); b = bytes.Replace(b, []byte("Content-Type: application/sdp\r\n"), nil, 1); b = bytes.Replace(b, []byte("Content-Length: 0\r\n"), []byte(fmt.Sprintf("Expires: %d\r\nContent-Length: 0\r\n", expires)), 1); return b, nil
}

func BuildInvite(id device.Identity, target, sdp string) ([]byte, error) { if target == "" { return nil, fmt.Errorf("target is empty") }; return baseRequest("INVITE", target, id, "", 1, []byte(sdp)), nil }

func BuildAck(d Dialog) ([]byte, error) { return dialogRequest("ACK", d), nil }
func BuildBye(d Dialog) ([]byte, error) { if d.CallID == "" { return nil, fmt.Errorf("dialog call id is empty") }; return dialogRequest("BYE", d), nil }

func dialogRequest(method string, d Dialog) []byte {
	to := fmt.Sprintf("<%s>", d.Remote); if d.RemoteTag != "" { to += ";tag=" + d.RemoteTag }
	from := fmt.Sprintf("<%s>", d.Local); if d.LocalTag != "" { from += ";tag=" + d.LocalTag }
	cseq := d.CSeq; if cseq == 0 { cseq = 1 }
	return []byte(fmt.Sprintf("%s %s SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1;branch=%s\r\nFrom: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: %d %s\r\nContent-Length: 0\r\n\r\n", method, d.Target, branch(), from, to, d.CallID, cseq, method))
}
