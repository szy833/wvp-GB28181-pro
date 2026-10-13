package runner

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

	"github.com/shizy/gb28181-load-test/internal/device"
)

type PlayController interface {
	Start(context.Context, *device.Device) error
	Stop(context.Context, *device.Device) error
}
type HTTPPlayController struct {
	Client    *http.Client
	BaseURL   string
	Token     string
	StartPath string
	StopPath  string
	Metrics   interface{ Observe(string, float64) }
	OnMedia   func(app, stream string)
	mu        sync.Mutex
	streams   map[string]string
}

func (c *HTTPPlayController) Start(ctx context.Context, d *device.Device) error {
	return c.request(ctx, c.StartPath, d, true)
}
func (c *HTTPPlayController) Stop(ctx context.Context, d *device.Device) error {
	return c.request(ctx, c.StopPath, d, false)
}
func (c *HTTPPlayController) request(ctx context.Context, pattern string, d *device.Device, start bool) error {
	started := time.Now()
	if c == nil || strings.TrimSpace(c.BaseURL) == "" {
		return fmt.Errorf("control base URL is empty")
	}
	if pattern == "" {
		if start {
			pattern = "/api/play/start/%s/%s"
		} else {
			pattern = "/api/play/stop/%s/%s"
		}
	}
	path := fmt.Sprintf(pattern, url.PathEscape(d.Identity.DeviceID), url.PathEscape(d.Identity.ChannelID))
	target, err := url.Parse(strings.TrimRight(c.BaseURL, "/") + "/" + strings.TrimLeft(path, "/"))
	if err != nil {
		return err
	}
	deviceKey := d.Identity.DeviceID + "\x00" + d.Identity.ChannelID
	if !start {
		c.mu.Lock()
		stream := c.streams[deviceKey]
		c.mu.Unlock()
		if stream != "" {
			query := target.Query()
			query.Set("stream", stream)
			target.RawQuery = query.Encode()
		}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return err
	}
	if c.Token != "" {
		req.Header.Set("access-token", c.Token)
	}
	client := c.Client
	if client == nil {
		client = &http.Client{Timeout: 90 * time.Second}
	}
	resp, err := client.Do(req)
	if c.Metrics != nil {
		c.Metrics.Observe("wvp_control_latency_seconds", time.Since(started).Seconds())
	}
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024+1))
	if err != nil || len(body) > 2*1024*1024 {
		return fmt.Errorf("invalid or oversized WVP control response")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("WVP control status=%d", resp.StatusCode)
	}
	var result struct {
		Code *int   `json:"code"`
		Msg  string `json:"msg"`
		Data struct {
			App    string `json:"app"`
			Stream string `json:"stream"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &result); err != nil || result.Code == nil {
		return fmt.Errorf("WVP control response missing valid JSON code")
	}
	if *result.Code != 0 {
		return fmt.Errorf("WVP control code=%d", *result.Code)
	}
	if start {
		generation := strings.TrimPrefix(result.Data.Stream, "gb_")
		_, generationErr := hex.DecodeString(generation)
		validGeneration := strings.HasPrefix(result.Data.Stream, "gb_") && len(generation) == 32 && generationErr == nil
		if result.Data.App != "rtp" || (!validGeneration && result.Data.Stream != d.Identity.DeviceID+"_"+d.Identity.ChannelID) {
			return fmt.Errorf("WVP play success lacks a valid RTP media identity")
		}
		if c.OnMedia != nil {
			c.OnMedia(result.Data.App, result.Data.Stream)
		}
		c.mu.Lock()
		if c.streams == nil {
			c.streams = make(map[string]string)
		}
		c.streams[deviceKey] = result.Data.Stream
		c.mu.Unlock()
		d.ConfirmMedia()
	}
	return nil
}
