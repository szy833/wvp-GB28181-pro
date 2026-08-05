package model

// Identity contains the identifiers shared by SIP, RTP and device layers.
// It deliberately has no dependency on either transport or device logic.
type Identity struct {
	DeviceID  string
	ChannelID string
	CallID    string
	Stream    string
	SSRC      string
	Index     int
	Domain    string
}
