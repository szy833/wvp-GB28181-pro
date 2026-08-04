package device

import (
	"context"
	"testing"
	"time"
)

func TestWaitForMediaTimeout(t *testing.T) { if err := WaitForMedia(context.Background(), time.Millisecond, make(chan struct{})); err == nil { t.Fatal("expected timeout") } }
