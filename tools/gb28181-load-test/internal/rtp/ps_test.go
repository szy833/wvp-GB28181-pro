package rtp

import "testing"

func TestSplitPS(t *testing.T) { got := SplitPS(make([]byte, 2501), 1200); if len(got) != 3 || len(got[2]) != 101 { t.Fatalf("%d", len(got)) } }
