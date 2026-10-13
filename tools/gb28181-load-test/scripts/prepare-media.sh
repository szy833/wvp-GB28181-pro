#!/usr/bin/env sh
set -eu

input=${1:-}
output=${2:-media/sample.ps}
if [ -z "$input" ]; then
  echo "用法: $0 input.mp4 [output.ps]" >&2
  exit 2
fi
mkdir -p "$(dirname "$output")"
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "未找到 ffmpeg，请先安装 ffmpeg" >&2
  exit 1
fi
tool=${GB28181_LOADTEST_BIN:-./bin/gb28181-load-test}
fps=${GB28181_MEDIA_FPS:-25}
if [ ! -x "$tool" ]; then
  echo "未找到工具二进制 $tool，请先构建最新版本或设置 GB28181_LOADTEST_BIN" >&2
  exit 1
fi
# Generic FFmpeg mpeg/vob muxers may aggregate frames or omit PES timestamps.
# The offline tool mode generates one timestamped access unit per PS pack.
exec "$tool" -prepare-media "$input" -media-output "$output" -media-fps "$fps"
