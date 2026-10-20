#!/bin/bash
# Render the README screenshots of the Mac app (synthetic demo data, no real readings)
# into ../docs/images. Compiles the app's sources without its @main entry point.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build
SOURCES=()
for f in src/*.swift; do
  [[ "$f" == "src/App.swift" ]] || SOURCES+=("$f")
done
swiftc -O -o .build/render-screenshots "${SOURCES[@]}" tools/screenshots/main.swift \
  -framework SwiftUI -framework Charts -framework Combine -framework AppKit \
  -framework Network -framework CryptoKit \
  -target arm64-apple-macos14.0
.build/render-screenshots ../docs/images
