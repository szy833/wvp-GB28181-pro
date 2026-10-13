package device

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/shizy/gb28181-load-test/internal/rtp"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

type State string

const (
	StateCreated  State = "CREATED"
	StateOnline   State = "ONLINE"
	StatePlaying  State = "PLAYING"
	StateStopping State = "STOPPING"
	StateFailed   State = "FAILED"
	StateClosed   State = "CLOSED"
)

type MediaSender interface {
	Start(context.Context) error
	Stop() error
}
type DeviceEvent struct {
	Packets, Bytes, MediaErrors                            uint64
	LocalCancellations, RemoteClosures, DiagnosticsDropped uint64
	Diagnostics                                            []rtp.SendDiagnostic
	CallID                                                 string
	State                                                  string
	Name                                                   string
	DeviceID                                               string
	ChannelID                                              string
	Error                                                  string
	At                                                     time.Time
}
type EventSink interface{ OnDeviceEvent(DeviceEvent) }
type DeviceHooks struct {
	Transport         sip.Transport
	Peer              net.Addr
	Local             string
	Target            string
	Username          string
	Password          string
	Realm             string
	ServerID          string
	Name              string
	Manufacturer      string
	Model             string
	NoMedia           bool
	DeviceByeAfter    time.Duration
	Sender            MediaSender
	SenderFactory     func(sip.SDPResponse) (MediaSender, string, error)
	RegisterInterval  time.Duration
	KeepaliveInterval time.Duration
	MediaTimeout      time.Duration
	ByeTimeout        time.Duration
}

type Device struct {
	Identity       Identity
	Hooks          DeviceHooks
	mu             sync.Mutex
	eventMu        sync.RWMutex
	eventSink      EventSink
	state          State
	active         bool
	dialog         sip.Dialog
	runCancel      context.CancelFunc
	wg             sync.WaitGroup
	byeSent        bool
	digest         sip.DigestChallenge
	registerCSeq   uint32
	messageCSeq    uint32
	nonceCount     uint32
	mediaRunning   bool
	inviteResponse []byte
	byeReply       chan error
	stopMu         sync.Mutex
	closeOnce      sync.Once
	closeDone      chan struct{}
	closeErr       error
	lastStats      rtp.SenderStats
	stopping       bool
	shuttingDown   bool
}

func New(id Identity, hooks DeviceHooks) *Device {
	if hooks.RegisterInterval <= 0 {
		hooks.RegisterInterval = time.Hour
	}
	if hooks.KeepaliveInterval <= 0 {
		hooks.KeepaliveInterval = 30 * time.Second
	}
	if hooks.MediaTimeout <= 0 {
		hooks.MediaTimeout = 10 * time.Second
	}
	if hooks.ByeTimeout <= 0 {
		hooks.ByeTimeout = 3 * time.Second
	}
	if hooks.Name == "" {
		hooks.Name = id.DeviceID
	}
	if hooks.Manufacturer == "" {
		hooks.Manufacturer = "gb28181-load-test"
	}
	if hooks.Model == "" {
		hooks.Model = "virtual-camera"
	}
	return &Device{Identity: id, Hooks: hooks, state: StateCreated, registerCSeq: 1, messageCSeq: 1, closeDone: make(chan struct{})}
}
func (d *Device) SetEventSink(sink EventSink) {
	d.eventMu.Lock()
	d.eventSink = sink
	d.eventMu.Unlock()
}
func (d *Device) emit(name, channelID, errText string) {
	d.eventMu.RLock()
	sink := d.eventSink
	d.eventMu.RUnlock()
	if sink != nil {
		sink.OnDeviceEvent(DeviceEvent{Name: name, DeviceID: d.Identity.DeviceID, ChannelID: channelID, Error: errText, At: time.Now()})
	}
}

func (d *Device) emitMediaStats() {
	d.mu.Lock()
	measured, ok := d.Hooks.Sender.(interface{ Stats() rtp.SenderStats })
	if !ok {
		d.mu.Unlock()
		return
	}
	stats := measured.Stats()
	prev := d.lastStats
	d.lastStats = stats
	callID, state := d.dialog.CallID, string(d.state)
	d.mu.Unlock()
	if stats.Packets < prev.Packets || stats.Bytes < prev.Bytes || stats.Errors < prev.Errors || stats.LocalCancellations < prev.LocalCancellations || stats.RemoteClosures < prev.RemoteClosures || stats.DiagnosticsDropped < prev.DiagnosticsDropped {
		return
	}
	e := DeviceEvent{Name: "media_stats", DeviceID: d.Identity.DeviceID, ChannelID: d.Identity.ChannelID, At: time.Now(), Packets: stats.Packets - prev.Packets, Bytes: stats.Bytes - prev.Bytes, MediaErrors: stats.Errors - prev.Errors}
	e.LocalCancellations = stats.LocalCancellations - prev.LocalCancellations
	e.RemoteClosures = stats.RemoteClosures - prev.RemoteClosures
	e.DiagnosticsDropped = stats.DiagnosticsDropped - prev.DiagnosticsDropped
	e.CallID, e.State = callID, state
	var lastSequence uint64
	if len(prev.Diagnostics) > 0 {
		lastSequence = prev.Diagnostics[len(prev.Diagnostics)-1].Sequence
	}
	for _, detail := range stats.Diagnostics {
		if detail.Sequence > lastSequence {
			e.Diagnostics = append(e.Diagnostics, detail)
		}
	}
	if e.Packets == 0 && e.Bytes == 0 && e.MediaErrors == 0 && e.LocalCancellations == 0 && e.RemoteClosures == 0 && len(e.Diagnostics) == 0 {
		return
	}
	d.eventMu.RLock()
	sink := d.eventSink
	d.eventMu.RUnlock()
	if sink != nil {
		sink.OnDeviceEvent(e)
	}
}
func (d *Device) State() State       { d.mu.Lock(); defer d.mu.Unlock(); return d.state }
func (d *Device) transition(s State) { d.mu.Lock(); d.state = s; d.mu.Unlock() }

func (d *Device) Run(ctx context.Context) error {
	if d.Hooks.Transport == nil {
		return fmt.Errorf("SIP transport is nil")
	}
	// Keep receiving final BYE responses after the workload context expires.
	runCtx, cancel := context.WithCancel(context.Background())
	d.runCancel = cancel
	d.wg.Add(1)
	go d.loop(runCtx)
	go func() {
		select {
		case <-ctx.Done():
			_ = d.Close()
		case <-d.closeDone:
		}
	}()
	return nil
}

func (d *Device) loop(ctx context.Context) {
	defer d.wg.Done()
	ticker := time.NewTicker(d.Hooks.RegisterInterval)
	defer ticker.Stop()
	keepaliveTicker := time.NewTicker(d.Hooks.KeepaliveInterval)
	defer keepaliveTicker.Stop()
	statsTicker := time.NewTicker(time.Second)
	defer statsTicker.Stop()
	d.sendRegister(ctx)
	d.transition(StateOnline)
	messages := make(chan sip.Message, 1)
	go func() {
		for {
			msg, err := d.Hooks.Transport.Receive(ctx)
			if err != nil {
				return
			}
			select {
			case messages <- msg:
			case <-ctx.Done():
				return
			}
		}
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			d.sendRegister(ctx)
		case <-keepaliveTicker.C:
			d.sendKeepalive(ctx)
		case <-statsTicker.C:
			d.emitMediaStats()
		case msg := <-messages:
			_ = d.HandleMessage(ctx, msg)
		}
	}
}

func (d *Device) sendRegister(ctx context.Context) {
	d.emit("register_attempt", "", "")
	target := d.Hooks.Target
	if target == "" {
		target = "sip:server@127.0.0.1"
	}
	d.mu.Lock()
	cseq := d.registerCSeq
	if cseq == 0 {
		cseq = 1
	}
	d.registerCSeq = cseq + 1
	auth := ""
	if d.digest.Nonce != "" && d.Hooks.Username != "" {
		d.nonceCount++
		auth, _ = sip.BuildDigestAuthorization("REGISTER", target, d.Hooks.Username, d.Hooks.Password, d.digest, d.nonceCount)
	}
	local := d.Hooks.Local
	d.mu.Unlock()
	b, err := sip.BuildRegisterAuthCSeq(d.Identity, target, local, int(d.Hooks.RegisterInterval.Seconds()), auth, cseq)
	if err != nil {
		d.emit("register_error", "", err.Error())
		return
	}
	if err := d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b); err != nil {
		d.emit("register_error", "", err.Error())
		return
	}
	d.emit("register_request", "", "")
}

func (d *Device) sendKeepalive(ctx context.Context) {
	target := d.Hooks.Target
	if target == "" {
		target = "sip:server@127.0.0.1"
	}
	d.mu.Lock()
	cseq := d.messageCSeq
	d.messageCSeq++
	local := d.Hooks.Local
	d.mu.Unlock()
	body := sip.BuildKeepaliveXML(d.Identity.DeviceID, d.Hooks.Manufacturer)
	b, err := sip.BuildMessage(d.Identity, target, local, body, cseq)
	if err == nil {
		_ = d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b)
	}
}

func (d *Device) HandleMessage(ctx context.Context, msg sip.Message) error {
	if strings.HasPrefix(msg.StartLine, "SIP/2.0 ") && strings.HasSuffix(strings.ToUpper(msg.Headers["cseq"]), " BYE") {
		n, err := sip.ParseCSeq(msg.Headers["cseq"], "BYE")
		parts := strings.Fields(msg.StartLine)
		if err != nil || len(parts) < 2 {
			return nil
		}
		status, err := strconv.Atoi(parts[1])
		if err != nil || status < 200 {
			return nil
		}
		d.mu.Lock()
		reply := d.byeReply
		match := msg.Headers["call-id"] == d.dialog.CallID && n == d.dialog.CSeq
		d.mu.Unlock()
		if reply != nil && match {
			var outcome error
			if status >= 300 {
				outcome = fmt.Errorf("BYE rejected with status %d", status)
			}
			select {
			case reply <- outcome:
			default:
			}
		}
		return nil
	}
	if strings.HasPrefix(msg.StartLine, "SIP/2.0 401") && strings.Contains(strings.ToUpper(msg.Headers["cseq"]), "REGISTER") {
		return d.handleRegisterChallenge(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "SIP/2.0 200") && strings.Contains(strings.ToUpper(msg.Headers["cseq"]), "REGISTER") {
		d.emit("register_success", "", "")
		return nil
	}
	if strings.HasPrefix(msg.StartLine, "INVITE ") {
		return d.handleInvite(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "ACK ") {
		n, err := sip.ParseCSeq(msg.Headers["cseq"], "ACK")
		d.mu.Lock()
		matches := err == nil && msg.Headers["call-id"] == d.dialog.CallID && n == d.dialog.InviteCSeq
		d.mu.Unlock()
		if !matches {
			return nil
		}
		return d.startMedia(ctx)
	}
	if strings.HasPrefix(msg.StartLine, "BYE ") {
		return d.handleRemoteBye(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "MESSAGE ") {
		return d.handleQuery(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "OPTIONS ") {
		return d.sendResponse(ctx, msg, "200 OK", "")
	}
	return nil
}

func (d *Device) handleRegisterChallenge(ctx context.Context, msg sip.Message) error {
	challenge, err := sip.ParseDigestChallenge(msg.Headers["www-authenticate"])
	if err != nil {
		d.emit("register_error", "", err.Error())
		return err
	}
	d.emit("register_challenge", "", "")
	d.mu.Lock()
	d.digest = challenge
	d.mu.Unlock()
	d.sendRegister(ctx)
	return nil
}

func (d *Device) handleQuery(ctx context.Context, msg sip.Message) error {
	q, err := sip.ParseQuery(msg.Body)
	if err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	if strings.EqualFold(q.CmdType, "catalog") {
		d.emit("catalog_query", d.Identity.ChannelID, "")
	}
	if err := d.sendResponse(ctx, msg, "200 OK", ""); err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	target := d.Hooks.Target
	if target == "" {
		target = strings.TrimSpace(strings.Split(msg.Headers["from"], ";")[0])
		target = strings.Trim(target, "<>")
	}
	d.mu.Lock()
	cseq := d.messageCSeq
	d.messageCSeq++
	d.mu.Unlock()
	var body []byte
	switch strings.ToLower(q.CmdType) {
	case "catalog":
		body = sip.BuildCatalogXML(d.Hooks.ServerID, q, sip.CatalogItem{DeviceID: d.Identity.ChannelID, Name: d.Hooks.Name, Manufacturer: d.Hooks.Manufacturer, Model: d.Hooks.Model, Status: "ON", ParentID: d.Hooks.ServerID})
	case "deviceinfo":
		body = sip.BuildDeviceInfoXML(d.Hooks.ServerID, q, d.Hooks.Name, d.Hooks.Manufacturer, d.Hooks.Model)
	case "devicestatus":
		body = sip.BuildDeviceStatusXML(d.Hooks.ServerID, q)
	default:
		return nil
	}
	b, err := sip.BuildMessage(d.Identity, target, d.Hooks.Local, body, cseq)
	if err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	if err := d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b); err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	d.emit(strings.ToLower(q.CmdType)+"_response", d.Identity.ChannelID, "")
	return nil
}

// Called only after a successful WVP response contains the actual stream identity.
func (d *Device) ConfirmMedia() { d.emit("media_confirmed", d.Identity.ChannelID, "") }

func (d *Device) sendResponse(ctx context.Context, msg sip.Message, status, body string) error {
	to := msg.Headers["to"]
	if sip.HeaderTag(to) == "" {
		to += ";tag=loadtest"
	}
	response := []byte(fmt.Sprintf("SIP/2.0 %s\r\nVia: %s\r\nFrom: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: %s\r\nContent-Length: %d\r\n\r\n%s", status, msg.Headers["via"], msg.Headers["from"], to, msg.Headers["call-id"], msg.Headers["cseq"], len(body), body))
	return d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response)
}

func (d *Device) handleRemoteBye(ctx context.Context, msg sip.Message) error {
	d.mu.Lock()
	if msg.Headers["call-id"] != d.dialog.CallID {
		d.mu.Unlock()
		return d.sendResponse(ctx, msg, "481 Call/Transaction Does Not Exist", "")
	}
	active := d.active
	d.active = false
	d.mediaRunning = false
	sender := d.Hooks.Sender
	d.mu.Unlock()
	if active && sender != nil {
		_ = sender.Stop()
		d.emitMediaStats()
	}
	d.transition(StateOnline)
	d.emit("device_bye", d.Identity.ChannelID, "")
	response := []byte(fmt.Sprintf("SIP/2.0 200 OK\r\nVia: %s\r\nFrom: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: %s\r\nContent-Length: 0\r\n\r\n", msg.Headers["via"], msg.Headers["from"], msg.Headers["to"], msg.Headers["call-id"], msg.Headers["cseq"]))
	if d.Hooks.Transport != nil {
		return d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response)
	}
	return nil
}

func (d *Device) handleInvite(ctx context.Context, msg sip.Message) error {
	n, err := sip.ParseCSeq(msg.Headers["cseq"], "INVITE")
	if err != nil {
		return d.sendResponse(ctx, msg, "400 Bad Request", "")
	}
	d.mu.Lock()
	if d.stopping || d.shuttingDown {
		d.mu.Unlock()
		return d.sendResponse(ctx, msg, "486 Busy Here", "")
	}
	if d.active {
		response := append([]byte(nil), d.inviteResponse...)
		repeat := msg.Headers["call-id"] == d.dialog.CallID && n == d.dialog.InviteCSeq
		d.mu.Unlock()
		if repeat && len(response) > 0 {
			return d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response)
		}
		return d.sendResponse(ctx, msg, "486 Busy Here", "")
	}
	callID := msg.Headers["call-id"]
	if callID == "" {
		callID = d.Identity.CallID
	}
	target := sip.HeaderURI(msg.Headers["contact"])
	if target == "" {
		target = sip.HeaderURI(msg.Headers["from"])
	}
	localTag := sip.HeaderTag(msg.Headers["to"])
	if localTag == "" {
		localTag = "loadtest-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	d.dialog = sip.Dialog{Target: target, Local: sip.HeaderURI(msg.Headers["to"]), Remote: sip.HeaderURI(msg.Headers["from"]), LocalTag: localTag, RemoteTag: sip.HeaderTag(msg.Headers["from"]), CallID: callID, CSeq: 1, InviteCSeq: n, Via: d.Hooks.Local}
	d.dialog.NextHop = sip.HeaderURI(msg.Headers["contact"])
	if route := msg.Headers["record-route"]; route != "" {
		d.dialog.Routes = strings.Split(route, ",")
	}
	d.active = true
	d.byeSent = false
	d.mediaRunning = false
	d.mu.Unlock()
	success := false
	var sender MediaSender
	defer func() {
		if !success {
			if sender != nil {
				_ = sender.Stop()
			}
			d.mu.Lock()
			d.active = false
			d.mu.Unlock()
		}
	}()
	answer := ""
	if d.Hooks.SenderFactory != nil {
		offer, err := sip.ParseSDP(msg.Body)
		if err != nil {
			d.transition(StateFailed)
			d.emit("invite_error", d.Identity.ChannelID, err.Error())
			return err
		}
		sender, answer, err = d.Hooks.SenderFactory(offer)
		if err != nil {
			d.transition(StateFailed)
			d.emit("invite_error", d.Identity.ChannelID, err.Error())
			return err
		}
		if d.Hooks.NoMedia {
			sender = nil
		}
		d.mu.Lock()
		if d.stopping || d.shuttingDown {
			d.mu.Unlock()
			return fmt.Errorf("device is stopping")
		}
		d.Hooks.Sender = sender
		d.lastStats = rtp.SenderStats{}
		d.mu.Unlock()
	}
	to := msg.Headers["to"]
	if sip.HeaderTag(to) == "" {
		to += ";tag=" + localTag
	}
	contact := "sip:" + d.Identity.DeviceID + "@" + d.Hooks.Local
	response := []byte(fmt.Sprintf("SIP/2.0 200 OK\r\nVia: %s\r\nFrom: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: %s\r\nContact: <%s>\r\nContent-Type: application/sdp\r\nContent-Length: %d\r\n\r\n%s", msg.Headers["via"], msg.Headers["from"], to, callID, msg.Headers["cseq"], contact, len(answer), answer))
	if err := d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response); err != nil {
		d.transition(StateFailed)
		d.emit("invite_error", d.Identity.ChannelID, err.Error())
		return err
	}
	d.mu.Lock()
	d.inviteResponse = response
	d.mu.Unlock()
	success = true
	d.transition(StatePlaying)
	d.emit("invite_success", d.Identity.ChannelID, "")
	return nil
}

func (d *Device) StartPlay(ctx context.Context) error { return d.startMedia(ctx) }
func (d *Device) startMedia(ctx context.Context) error {
	d.mu.Lock()
	active := d.active
	if d.mediaRunning {
		d.mu.Unlock()
		return nil
	}
	sender := d.Hooks.Sender
	if active && sender != nil {
		d.mediaRunning = true
	}
	d.mu.Unlock()
	if !active {
		err := fmt.Errorf("no active invite")
		d.emit("media_start_error", d.Identity.ChannelID, err.Error())
		return err
	}
	if sender == nil {
		return nil
	}
	if err := sender.Start(ctx); err != nil {
		d.transition(StateFailed)
		d.emit("media_start_error", d.Identity.ChannelID, err.Error())
		return err
	}
	d.emit("media_started", d.Identity.ChannelID, "")
	if d.Hooks.DeviceByeAfter > 0 {
		byeCtx := ctx
		d.mu.Lock()
		callID := d.dialog.CallID
		d.mu.Unlock()
		go func() {
			timer := time.NewTimer(d.Hooks.DeviceByeAfter)
			defer timer.Stop()
			select {
			case <-timer.C:
				_ = d.stopPlay(byeCtx, callID)
			case <-byeCtx.Done():
			}
		}()
	}
	return nil
}

func (d *Device) StopPlay(ctx context.Context) error {
	return d.stopPlay(ctx, "")
}

func (d *Device) stopPlay(ctx context.Context, expectedCallID string) error {
	d.stopMu.Lock()
	defer d.stopMu.Unlock()
	d.mu.Lock()
	if !d.active || expectedCallID != "" && d.dialog.CallID != expectedCallID {
		d.mu.Unlock()
		return nil
	}
	d.active = false
	d.stopping = true
	d.mediaRunning = false
	dialog := d.dialog
	already := d.byeSent
	d.byeSent = true
	sender := d.Hooks.Sender
	reply := make(chan error, 1)
	d.byeReply = reply
	d.mu.Unlock()
	defer func() { d.mu.Lock(); d.byeReply = nil; d.stopping = false; d.mu.Unlock(); d.transition(StateOnline) }()
	if sender != nil {
		_ = sender.Stop()
		d.emitMediaStats()
	}
	if !already && d.Hooks.Transport != nil {
		b, err := sip.BuildBye(dialog)
		if err != nil {
			d.emit("device_bye_error", d.Identity.ChannelID, err.Error())
			return err
		}
		byeCtx, cancel := context.WithTimeout(ctx, d.Hooks.ByeTimeout)
		defer cancel()
		peer := d.Hooks.Peer
		nextHop := dialog.NextHop
		if len(dialog.Routes) > 0 {
			nextHop = sip.HeaderURI(dialog.Routes[0])
		}
		if nextHop != "" {
			addr, err := sip.ResolveUDPURI(nextHop)
			if err != nil {
				d.emit("device_bye_error", d.Identity.ChannelID, "invalid SIP dialog next hop")
				return fmt.Errorf("invalid SIP dialog next hop")
			}
			peer = addr
		}
		if err := d.Hooks.Transport.Send(byeCtx, peer, b); err != nil {
			d.emit("device_bye_error", d.Identity.ChannelID, err.Error())
			return err
		}
		d.emit("device_bye_sent", d.Identity.ChannelID, "")
		d.transition(StateStopping)
		timer := time.NewTimer(500 * time.Millisecond)
		defer timer.Stop()
		interval := 500 * time.Millisecond
		for {
			select {
			case err := <-reply:
				if err != nil {
					d.emit("device_bye_error", d.Identity.ChannelID, err.Error())
				} else {
					d.emit("device_bye_success", d.Identity.ChannelID, "")
				}
				return err
			case <-byeCtx.Done():
				d.emit("device_bye_timeout", d.Identity.ChannelID, byeCtx.Err().Error())
				return byeCtx.Err()
			case <-timer.C:
				if err := d.Hooks.Transport.Send(byeCtx, peer, b); err != nil {
					d.emit("device_bye_error", d.Identity.ChannelID, err.Error())
					return err
				}
				interval *= 2
				timer.Reset(interval)
			}
		}
	}
	d.transition(StateStopping)
	d.transition(StateOnline)
	return nil
}

func (d *Device) Close() error {
	d.closeOnce.Do(func() {
		defer close(d.closeDone)
		d.mu.Lock()
		d.shuttingDown = true
		d.mu.Unlock()
		_ = d.StopPlay(context.Background())
		if d.runCancel != nil {
			d.runCancel()
		}
		if d.Hooks.Transport != nil {
			d.closeErr = d.Hooks.Transport.Close()
		}
		d.wg.Wait()
		d.transition(StateClosed)
	})
	return d.closeErr
}
