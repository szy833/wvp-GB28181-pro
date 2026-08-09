package device

import (
	"crypto/rand"
	"fmt"
	"strconv"
	"strings"
	"sync"

	"github.com/shizy/gb28181-load-test/internal/model"
)

type Identity = model.Identity

type IdentityFactoryConfig struct {
	Mode         string
	DeviceBase   string
	ChannelBase  string
	LegacyPrefix string
	RunID        string
	Domain       string
}

type IdentityFactory struct {
	RunID           string
	Mode            string
	Prefix          string
	Domain          string
	devicePrefix    string
	channelPrefix   string
	deviceSequence  int
	channelSequence int
	typePrefix      string
	mu              sync.Mutex
	used            map[string]struct{}
	usedDevice      map[string]struct{}
	usedChannel     map[string]struct{}
}

func NewIdentityFactory(runID, prefix, domain string) (*IdentityFactory, error) {
	return NewIdentityFactoryWithConfig(IdentityFactoryConfig{
		Mode: "legacy", LegacyPrefix: prefix, RunID: runID, Domain: domain,
	})
}

func NewIdentityFactoryWithConfig(config IdentityFactoryConfig) (*IdentityFactory, error) {
	if strings.TrimSpace(config.RunID) == "" {
		return nil, fmt.Errorf("runID must not be empty")
	}
	if strings.TrimSpace(config.Domain) == "" {
		return nil, fmt.Errorf("domain must not be empty")
	}
	mode := strings.ToLower(strings.TrimSpace(config.Mode))
	if mode == "" {
		mode = "numeric"
	}
	factory := &IdentityFactory{
		RunID:       config.RunID,
		Mode:        mode,
		Prefix:      strings.TrimSpace(config.LegacyPrefix),
		Domain:      config.Domain,
		typePrefix:  "0",
		used:        make(map[string]struct{}),
		usedDevice:  make(map[string]struct{}),
		usedChannel: make(map[string]struct{}),
	}
	switch mode {
	case "legacy":
		if !strings.HasPrefix(factory.Prefix, "loadtest-") {
			return nil, fmt.Errorf("id prefix must start with loadtest-")
		}
	case "numeric":
		var err error
		factory.devicePrefix, factory.deviceSequence, err = parseNumericBase("deviceBase", config.DeviceBase)
		if err != nil {
			return nil, err
		}
		factory.channelPrefix, factory.channelSequence, err = parseNumericBase("channelBase", config.ChannelBase)
		if err != nil {
			return nil, err
		}
		if factory.devicePrefix == factory.channelPrefix {
			return nil, fmt.Errorf("deviceBase and channelBase prefixes must differ")
		}
	default:
		return nil, fmt.Errorf("identity mode must be numeric or legacy")
	}
	return factory, nil
}

func (f *IdentityFactory) New(index int) (Identity, error) {
	if index < 0 {
		return Identity{}, fmt.Errorf("index must not be negative")
	}

	f.mu.Lock()
	defer f.mu.Unlock()

	deviceID, channelID, err := f.nextIDs(index)
	if err != nil {
		return Identity{}, err
	}
	if _, exists := f.usedDevice[deviceID]; exists {
		return Identity{}, fmt.Errorf("duplicate device identity: %s", deviceID)
	}
	if _, exists := f.usedChannel[channelID]; exists {
		return Identity{}, fmt.Errorf("duplicate channel identity: %s", channelID)
	}

	var random [8]byte
	if _, err := rand.Read(random[:]); err != nil {
		return Identity{}, fmt.Errorf("call id entropy: %w", err)
	}
	callID := fmt.Sprintf("%s-%d-%x", f.RunID, index, random)
	stream := deviceID + "_" + channelID
	domainNumber, _ := strconv.ParseUint(f.Domain, 10, 64)
	for n := 0; n < 10000; n++ {
		// GB28181 SSRC is kept at ten decimal digits: type prefix + five
		// domain digits + four sequence digits.
		candidate := fmt.Sprintf("%s%05d%04d", f.typePrefix, domainNumber%100000, (index+n)%10000)
		if _, exists := f.used[candidate]; exists {
			continue
		}
		f.used[candidate] = struct{}{}
		f.usedDevice[deviceID] = struct{}{}
		f.usedChannel[channelID] = struct{}{}
		return Identity{DeviceID: deviceID, ChannelID: channelID, CallID: callID, Stream: stream, SSRC: candidate, Index: index, Domain: f.Domain}, nil
	}
	return Identity{}, fmt.Errorf("ssrc exhausted")
}

func (f *IdentityFactory) nextIDs(index int) (string, string, error) {
	if f.Mode == "legacy" {
		deviceID := f.Prefix + fmt.Sprintf("%06d", index+1)
		return deviceID, deviceID + "01", nil
	}
	deviceSequence := f.deviceSequence + index + 1
	channelSequence := f.channelSequence + index + 1
	if deviceSequence > 999999 || channelSequence > 999999 {
		return "", "", fmt.Errorf("numeric identity range exhausted")
	}
	return f.devicePrefix + fmt.Sprintf("%06d", deviceSequence),
		f.channelPrefix + fmt.Sprintf("%06d", channelSequence), nil
}

func parseNumericBase(name, value string) (string, int, error) {
	if len(value) != 20 {
		return "", 0, fmt.Errorf("%s must be exactly 20 digits", name)
	}
	for _, r := range value {
		if r < '0' || r > '9' {
			return "", 0, fmt.Errorf("%s must contain only digits", name)
		}
	}
	sequence := 0
	for _, r := range value[14:] {
		sequence = sequence*10 + int(r-'0')
	}
	if sequence >= 999999 {
		return "", 0, fmt.Errorf("%s sequence leaves no available range", name)
	}
	return value[:14], sequence, nil
}

func (f *IdentityFactory) String() string { return strconv.Itoa(len(f.used)) }
