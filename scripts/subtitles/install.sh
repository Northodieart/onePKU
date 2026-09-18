#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$SCRIPT_DIR/install.py" "$@"
elif command -v uv >/dev/null 2>&1; then
  exec uv run --no-project --no-config --python 3.12 "$SCRIPT_DIR/install.py" "$@"
else
  echo '请先安装 uv 与 ffmpeg：brew install uv ffmpeg' >&2
  exit 1
fi
