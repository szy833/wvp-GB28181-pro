package device

import (
	"regexp"
	"strings"
	"testing"
)

func TestIdentityFactoryUnique(t *testing.T) {
	f, err := NewIdentityFactory("run-1", "loadtest-", "3402")
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ {
		id, err := f.New(i)
		if err != nil {
			t.Fatal(err)
		}
		for _, v := range []string{id.DeviceID, id.CallID, id.Stream, id.SSRC} {
			if seen[v] {
				t.Fatalf("duplicate %q", v)
			}
			seen[v] = true
		}
		if !regexp.MustCompile(`^[0-9]{10}$`).MatchString(id.SSRC) {
			t.Fatalf("bad ssrc %q", id.SSRC)
		}
	}
}

func TestIdentityFactoryNumeric(t *testing.T) {
	f, err := NewIdentityFactoryWithConfig(IdentityFactoryConfig{
		Mode: "numeric", DeviceBase: "34020000001110000000",
		ChannelBase: "34020000001310000000", RunID: "run", Domain: "3402000000",
	})
	if err != nil {
		t.Fatal(err)
	}
	first, err := f.New(0)
	if err != nil {
		t.Fatal(err)
	}
	second, err := f.New(1)
	if err != nil {
		t.Fatal(err)
	}
	if first.DeviceID != "34020000001110000001" || first.ChannelID != "34020000001310000001" {
		t.Fatalf("unexpected first identity: %+v", first)
	}
	if second.DeviceID != "34020000001110000002" || second.ChannelID != "34020000001310000002" {
		t.Fatalf("unexpected second identity: %+v", second)
	}
	for _, id := range []string{first.DeviceID, first.ChannelID, second.DeviceID, second.ChannelID} {
		if len(id) != 20 || !regexp.MustCompile(`^[0-9]{20}$`).MatchString(id) {
			t.Fatalf("invalid GB ID: %q", id)
		}
	}
}

func TestIdentityFactoryNumericRejectsExhaustedWindow(t *testing.T) {
	_, err := NewIdentityFactoryWithConfig(IdentityFactoryConfig{
		Mode: "numeric", DeviceBase: "34020000001110999999",
		ChannelBase: "34020000001310999999", RunID: "run", Domain: "3402",
	})
	if err == nil || !strings.Contains(err.Error(), "range") {
		t.Fatalf("unexpected error: %v", err)
	}
}
