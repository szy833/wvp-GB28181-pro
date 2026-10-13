package rtp

import (
	"encoding/binary"
	"fmt"
	"io"
)

type annexBNAL struct{ start, payload int }

func annexBNALs(data []byte) []annexBNAL {
	var result []annexBNAL
	for i := 0; i+3 < len(data); {
		n := 0
		if data[i] == 0 && data[i+1] == 0 {
			if data[i+2] == 1 {
				n = 3
			} else if data[i+2] == 0 && data[i+3] == 1 {
				n = 4
			}
		}
		if n > 0 && i+n < len(data) {
			result = append(result, annexBNAL{i, i + n})
			i += n + 1
		} else {
			i++
		}
	}
	return result
}

// MuxH264PS accepts CFR, no-B-frame Annex-B H.264 with an AUD per frame.
// Each access unit receives its own MPEG-2 pack and PTS; large frames span
// bounded PES packets sharing that PTS, never packets containing two frames.
func MuxH264PS(data []byte, output io.Writer, fps int) error {
	if fps < 1 || fps > 120 {
		return fmt.Errorf("media FPS must be between 1 and 120")
	}
	nals := annexBNALs(data)
	if len(nals) == 0 || nals[0].start != 0 || data[nals[0].payload]&31 != 9 {
		return fmt.Errorf("raw H.264 needs an AUD before every frame; use prepare-media")
	}
	var frames []int
	hasVideo := false
	for _, nal := range nals {
		switch data[nal.payload] & 31 {
		case 9:
			if len(frames) > 0 && !hasVideo {
				return fmt.Errorf("H.264 access unit contains no video")
			}
			frames = append(frames, nal.start)
			hasVideo = false
		case 1, 5:
			hasVideo = true
		}
	}
	if !hasVideo || len(frames) < 2 {
		return fmt.Errorf("media needs at least two video frames")
	}
	frames = append(frames, len(data))
	psm := []byte{0, 0, 1, 0xbc, 0, 14, 0xe0, 0xff, 0, 0, 0, 4, 0x1b, 0xe0, 0, 0, 0, 0, 0, 0}
	binary.BigEndian.PutUint32(psm[16:], mpegCRC(psm[:16]))
	for i := 0; i+1 < len(frames); i++ {
		pts := int64(90000) + int64(i)*90000/int64(fps)
		pack := []byte{0, 0, 1, 0xba, 0x44, 0, 4, 0, 4, 1, 0, 0, 3, 0xf8}
		shiftPacketClock(pack, psPacket{packClock: true}, 0, pts)
		if err := writeMedia(output, pack); err != nil {
			return err
		}
		// A video-only system header and explicit H.264 program-stream map.
		if err := writeMedia(output, []byte{0, 0, 1, 0xbb, 0, 9, 0x80, 4, 0xe5, 0, 0x21, 0xff, 0xe0, 0xe0, 7}, psm); err != nil {
			return err
		}
		frame := data[frames[i]:frames[i+1]]
		for len(frame) > 0 {
			n := len(frame)
			if n > 65527 { // 16-bit PES length minus the 8-byte optional header.
				n = 65527
			}
			header := []byte{0, 0, 1, 0xe0, 0, 0, 0x80, 0x80, 5, 0x21, 0, 1, 0, 1}
			binary.BigEndian.PutUint16(header[4:6], uint16(n+8))
			writeClock(header[9:], pts)
			if err := writeMedia(output, header, frame[:n]); err != nil {
				return err
			}
			frame = frame[n:]
		}
	}
	return nil
}

func writeMedia(w io.Writer, chunks ...[]byte) error {
	for _, chunk := range chunks {
		n, err := w.Write(chunk)
		if err != nil {
			return err
		}
		if n != len(chunk) {
			return io.ErrShortWrite
		}
	}
	return nil
}

func mpegCRC(data []byte) uint32 {
	crc := uint32(0xffffffff)
	for _, b := range data {
		crc ^= uint32(b) << 24
		for i := 0; i < 8; i++ {
			if crc&0x80000000 != 0 {
				crc = crc<<1 ^ 0x04c11db7
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

func validateMediaProfile(data []byte, packets []psPacket, units []psUnit) error {
	for _, p := range packets {
		if data[p.start+3] == 0xba && !p.packClock {
			return fmt.Errorf("MPEG-1 PS is not safe for ZLM timestamps; rebuild with scripts/prepare-media.sh")
		}
		if p.video && (data[p.start+6]&0xc0 != 0x80 || len(p.clockOffsets) == 0) {
			return fmt.Errorf("every video PES needs MPEG-2 PTS; rebuild with scripts/prepare-media.sh")
		}
	}
	for _, unit := range units {
		var frame []byte
		for _, p := range unit.packets {
			if p.video {
				h := p.start + 6
				frame = append(frame, data[h+3+int(data[h+2]):p.end]...)
			}
		}
		auds := 0
		for _, nal := range annexBNALs(frame) {
			if frame[nal.payload]&31 == 9 {
				auds++
			}
		}
		if auds != 1 {
			return fmt.Errorf("each video timestamp must contain exactly one H.264 AUD frame; rebuild with scripts/prepare-media.sh")
		}
	}
	return nil
}
