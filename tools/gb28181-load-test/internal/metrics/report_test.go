package metrics

import (
	"path/filepath"
	"testing"
)

func TestWriteReports(t *testing.T) { p := filepath.Join(t.TempDir(), "r.json"); r := Report{RunID: "x"}; if err := WriteJSON(p, r); err != nil { t.Fatal(err) }; if err := WriteCSV(filepath.Join(t.TempDir(), "r.csv"), r); err != nil { t.Fatal(err) } }
