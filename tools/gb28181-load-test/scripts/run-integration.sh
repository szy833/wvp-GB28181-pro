#!/usr/bin/env sh
set -eu

config=${1:-configs/example.yaml}
if [ "${GB28181_LOADTEST_INTEGRATION:-}" != "1" ]; then
  echo "这是受控集成测试入口。设置 GB28181_LOADTEST_INTEGRATION=1 后才会连接测试环境。" >&2
  exit 2
fi
go run ./cmd/gb28181-load-test -config "$config"
