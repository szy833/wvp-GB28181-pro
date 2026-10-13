package rtp

import (
	"encoding/binary"
	"fmt"
)

const clockMask int64 = (1 << 33) - 1

type psPacket struct {
	start, end   int
	clockOffsets []int
	decodeTime   int64
	video        bool
	packClock    bool
}
type psUnit struct {
	start, end      int
	clock, duration int64
	packets         []psPacket
}

func readClock(b []byte) int64 {
	return int64(b[0]&14)<<29 | int64(b[1])<<22 | int64(b[2]&254)<<14 | int64(b[3])<<7 | int64(b[4]>>1)
}

func writeClock(b []byte, v int64) {
	v &= clockMask
	b[0] = b[0]&0xf0 | byte(v>>29)&14 | 1
	b[1], b[2], b[3], b[4] = byte(v>>22), byte(v>>14)&254|1, byte(v>>7), byte(v<<1)|1
}

// Walk packet lengths, not arbitrary Annex-B start codes inside video payloads.
// Timed video PES groups preserve the original multiplexed PS byte order.
func parseTimeline(data []byte) ([]psPacket, []psUnit, error) {
	var packets []psPacket
	var units []psUnit
	var previous, elapsed int64
	for pos := 0; pos < len(data); {
		if pos+4 > len(data) || data[pos] != 0 || data[pos+1] != 0 || data[pos+2] != 1 {
			return nil, nil, fmt.Errorf("invalid PS packet at byte %d", pos)
		}
		id := data[pos+3]
		p := psPacket{start: pos, decodeTime: -1}
		switch id {
		case 0xba:
			if pos+12 > len(data) {
				return nil, nil, fmt.Errorf("truncated PS pack")
			}
			if data[pos+4]&0xc0 == 0x40 {
				if pos+14 > len(data) {
					return nil, nil, fmt.Errorf("truncated MPEG-2 pack")
				}
				p.end, p.packClock = pos+14+int(data[pos+13]&7), true
			} else if data[pos+4]&0xf0 == 0x20 {
				p.end = pos + 12
				p.clockOffsets = []int{pos + 4}
			} else {
				return nil, nil, fmt.Errorf("unsupported PS pack header")
			}
		case 0xb9:
			p.end = pos + 4
		default:
			if id < 0xbb || pos+6 > len(data) {
				return nil, nil, fmt.Errorf("invalid PES header")
			}
			length := int(binary.BigEndian.Uint16(data[pos+4 : pos+6]))
			if length == 0 {
				return nil, nil, fmt.Errorf("unbounded PES not supported; remux input to MPEG-PS")
			}
			p.end = pos + 6 + length
			if p.end > len(data) {
				return nil, nil, fmt.Errorf("truncated PES packet")
			}
			p.video = id >= 0xe0 && id <= 0xef
			if p.video || id >= 0xc0 && id <= 0xdf || id == 0xbd {
				h := pos + 6
				if h+3 <= p.end && data[h]&0xc0 == 0x80 {
					flags, headerLen := data[h+1], int(data[h+2])
					if h+3+headerLen > p.end {
						return nil, nil, fmt.Errorf("invalid PES optional header")
					}
					if flags&0x80 != 0 && headerLen >= 5 {
						p.clockOffsets = append(p.clockOffsets, h+3)
					}
					if flags&0xc0 == 0xc0 && headerLen >= 10 {
						p.clockOffsets = append(p.clockOffsets, h+8)
					}
				} else {
					for h < p.end && data[h] == 0xff {
						h++
					}
					if h < p.end && data[h]&0xc0 == 0x40 {
						h += 2
					}
					if h+5 <= p.end && (data[h]&0xf0 == 0x20 || data[h]&0xf0 == 0x30) {
						p.clockOffsets = append(p.clockOffsets, h)
						if data[h]&0xf0 == 0x30 {
							if h+10 > p.end {
								return nil, nil, fmt.Errorf("truncated DTS")
							}
							p.clockOffsets = append(p.clockOffsets, h+5)
						}
					}
				}
			}
		}
		if p.end > len(data) {
			return nil, nil, fmt.Errorf("truncated PS pack")
		}
		if p.video && len(p.clockOffsets) > 0 {
			p.decodeTime = readClock(data[p.clockOffsets[len(p.clockOffsets)-1]:])
			if len(units) == 0 {
				previous = p.decodeTime
				units = append(units, psUnit{start: 0})
			} else if p.decodeTime != previous {
				delta := (p.decodeTime - previous) & clockMask
				if delta <= 0 || delta > 900000 {
					return nil, nil, fmt.Errorf("non-monotonic or excessive video DTS/PTS gap; remux input")
				}
				elapsed += delta
				// Include immediately preceding pack/system headers with the next video PES.
				boundary := pos
				for i := len(packets) - 1; i >= 0 && !packets[i].video; i-- {
					boundary = packets[i].start
				}
				last := &units[len(units)-1]
				last.end, last.duration = boundary, delta
				units = append(units, psUnit{start: boundary, clock: elapsed})
				previous = p.decodeTime
			}
		}
		packets = append(packets, p)
		pos = p.end
	}
	if len(units) < 2 {
		return nil, nil, fmt.Errorf("PS input needs at least two distinct video DTS/PTS timestamps")
	}
	last := &units[len(units)-1]
	last.end, last.duration = len(data), units[len(units)-2].duration
	i := 0
	for j := range units {
		start := i
		for i < len(packets) && packets[i].end <= units[j].end {
			i++
		}
		units[j].packets = packets[start:i]
	}
	return packets, units, nil
}

func shiftPacketClock(data []byte, p psPacket, origin int, offset int64) {
	for _, at := range p.clockOffsets {
		b := data[at-origin:]
		writeClock(b, readClock(b)+offset)
	}
	if p.packClock {
		b := data[p.start+4-origin:]
		v := int64(b[0]&0x38)<<27 | int64(b[0]&3)<<28 | int64(b[1])<<20 | int64(b[2]&0xf8)<<12 | int64(b[2]&3)<<13 | int64(b[3])<<5 | int64(b[4]>>3)
		v = (v + offset) & clockMask
		b[0] = b[0]&0xc4 | byte(v>>27)&0x38 | byte(v>>28)&3
		b[1] = byte(v >> 20)
		b[2] = b[2]&4 | byte(v>>12)&0xf8 | byte(v>>13)&3
		b[3] = byte(v >> 5)
		b[4] = b[4]&7 | byte(v<<3)
	}
}
