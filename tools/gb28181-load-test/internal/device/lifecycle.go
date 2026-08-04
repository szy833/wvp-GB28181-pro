package device

import (
	"context"
	"fmt"
	"time"
)

func WaitForMedia(ctx context.Context, timeout time.Duration, ready <-chan struct{}) error { if timeout <= 0 { timeout = 10 * time.Second }; timer := time.NewTimer(timeout); defer timer.Stop(); select { case <-ready: return nil; case <-timer.C: return fmt.Errorf("media timeout"); case <-ctx.Done(): return ctx.Err() } }
