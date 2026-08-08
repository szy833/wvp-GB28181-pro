#!/usr/bin/env sh
set -eu

config=${1:-configs/example.yaml}
if [ "${GB28181_LOADTEST_INTEGRATION:-}" != "1" ]; then
  echo "这是受控集成测试入口。设置 GB28181_LOADTEST_INTEGRATION=1 后才会连接测试环境。" >&2
  exit 2
fi

binary=${GB28181_LOADTEST_BIN:-./bin/gb28181-load-test}
if [ -x "$binary" ]; then
  exec "$binary" -config "$config"
fi

if command -v go >/dev/null 2>&1; then
  exec go run ./cmd/gb28181-load-test -config "$config"
fi

echo "未找到可执行文件 $binary，且当前环境没有 Go。请先准备编译好的压测二进制，或设置 GB28181_LOADTEST_BIN 指向它。" >&2
exit 1
