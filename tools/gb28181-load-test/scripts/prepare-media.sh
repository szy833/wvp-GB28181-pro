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
# FFmpeg names the MPEG program-stream muxer "mpeg"; "mpegps" is not a
# portable muxer name and is rejected by common FFmpeg builds.
if ! ffmpeg -hide_banner -loglevel error -y -i "$input" -map 0:v:0 -an -c:v copy -f mpeg "$output"; then
  echo "视频流无法直接封装为 MPEG-PS，尝试转码为 MPEG-2 视频" >&2
  ffmpeg -hide_banner -loglevel error -y -i "$input" -map 0:v:0 -an -c:v mpeg2video -b:v 4M -f mpeg "$output"
fi
echo "已生成 $output"
