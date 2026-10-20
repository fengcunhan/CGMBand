#!/bin/bash
# 编译并运行协议 / 加密 / 菜单栏文本的测试（无需 Xcode 工程）。失败时非零退出。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p .build
SOURCES=(
  src/DesktopCrypto.swift
  src/DesktopWire.swift
  src/DesktopIdentity.swift
  src/PhonePayloads.swift
  src/TrustStore.swift
  src/LineChannel.swift
  src/PhoneSession.swift
  src/PhoneLink.swift
  src/GlucoseDisplay.swift
  src/GlucoseChart.swift
  src/HistoryStore.swift
)
echo "编译测试..."
swiftc -Onone -o .build/cgm-tests "${SOURCES[@]}" tests/*.swift \
  -framework Network -framework CryptoKit \
  -target arm64-apple-macos14.0
echo "运行测试..."
.build/cgm-tests
