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
ffmpeg -hide_banner -loglevel error -y -i "$input" -an -c:v copy -f mpegps "$output"
echo "已生成 $output"
