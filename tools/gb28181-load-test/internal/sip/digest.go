package sip

import (
	"crypto/md5"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"
)

type DigestChallenge struct { Realm string; Nonce string; Opaque string; Algorithm string; QOP string }

func ParseDigestChallenge(header string) (DigestChallenge, error) {
	var out DigestChallenge
	if i := strings.Index(strings.ToLower(header), "digest "); i >= 0 { header = header[i+7:] }
	for _, item := range strings.Split(header, ",") {
		parts := strings.SplitN(strings.TrimSpace(item), "=", 2); if len(parts) != 2 { continue }
		key := strings.ToLower(strings.TrimSpace(parts[0])); value := strings.Trim(strings.TrimSpace(parts[1]), "\"")
		switch key { case "realm": out.Realm = value; case "nonce": out.Nonce = value; case "opaque": out.Opaque = value; case "algorithm": out.Algorithm = value; case "qop": if strings.Contains(value, "auth") { out.QOP = "auth" } }
	}
	if out.Realm == "" || out.Nonce == "" { return DigestChallenge{}, fmt.Errorf("digest challenge missing realm or nonce") }; if out.Algorithm != "" && !strings.EqualFold(out.Algorithm, "MD5") { return DigestChallenge{}, fmt.Errorf("unsupported digest algorithm %q", out.Algorithm) }; return out, nil
}

func BuildDigestAuthorization(method, uri, username, password string, challenge DigestChallenge, nonceCount uint32) (string, error) {
	if method == "" || uri == "" || username == "" { return "", fmt.Errorf("digest request fields are incomplete") }; if challenge.Nonce == "" || challenge.Realm == "" { return "", fmt.Errorf("digest challenge is incomplete") }
	var cnonceBytes [12]byte; if _, err := rand.Read(cnonceBytes[:]); err != nil { return "", err }; cnonce := hex.EncodeToString(cnonceBytes[:])
	ha1 := md5Hex(username + ":" + challenge.Realm + ":" + password); ha2 := md5Hex(method + ":" + uri); nc := fmt.Sprintf("%08x", nonceCount); response := ""
	if challenge.QOP != "" { response = md5Hex(ha1 + ":" + challenge.Nonce + ":" + nc + ":" + cnonce + ":" + challenge.QOP + ":" + ha2) } else { response = md5Hex(ha1 + ":" + challenge.Nonce + ":" + ha2) }
	parts := []string{fmt.Sprintf("username=\"%s\"", username), fmt.Sprintf("realm=\"%s\"", challenge.Realm), fmt.Sprintf("nonce=\"%s\"", challenge.Nonce), fmt.Sprintf("uri=\"%s\"", uri), fmt.Sprintf("response=\"%s\"", response)}
	if challenge.Algorithm != "" { parts = append(parts, "algorithm="+challenge.Algorithm) }; if challenge.Opaque != "" { parts = append(parts, fmt.Sprintf("opaque=\"%s\"", challenge.Opaque)) }; if challenge.QOP != "" { parts = append(parts, "qop="+challenge.QOP, "nc="+nc, fmt.Sprintf("cnonce=\"%s\"", cnonce)) }; return "Digest " + strings.Join(parts, ", "), nil
}

func md5Hex(value string) string { sum := md5.Sum([]byte(value)); return hex.EncodeToString(sum[:]) }
