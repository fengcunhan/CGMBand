#!/bin/bash
set -e
cd "$(dirname "$0")"
APP="血糖监测.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp Info.plist "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
echo "编译中..."
swiftc -O -o "$APP/Contents/MacOS/CGMMonitor" \
  src/*.swift \
  -framework SwiftUI -framework Charts -framework Combine -framework AppKit \
  -framework Network -framework CryptoKit \
  -target arm64-apple-macos14.0
# 本地 ad-hoc 签名
codesign --force --deep --sign - "$APP"
echo "完成: $APP"
