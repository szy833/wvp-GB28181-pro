package metrics

import (
	"bytes"
	"encoding/csv"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/shizy/gb28181-load-test/internal/rtp"
)

func TestSendDiagnosticsVisibleInAllReports(t *testing.T) {
	d := DeviceResult{DeviceID: "d", RTPLocalCancellations: 1, RTPRemoteClosures: 2,
		RTPDiagnostics: []SendDiagnostic{{SendDiagnostic: rtp.SendDiagnostic{At: time.Now(), Kind: "local_cancel", Operation: "write"}, CallID: "call-1"}}}
	r := Report{Devices: []DeviceResult{d}, Plays: []PlayResult{{ID: "p"}}}
	dir := t.TempDir()
	for _, tc := range []struct {
		name  string
		write func(string, Report) error
	}{
		{"report.json", WriteJSON}, {"report.md", WriteMarkdown}, {"report.csv", WriteDetailCSV},
	} {
		p := filepath.Join(dir, tc.name)
		if err := tc.write(p, r); err != nil {
			t.Fatal(err)
		}
		data, _ := os.ReadFile(p)
		if !strings.Contains(string(data), "local_cancel") || !strings.Contains(string(data), "call-1") {
			t.Fatalf("%s lacks diagnostics: %s", tc.name, data)
		}
		if strings.HasSuffix(tc.name, "csv") {
			if _, err := csv.NewReader(bytes.NewReader(data)).ReadAll(); err != nil {
				t.Fatal("CSV column mismatch", err)
			}
		}
	}
	var out bytes.Buffer
	PrintSummary(&out, r, "")
	if !strings.Contains(out.String(), "localCancellations=1 remoteClosures=2") {
		t.Fatal(out.String())
	}
}
