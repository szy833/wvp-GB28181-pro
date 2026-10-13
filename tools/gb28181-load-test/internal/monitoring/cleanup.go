package monitoring

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

type ResourceScope struct {
	mu        sync.RWMutex
	devices   map[string]bool
	streams   map[string]bool
	listeners map[string]bool
}

func NewResourceScope() *ResourceScope {
	return &ResourceScope{devices: map[string]bool{}, streams: map[string]bool{}, listeners: map[string]bool{}}
}
func (s *ResourceScope) AddDevice(deviceID, channelID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.devices[deviceID] = true
	s.streams[deviceID+"_"+channelID] = true
}
func (s *ResourceScope) AddSSRC(value uint32) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.listeners[fmt.Sprintf("%08X", value)] = true
}
func (s *ResourceScope) AddStream(app, stream string) {
	if app != "rtp" || stream == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.streams[stream] = true
}

// Derive a generation only from the exact RTP origin and an SSRC received by a test device.
// This also accounts for streams whose HTTP start response was lost.
func (s *ResourceScope) learnOrigin(stream ZLMStream) {
	u, err := url.Parse(stream.OriginURL)
	if err != nil || u.Scheme != "rtp" || stream.App != "rtp" {
		return
	}
	parts := strings.Split(strings.TrimPrefix(u.Path, "/"), "/")
	if len(parts) != 2 || parts[0] != "rtp" || !s.matchesListener(parts[1]) {
		return
	}
	listener := strings.Split(parts[1], "_")
	if len(listener) != 2 || stream.Stream != "gb_"+strings.ToLower(listener[1]) {
		return
	}
	s.AddStream(stream.App, stream.Stream)
}
func (s *ResourceScope) matchesStream(app, stream string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return app == "rtp" && (s.streams[stream] || s.listeners[strings.ToUpper(stream)])
}
func (s *ResourceScope) matchesDevice(id string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.devices[id]
}
func (s *ResourceScope) matchesListener(id string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	id = strings.ToUpper(id)
	if s.listeners[id] {
		return true
	}
	parts := strings.Split(id, "_")
	if len(parts) != 2 || len(parts[0]) != 8 || len(parts[1]) != 32 || !s.listeners[parts[0]] {
		return false
	}
	_, err := hex.DecodeString(parts[1])
	return err == nil
}

type ScopedZLM struct {
	Client *ZLMClient
	Scope  *ResourceScope
}

func (s *ScopedZLM) ListStreams(ctx context.Context) (ZLMSnapshot, error) {
	snapshot, err := s.Client.ListStreams(ctx)
	snapshot.ScopeFiltered = true
	snapshot.NodeTotalStreams = snapshot.TotalStreams
	snapshot.TotalStreams, snapshot.BusinessStreams, snapshot.RTPStreams = 0, 0, 0
	filtered := make([]ZLMStream, 0)
	seen := map[string]bool{}
	for _, stream := range snapshot.Streams {
		s.Scope.learnOrigin(stream)
		if !s.Scope.matchesStream(stream.App, stream.Stream) {
			continue
		}
		filtered = append(filtered, stream)
		snapshot.TotalStreams++
		key := stream.VHost + "\x00" + stream.App + "\x00" + stream.Stream
		if !seen[key] {
			seen[key] = true
			snapshot.BusinessStreams++
			if isRTPStream(stream) {
				snapshot.RTPStreams++
			}
		}
	}
	snapshot.Streams = filtered
	return snapshot, err
}

// Only read-only APIs are used. No global close/delete API belongs in cleanup checks.
func fetchJSON(ctx context.Context, endpoint, token string, timeout time.Duration, target any) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return fmt.Errorf("invalid monitoring endpoint")
	}
	if token != "" {
		req.Header.Set("access-token", token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("monitoring request failed: %s", safeHTTPError(err))
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("monitoring HTTP status %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20+1))
	if err != nil || len(data) > 8<<20 {
		return fmt.Errorf("invalid monitoring response size")
	}
	if json.Unmarshal(data, target) != nil {
		return fmt.Errorf("invalid monitoring JSON")
	}
	return nil
}

func (s *ScopedZLM) CountListeners(ctx context.Context) (int, error) {
	u, err := url.Parse(strings.TrimRight(s.Client.BaseURL, "/") + "/index/api/listRtpServer")
	if err != nil {
		return 0, fmt.Errorf("invalid ZLM endpoint")
	}
	q := u.Query()
	q.Set("secret", s.Client.Secret)
	u.RawQuery = q.Encode()
	var result struct {
		Code *int            `json:"code"`
		Data json.RawMessage `json:"data"`
	}
	if err := fetchJSON(ctx, u.String(), "", s.Client.Timeout, &result); err != nil {
		return 0, err
	}
	if result.Code == nil || *result.Code != 0 {
		return 0, fmt.Errorf("ZLM listener data unavailable")
	}
	// ZLM omits data when listRtpServer succeeds with no listeners.
	if len(result.Data) == 0 {
		return 0, nil
	}
	var entries []struct {
		Stream string `json:"stream_id"`
	}
	if json.Unmarshal(result.Data, &entries) != nil || entries == nil {
		return 0, fmt.Errorf("invalid ZLM listener data")
	}
	n := 0
	for _, e := range entries {
		if strings.TrimSpace(e.Stream) == "" {
			return 0, fmt.Errorf("invalid ZLM listener entry")
		}
		if s.Scope.matchesListener(e.Stream) {
			n++
		}
	}
	return n, nil
}

type WVPClient struct {
	BaseURL, Token string
	Timeout        time.Duration
	Scope          *ResourceScope
}

func (c *WVPClient) CountSessions(ctx context.Context) (int, error) {
	var result struct {
		Code  json.RawMessage `json:"code"`
		Data  json.RawMessage `json:"data"`
		Count *int            `json:"count"`
	}
	if err := fetchJSON(ctx, strings.TrimRight(c.BaseURL, "/")+"/api/play/ssrc", c.Token, c.Timeout, &result); err != nil {
		return 0, err
	}
	if len(result.Code) > 0 {
		var code *int
		if json.Unmarshal(result.Code, &code) != nil || code == nil || *code != 0 {
			return 0, fmt.Errorf("WVP session data unavailable")
		}
	}
	if len(result.Data) == 0 || string(result.Data) == "null" {
		return 0, fmt.Errorf("WVP session data unavailable")
	}
	data := result.Data
	count := result.Count
	if strings.HasPrefix(strings.TrimSpace(string(data)), "{") {
		var nested struct {
			Data  json.RawMessage `json:"data"`
			Count *int            `json:"count"`
		}
		if json.Unmarshal(data, &nested) != nil || nested.Count == nil {
			return 0, fmt.Errorf("invalid WVP session envelope")
		}
		data, count = nested.Data, nested.Count
	}
	var sessions []struct {
		DeviceID   string `json:"deviceId"`
		PlatformID string `json:"platformId"`
		StreamID   string `json:"streamId"`
	}
	if json.Unmarshal(data, &sessions) != nil || sessions == nil || count != nil && *count != len(sessions) {
		return 0, fmt.Errorf("invalid WVP session data")
	}
	n := 0
	for _, e := range sessions {
		// Platform sessions may have no deviceId; /ssrc still supplies streamId.
		if strings.TrimSpace(e.DeviceID) == "" && strings.TrimSpace(e.PlatformID) == "" && strings.TrimSpace(e.StreamID) == "" {
			return 0, fmt.Errorf("invalid WVP session entry")
		}
		if c.Scope.matchesDevice(e.DeviceID) || c.Scope.matchesStream("rtp", e.StreamID) {
			n++
		}
	}
	return n, nil
}

type CleanupPoller struct {
	ZLM      *ScopedZLM
	WVP      *WVPClient
	Interval time.Duration
}

func (p *CleanupPoller) Wait(ctx context.Context) (ZLMSnapshot, error) {
	started := time.Now()
	interval := p.Interval
	if interval <= 0 {
		interval = time.Second
	}
	var snapshot ZLMSnapshot
	zeroRounds := 0
	for {
		snapshot, _ = p.ZLM.ListStreams(ctx)
		snapshot.CleanupErrors = nil
		n, err := p.ZLM.CountListeners(ctx)
		snapshot.ResidualListeners, snapshot.ListenersAvailable = n, err == nil
		if err != nil {
			snapshot.CleanupErrors = append(snapshot.CleanupErrors, "ZLM listener check: "+err.Error())
		}
		if p.WVP != nil {
			n, err := p.WVP.CountSessions(ctx)
			snapshot.ResidualSessions, snapshot.SessionsAvailable = n, err == nil
			if err != nil {
				snapshot.CleanupErrors = append(snapshot.CleanupErrors, "WVP session check: "+err.Error())
			}
		}
		snapshot.CleanupSeconds = time.Since(started).Seconds()
		zero := snapshot.Available && snapshot.BusinessStreams == 0 && snapshot.ListenersAvailable && snapshot.ResidualListeners == 0 && (p.WVP == nil || snapshot.SessionsAvailable && snapshot.ResidualSessions == 0)
		if zero {
			zeroRounds++
		} else {
			zeroRounds = 0
		}
		if zeroRounds >= 2 {
			return snapshot, nil
		}
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			snapshot.CleanupTimedOut = true
			snapshot.CleanupSeconds = time.Since(started).Seconds()
			return snapshot, nil
		case <-timer.C:
		}
	}
}
