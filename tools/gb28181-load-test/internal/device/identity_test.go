package device

import (
	"regexp"
	"testing"
)

func TestIdentityFactoryUnique(t *testing.T) {
	f, err := NewIdentityFactory("run-1", "loadtest-", "3402"); if err != nil { t.Fatal(err) }
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ { id, err := f.New(i); if err != nil { t.Fatal(err) }; for _, v := range []string{id.DeviceID, id.CallID, id.Stream, id.SSRC} { if seen[v] { t.Fatalf("duplicate %q", v) }; seen[v] = true }; if !regexp.MustCompile(`^[0-9]{10}$`).MatchString(id.SSRC) { t.Fatalf("bad ssrc %q", id.SSRC) } }
}
