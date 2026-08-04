package rtp

import "encoding/binary"

const HeaderSize = 12

type Packetizer struct { MTU int; PayloadType byte }

func (p Packetizer) Packetize(unit MediaUnit, ssrc uint32, sequence uint16) [][]byte {
	mtu := p.MTU; if mtu <= HeaderSize { mtu = 1400 }; pt := p.PayloadType; if pt == 0 { pt = 96 }
	max := mtu - HeaderSize; data := unit.Data; if len(data) == 0 { return nil }
	result := make([][]byte, 0, (len(data)+max-1)/max)
	for offset := 0; offset < len(data); offset += max { end := offset + max; if end > len(data) { end = len(data) }; b := make([]byte, HeaderSize+end-offset); b[0] = 0x80; b[1] = pt; if end == len(data) { b[1] |= 0x80 }; binary.BigEndian.PutUint16(b[2:4], sequence+uint16(len(result))); binary.BigEndian.PutUint32(b[4:8], uint32(unit.Timestamp)); binary.BigEndian.PutUint32(b[8:12], ssrc); copy(b[12:], data[offset:end]); result = append(result, b) }
	return result
}
