package scenario

import "testing"

func TestBuiltinScenarios(t *testing.T) { for _, s := range []Scenario{SteadyPlay(0), NoMediaTimeout(0), DeviceBye(0), ChurnPlay(0)} { if s.Name() == "" || s.PlayPolicy() == "" { t.Fatal("invalid scenario") } } }
