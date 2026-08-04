package sip

import (
	"strings"
	"testing"
)

func TestDigestAuthorization(t *testing.T) { c, err := ParseDigestChallenge(`Digest realm="3402", nonce="abc", qop="auth", algorithm=MD5`); if err != nil { t.Fatal(err) }; a, err := BuildDigestAuthorization("REGISTER", "sip:server@example.com", "user", "pass", c, 1); if err != nil { t.Fatal(err) }; for _, part := range []string{"username=\"user\"", "realm=\"3402\"", "nonce=\"abc\"", "qop=auth", "nc=00000001", "response=\""} { if !strings.Contains(a, part) { t.Fatalf("missing %s: %s", part, a) } } }
