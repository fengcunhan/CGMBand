#!/usr/bin/env bash
# Build the phone APK and the band rpk from the same signing key and collect them in out/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/out"

if [[ ! -f "$ROOT/keystore/keystore.properties" ]]; then
  echo "缺少签名，请先运行 tools/create_keystore.sh" >&2
  exit 1
fi

"$ROOT/tools/fetch_xms_sdk.sh"

echo "==> 手机端：单元测试 + release APK"
(cd "$ROOT/android" && ./gradlew --quiet :app:testDebugUnitTest :app:assembleRelease)

echo "==> 手环端：单元测试 + release rpk"
(cd "$ROOT/band" && npm install --silent && npm test --silent && npx aiot release)

mkdir -p "$OUT"
cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$OUT/CGMBand-phone.apk"
cp "$ROOT"/band/dist/*.release.*.rpk "$OUT/"

echo "==> 产物（两者必须一起安装，签名一致才能通信）："
ls -la "$OUT"
