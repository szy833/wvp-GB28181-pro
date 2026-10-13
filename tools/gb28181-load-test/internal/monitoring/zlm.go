package monitoring

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ZLMStream is the subset of a ZLMediaKit media item needed by the report.
// Keeping the original identifiers makes it possible to inspect residual
// streams without exposing the ZLM authentication secret.
type ZLMStream struct {
	Schema        string `json:"schema,omitempty"`
	VHost         string `json:"vhost,omitempty"`
	App           string `json:"app,omitempty"`
	Stream        string `json:"stream,omitempty"`
	OriginType    int    `json:"originType,omitempty"`
	OriginTypeStr string `json:"originTypeStr,omitempty"`
	OriginURL     string `json:"originUrl,omitempty"`
	Readers       int    `json:"readers,omitempty"`
}

// ZLMSnapshot represents one point-in-time getMediaList response.
type ZLMSnapshot struct {
	ScopeFiltered      bool        `json:"scopeFiltered"`
	NodeTotalStreams   int         `json:"nodeTotalStreams"`
	ResidualListeners  int         `json:"residualListeners"`
	ListenersAvailable bool        `json:"listenersAvailable"`
	ResidualSessions   int         `json:"residualSessions"`
	SessionsAvailable  bool        `json:"sessionsAvailable"`
	CleanupSeconds     float64     `json:"cleanupSeconds"`
	CleanupTimedOut    bool        `json:"cleanupTimedOut"`
	CleanupErrors      []string    `json:"cleanupErrors,omitempty"`
	MediaServerID      string      `json:"mediaServerId,omitempty"`
	Available          bool        `json:"available"`
	SampledAt          time.Time   `json:"sampledAt"`
	TotalStreams       int         `json:"totalStreams"`
	RTPStreams         int         `json:"rtpStreams"`
	BusinessStreams    int         `json:"businessStreams"`
	Streams            []ZLMStream `json:"streams,omitempty"`
	APILatencySeconds  float64     `json:"apiLatencySeconds"`
	Error              string      `json:"error,omitempty"`
	HTTPStatus         int         `json:"httpStatus,omitempty"`
}

// ZLMClient queries ZLMediaKit's getMediaList API. Secret is only sent as a
// query parameter and is never included in errors or snapshots.
type ZLMClient struct {
	BaseURL       string
	Secret        string
	MediaServerID string
	HTTPClient    *http.Client
	Timeout       time.Duration
}

// NewZLMClient constructs a client with bounded request time. The URL is
// normalized so callers can provide either a host or an API base path.
func NewZLMClient(baseURL, secret, mediaServerID string, timeout time.Duration) *ZLMClient {
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	return &ZLMClient{BaseURL: strings.TrimRight(baseURL, "/"), Secret: secret,
		MediaServerID: mediaServerID, Timeout: timeout,
		HTTPClient: &http.Client{Timeout: timeout}}
}

func (c *ZLMClient) mediaServerID() string {
	if c == nil {
		return ""
	}
	return c.MediaServerID
}

// ListStreams fetches and summarizes ZLM media streams.
func (c *ZLMClient) ListStreams(ctx context.Context) (snapshot ZLMSnapshot, retErr error) {
	started := time.Now()
	snapshot = ZLMSnapshot{SampledAt: started, MediaServerID: c.mediaServerID()}
	defer func() { snapshot.APILatencySeconds = time.Since(started).Seconds() }()
	if c == nil || strings.TrimSpace(c.BaseURL) == "" {
		snapshot.Error = "ZLM URL is empty"
		return snapshot, fmt.Errorf("zlm URL is empty")
	}
	endpoint := strings.TrimRight(c.BaseURL, "/") + "/index/api/getMediaList"
	u, err := url.Parse(endpoint)
	if err != nil {
		snapshot.Error = "invalid ZLM URL"
		return snapshot, fmt.Errorf("parse ZLM URL: %w", err)
	}
	q := u.Query()
	if c.Secret != "" {
		q.Set("secret", c.Secret)
	}
	u.RawQuery = q.Encode()
	reqCtx := ctx
	var cancel context.CancelFunc
	if c.Timeout > 0 {
		reqCtx, cancel = context.WithTimeout(ctx, c.Timeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(reqCtx, http.MethodGet, u.String(), nil)
	if err != nil {
		snapshot.Error = "build ZLM request failed"
		return snapshot, err
	}
	client := c.HTTPClient
	if client == nil {
		client = &http.Client{Timeout: c.Timeout}
	}
	resp, err := client.Do(req)
	if err != nil {
		snapshot.Error = safeHTTPError(err)
		return snapshot, err
	}
	defer resp.Body.Close()
	snapshot.HTTPStatus = resp.StatusCode
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		snapshot.Error = "read ZLM response failed"
		return snapshot, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		snapshot.Error = fmt.Sprintf("ZLM HTTP status %d", resp.StatusCode)
		return snapshot, fmt.Errorf("ZLM HTTP status %d", resp.StatusCode)
	}
	var payload zlmMediaListResponse
	if err := json.Unmarshal(body, &payload); err != nil {
		snapshot.Error = "decode ZLM response failed"
		return snapshot, fmt.Errorf("decode ZLM response: %w", err)
	}
	if payload.Code != 0 {
		snapshot.Error = fmt.Sprintf("ZLM API code %d", payload.Code)
		return snapshot, fmt.Errorf("ZLM API code %d", payload.Code)
	}
	snapshot.Available = true
	snapshot.TotalStreams = len(payload.Data)
	snapshot.Streams = make([]ZLMStream, 0, len(payload.Data))
	businessStreams := make(map[string]struct{})
	rtpStreams := make(map[string]struct{})
	for _, item := range payload.Data {
		stream := item.stream()
		snapshot.Streams = append(snapshot.Streams, stream)
		if isRTPStream(stream) {
			key := strings.ToLower(stream.App) + "\x00" + stream.Stream
			if _, exists := rtpStreams[key]; !exists {
				rtpStreams[key] = struct{}{}
				snapshot.RTPStreams++
			}
		}
		if isBusinessStream(stream) {
			// ZLM exposes one media item per output schema (rtsp, hls,
			// fmp4...). Count a business stream once, not once per schema.
			businessKey := strings.ToLower(stream.App) + "\x00" + stream.Stream
			if _, exists := businessStreams[businessKey]; !exists {
				businessStreams[businessKey] = struct{}{}
				snapshot.BusinessStreams++
			}
		}
	}
	return snapshot, nil
}

type zlmMediaListResponse struct {
	Code int             `json:"code"`
	Data []zlmMediaEntry `json:"data"`
}

// ZLM has used both readerCount and totalReaderCount in different versions.
type zlmMediaEntry struct {
	Schema           string `json:"schema"`
	VHost            string `json:"vhost"`
	App              string `json:"app"`
	Stream           string `json:"stream"`
	OriginType       int    `json:"originType"`
	OriginTypeStr    string `json:"originTypeStr"`
	OriginURL        string `json:"originUrl"`
	ReaderCount      int    `json:"readerCount"`
	TotalReaderCount int    `json:"totalReaderCount"`
	Readers          int    `json:"readers"`
}

func (e zlmMediaEntry) stream() ZLMStream {
	readers := e.ReaderCount
	if readers == 0 {
		readers = e.TotalReaderCount
	}
	if readers == 0 {
		readers = e.Readers
	}
	return ZLMStream{Schema: e.Schema, VHost: e.VHost, App: e.App, Stream: e.Stream,
		OriginType: e.OriginType, OriginTypeStr: e.OriginTypeStr, OriginURL: e.OriginURL, Readers: readers}
}

func isRTPStream(s ZLMStream) bool {
	return strings.EqualFold(s.Schema, "rtp") || s.OriginType == 3 || strings.Contains(strings.ToLower(s.OriginTypeStr), "rtp")
}

// Business streams are the named RTP application streams emitted after the
// stream-replace hook. Raw RTP listener identifiers are usually hexadecimal;
// counting every app/stream is safer than applying a brittle ID regex.
func isBusinessStream(s ZLMStream) bool {
	return strings.EqualFold(s.App, "rtp") && strings.TrimSpace(s.Stream) != ""
}

func safeHTTPError(err error) string {
	if err == nil {
		return ""
	}
	if strings.Contains(strings.ToLower(err.Error()), "context deadline exceeded") {
		return "request timeout"
	}
	return "request failed"
}
