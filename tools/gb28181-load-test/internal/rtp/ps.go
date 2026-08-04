package rtp

import "bytes"

func SplitPS(data []byte, max int) [][]byte {
	if max <= 0 { max = 1200 }; if len(data) == 0 { return nil }; out := make([][]byte, 0, (len(data)+max-1)/max)
	for len(data) > 0 { n := max; if len(data) < n { n = len(data) }; out = append(out, append([]byte(nil), data[:n]...)); data = data[n:] }; return out
}

func isPSStart(data []byte) bool { return bytes.HasPrefix(data, []byte{0, 0, 1, 0xba}) }
