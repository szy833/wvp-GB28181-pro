package device

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/shizy/gb28181-load-test/internal/sip"
)

func inviteMessage() sip.Message {
	return sip.Message{StartLine: "INVITE sip:channel@127.0.0.1 SIP/2.0", Headers: map[string]string{
		"call-id": "dialog-319", "cseq": "319 INVITE", "via": "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-test",
		"from": "<sip:server@3402000000>;tag=remote", "to": "<sip:channel@3402000000>", "contact": "<sip:server@127.0.0.1:5060>",
	}}
}

func TestInviteResponseEchoesCSeqAndByeUsesDialogURIs(t *testing.T) {
	tr := &fakeTransport{}
	d := New(Identity{DeviceID: "device", ChannelID: "channel"}, DeviceHooks{Transport: tr, Local: "127.0.0.1:25000"})
	if err := d.HandleMessage(context.Background(), inviteMessage()); err != nil {
		t.Fatal(err)
	}
	response, err := sip.ParseMessage(tr.sent[0])
	if err != nil {
		t.Fatal(err)
	}
	if response.Headers["cseq"] != "319 INVITE" {
		t.Fatalf("wrong response CSeq: %q", response.Headers["cseq"])
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_ = d.StopPlay(ctx)
	bye, err := sip.ParseMessage(tr.sent[1])
	if err != nil {
		t.Fatal(err)
	}
	if bye.StartLine != "BYE sip:server@127.0.0.1:5060 SIP/2.0" {
		t.Fatal(bye.StartLine)
	}
	if bye.Headers["to"] != "<sip:server@3402000000>;tag=remote" {
		t.Fatal(bye.Headers["to"])
	}
	if !strings.HasPrefix(bye.Headers["from"], "<sip:channel@3402000000>;tag=") {
		t.Fatal(bye.Headers["from"])
	}
	if !strings.Contains(bye.Headers["via"], "127.0.0.1:25000") {
		t.Fatal(bye.Headers["via"])
	}
}

func TestByeTimeoutIsNotReportedAsSuccess(t *testing.T) {
	d := New(Identity{DeviceID: "device"}, DeviceHooks{Transport: &fakeTransport{}})
	if err := d.HandleMessage(context.Background(), inviteMessage()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := d.StopPlay(ctx); err == nil {
		t.Fatal("unanswered BYE must return an error")
	}
}

func TestUnrelatedAckDoesNotStartMedia(t *testing.T) {
	snd := &fakeSender{}
	d := New(Identity{DeviceID: "device"}, DeviceHooks{Transport: &fakeTransport{}, Sender: snd})
	if err := d.HandleMessage(context.Background(), inviteMessage()); err != nil {
		t.Fatal(err)
	}
	_ = d.HandleMessage(context.Background(), sip.Message{StartLine: "ACK sip:x SIP/2.0", Headers: map[string]string{"call-id": "other", "cseq": "319 ACK"}})
	if snd.starts != 0 {
		t.Fatal("unrelated ACK started media")
	}
}

func TestInviteCannotReplaceDialogWhileByeIsPending(t *testing.T) {
	tr := &fakeTransport{}
	d := New(Identity{DeviceID: "device"}, DeviceHooks{Transport: tr})
	if err := d.HandleMessage(context.Background(), inviteMessage()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	done := make(chan struct{})
	go func() { defer close(done); _ = d.StopPlay(ctx) }()
	deadline := time.Now().Add(time.Second)
	for {
		tr.mu.Lock()
		sent := len(tr.sent)
		tr.mu.Unlock()
		if sent >= 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("BYE was not sent")
		}
		time.Sleep(time.Millisecond)
	}
	next := inviteMessage()
	next.Headers["call-id"] = "next-dialog"
	if err := d.HandleMessage(context.Background(), next); err != nil {
		t.Fatal(err)
	}
	tr.mu.Lock()
	response := string(tr.sent[len(tr.sent)-1])
	tr.mu.Unlock()
	if !strings.HasPrefix(response, "SIP/2.0 486") {
		t.Errorf("INVITE replaced a pending BYE dialog: %s", response)
	}
	<-done
}

func TestOldDeviceByeTimerDoesNotCloseNextDialog(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := New(Identity{DeviceID: "d"}, DeviceHooks{Transport: &fakeTransport{}, Sender: &fakeSender{}, DeviceByeAfter: 10 * time.Millisecond})
	if err := d.HandleMessage(ctx, inviteMessage()); err != nil {
		t.Fatal(err)
	}
	if err := d.StartPlay(ctx); err != nil {
		t.Fatal(err)
	}
	_ = d.HandleMessage(ctx, sip.Message{StartLine: "BYE sip:x SIP/2.0", Headers: map[string]string{"call-id": "dialog-319", "cseq": "2 BYE"}})
	next := inviteMessage()
	next.Headers["call-id"] = "next"
	if err := d.HandleMessage(ctx, next); err != nil {
		t.Fatal(err)
	}
	time.Sleep(30 * time.Millisecond)
	d.mu.Lock()
	active := d.active
	d.mu.Unlock()
	if !active {
		t.Fatal("old dialog timer closed the new dialog")
	}
}
