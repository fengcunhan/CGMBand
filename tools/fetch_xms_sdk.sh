#!/usr/bin/env bash
# Download Xiaomi's wearable SDK (xms-wearable-lib 1.4) from Xiaomi's official Vela demo
# package instead of redistributing the binary in this repository.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="$ROOT/android/app/libs/xms-wearable-lib_1.4_release.aar"
URL="https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip"
ENTRY="interconnect_dev_test_demo/libs/xms-wearable-lib_1.4_release.aar"
SHA256="9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195"

sha256_of() { shasum -a 256 "$1" 2>/dev/null | awk '{print $1}' || sha256sum "$1" | awk '{print $1}'; }

if [[ -f "$TARGET" && "$(sha256_of "$TARGET")" == "$SHA256" ]]; then
  echo "小米穿戴 SDK 已存在：${TARGET}"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "从小米官方下载 interconnect 示例包（约 7 MB）…"
curl -fsSL --retry 3 -o "$TMP/demo.zip" "$URL"
unzip -q -o "$TMP/demo.zip" "$ENTRY" -d "$TMP"

ACTUAL="$(sha256_of "$TMP/$ENTRY")"
if [[ "$ACTUAL" != "$SHA256" ]]; then
  echo "SDK 校验失败：期望 ${SHA256}，实际 ${ACTUAL}。官方包可能已更新，请人工确认。" >&2
  exit 1
fi

mkdir -p "$(dirname "$TARGET")"
cp "$TMP/$ENTRY" "$TARGET"
echo "已下载并校验小米穿戴 SDK：${TARGET}"
