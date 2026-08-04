package device

import (
	"crypto/rand"
	"fmt"
	"strconv"
	"strings"
	"sync"
)

type Identity struct {
	DeviceID  string
	ChannelID string
	CallID    string
	Stream    string
	SSRC      string
	Index     int
	Domain    string
}

type IdentityFactory struct {
	RunID  string
	Prefix string
	Domain string
	typePrefix string
	mu sync.Mutex
	used map[string]struct{}
}

func NewIdentityFactory(runID, prefix, domain string) (*IdentityFactory, error) {
	if strings.TrimSpace(runID) == "" { return nil, fmt.Errorf("runID must not be empty") }
	if !strings.HasPrefix(prefix, "loadtest-") { return nil, fmt.Errorf("id prefix must start with loadtest-") }
	if strings.TrimSpace(domain) == "" { return nil, fmt.Errorf("domain must not be empty") }
	return &IdentityFactory{RunID: runID, Prefix: prefix, Domain: domain, typePrefix: "0", used: make(map[string]struct{})}, nil
}

func (f *IdentityFactory) New(index int) (Identity, error) {
	if index < 0 { return Identity{}, fmt.Errorf("index must not be negative") }
	deviceID := f.Prefix + fmt.Sprintf("%06d", index+1)
	channelID := deviceID + "01"
	var random [8]byte
	if _, err := rand.Read(random[:]); err != nil { return Identity{}, fmt.Errorf("call id entropy: %w", err) }
	callID := fmt.Sprintf("%s-%d-%x", f.RunID, index, random)
	stream := deviceID + "_" + channelID
	f.mu.Lock(); defer f.mu.Unlock()
	for n := 0; n < 10000; n++ {
		// GB28181 SSRC is kept at ten decimal digits: type prefix + five
		// domain digits + four sequence digits.
		domainNumber, _ := strconv.ParseUint(f.Domain, 10, 64)
		candidate := fmt.Sprintf("%s%05d%04d", f.typePrefix, domainNumber%100000, (index+n)%10000)
		if _, exists := f.used[candidate]; !exists { f.used[candidate] = struct{}{}; return Identity{DeviceID: deviceID, ChannelID: channelID, CallID: callID, Stream: stream, SSRC: candidate, Index: index, Domain: f.Domain}, nil }
	}
	return Identity{}, fmt.Errorf("ssrc exhausted")
}

func (f *IdentityFactory) String() string { return strconv.Itoa(len(f.used)) }
