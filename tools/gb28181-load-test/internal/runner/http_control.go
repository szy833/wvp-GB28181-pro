package runner

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/shizy/gb28181-load-test/internal/device"
)

type PlayController interface { Start(context.Context, *device.Device) error; Stop(context.Context, *device.Device) error }
type HTTPPlayController struct { Client *http.Client; BaseURL string; Token string; StartPath string; StopPath string; Metrics interface{ Observe(string, float64) } }

func (c *HTTPPlayController) Start(ctx context.Context, d *device.Device) error { return c.request(ctx, c.StartPath, d) }
func (c *HTTPPlayController) Stop(ctx context.Context, d *device.Device) error { return c.request(ctx, c.StopPath, d) }
func (c *HTTPPlayController) request(ctx context.Context, pattern string, d *device.Device) error { started := time.Now(); if c == nil || strings.TrimSpace(c.BaseURL) == "" { return fmt.Errorf("control base URL is empty") }; if pattern == "" { pattern = "/api/play/start/%s/%s" }; path := fmt.Sprintf(pattern, url.PathEscape(d.Identity.DeviceID), url.PathEscape(d.Identity.ChannelID)); target, err := url.Parse(strings.TrimRight(c.BaseURL, "/") + "/" + strings.TrimLeft(path, "/")); if err != nil { return err }; req, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil); if err != nil { return err }; if c.Token != "" { req.Header.Set("Authorization", "Bearer "+c.Token) }; client := c.Client; if client == nil { client = &http.Client{Timeout: 90 * time.Second} }; resp, err := client.Do(req); if c.Metrics != nil { c.Metrics.Observe("wvp_control_latency_seconds", time.Since(started).Seconds()) }; if err != nil { return err }; defer resp.Body.Close(); body, _ := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024)); if resp.StatusCode < 200 || resp.StatusCode >= 300 { return fmt.Errorf("WVP control status=%d body=%s", resp.StatusCode, strings.TrimSpace(string(body))) }; var result struct { Code int `json:"code"`; Msg string `json:"msg"` }; if json.Unmarshal(body, &result) == nil && result.Code != 0 { return fmt.Errorf("WVP control code=%d msg=%s", result.Code, result.Msg) }; return nil }
