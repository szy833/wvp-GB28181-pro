//go:build integration

package integration

import "testing"

func TestEnvironmentIsExplicitlyEnabled(t *testing.T) {
	t.Skip("真实 WVP/ZLM 集成测试需要测试网络、账号和媒体节点；通过 scripts/run-integration.sh 执行")
}
