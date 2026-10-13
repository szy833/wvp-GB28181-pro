package rtp

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"syscall"
	"time"
)

type SendDiagnostic struct {
	Sequence  uint64    `json:"sequence"`
	At        time.Time `json:"at"`
	Kind      string    `json:"kind"`
	Operation string    `json:"operation"`
	Error     string    `json:"error"`
	Code      string    `json:"code"`
	Cancelled bool      `json:"cancelled"`
}

type sendDiagnostics struct {
	mu       sync.Mutex
	local    uint64
	remote   uint64
	sequence uint64
	events   []SendDiagnostic
}

// Cancellation does not excuse a reset, timeout, or unrelated source failure.
func (d *sendDiagnostics) record(operation string, err error, cancelled bool) bool {
	kind := "send_error"
	if operation == "source" {
		kind = "source_error"
	}
	if cancelled && (errors.Is(err, context.Canceled) || errors.Is(err, net.ErrClosed) || operation == "source" && errors.Is(err, context.DeadlineExceeded)) {
		kind = "local_cancel"
	} else if operation == "write" && (errors.Is(err, syscall.EPIPE) || errors.Is(err, syscall.ECONNRESET) || errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF)) {
		kind = "remote_close"
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if kind == "local_cancel" {
		d.local++
	}
	if kind == "remote_close" {
		d.remote++
	}
	d.sequence++
	// Fixed text avoids leaking arbitrary source errors (URLs or credentials).
	message := map[string]string{"local_cancel": "local cancellation interrupted operation", "remote_close": "peer closed or reset connection", "source_error": "media source failed", "send_error": "network send failed"}[kind]
	code := "other"
	for _, candidate := range []struct {
		err  error
		code string
	}{
		{context.Canceled, "cancelled"}, {net.ErrClosed, "closed"}, {syscall.EPIPE, "broken_pipe"},
		{syscall.ECONNRESET, "connection_reset"}, {io.ErrUnexpectedEOF, "unexpected_eof"}, {io.EOF, "eof"},
	} {
		if errors.Is(err, candidate.err) {
			code = candidate.code
			break
		}
	}
	var timeout net.Error
	if code == "other" && errors.As(err, &timeout) && timeout.Timeout() {
		code = "timeout"
	}
	d.events = append(d.events, SendDiagnostic{Sequence: d.sequence, At: time.Now().UTC(), Kind: kind, Operation: operation, Error: message, Code: code, Cancelled: cancelled})
	if len(d.events) > 8 {
		d.events = d.events[len(d.events)-8:]
	}
	return kind != "local_cancel"
}

func (d *sendDiagnostics) snapshot() SenderStats {
	d.mu.Lock()
	defer d.mu.Unlock()
	return SenderStats{LocalCancellations: d.local, RemoteClosures: d.remote,
		DiagnosticsDropped: d.sequence - uint64(len(d.events)), Diagnostics: append([]SendDiagnostic(nil), d.events...)}
}
