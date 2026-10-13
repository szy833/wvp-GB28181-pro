package runner

import (
	"testing"
	"time"

	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"github.com/shizy/gb28181-load-test/internal/rtp"
)

func TestSendDiagnosticsReachCountersAndReport(t *testing.T) {
	r := metrics.NewRegistry()
	c := newResultCollector(r)
	c.OnDeviceEvent(device.DeviceEvent{Name: "media_stats", DeviceID: "d", ChannelID: "c", CallID: "call-1",
		MediaErrors: 1, LocalCancellations: 2, RemoteClosures: 1,
		Diagnostics: []rtp.SendDiagnostic{{Sequence: 1, At: time.Now(), Kind: "remote_close", Operation: "write"}}})
	d := c.DeviceResults()[0]
	if d.RTPErrors != 1 || d.RTPLocalCancellations != 2 || d.RTPRemoteClosures != 1 || len(d.RTPDiagnostics) != 1 || d.RTPDiagnostics[0].CallID != "call-1" {
		t.Fatalf("lost diagnostics: %+v", d)
	}
	values := r.Snapshot()
	if values["rtp_send_errors_total"] != 1 || values["rtp_local_cancellations_total"] != 2 || values["rtp_remote_closures_total"] != 1 {
		t.Fatal(values)
	}
	d.RTPDiagnostics[0].Kind = "changed"
	if c.DeviceResults()[0].RTPDiagnostics[0].Kind != "remote_close" {
		t.Fatal("report aliases collector")
	}
}
