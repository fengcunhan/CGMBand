#!/usr/bin/env bash
# Create the signing key shared by the phone app and the band app.
#
# Xiaomi's interconnect only links a band app to a phone app with the same package name
# AND the same signing certificate, so both sides are signed from keystore/cgmband.jks:
#   keystore/cgmband.jks, keystore/keystore.properties   -> Android (Gradle)
#   band/sign/{debug,release}/{private,certificate}.pem   -> band rpk (aiot-toolkit)
# Everything written here is gitignored. Losing it means re-signing and reinstalling both apps.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYSTORE_DIR="$ROOT/keystore"
JKS="$KEYSTORE_DIR/cgmband.jks"

if [[ -e "$JKS" ]]; then
  echo "已存在 ${JKS}，为避免覆盖已安装应用的签名，脚本退出。" >&2
  exit 1
fi
for tool in keytool openssl; do
  command -v "$tool" >/dev/null || { echo "缺少 ${tool}，请先安装 JDK 17 和 OpenSSL。" >&2; exit 1; }
done

umask 077
mkdir -p "$KEYSTORE_DIR"
PASS="$(openssl rand -hex 16)"

keytool -genkeypair -keystore "$JKS" -storetype PKCS12 -alias cgmband -keyalg RSA -keysize 2048 \
  -validity 36500 -storepass "$PASS" -keypass "$PASS" -dname "CN=CGMBand" >/dev/null 2>&1

cat > "$KEYSTORE_DIR/keystore.properties" <<PROPS
storeFile=../../keystore/cgmband.jks
storePassword=$PASS
keyAlias=cgmband
keyPassword=$PASS
PROPS

PEM="$(mktemp)"
trap 'rm -f "$PEM"' EXIT
openssl pkcs12 -in "$JKS" -passin "pass:$PASS" -nodes -out "$PEM" 2>/dev/null
for variant in debug release; do
  mkdir -p "$ROOT/band/sign/$variant"
  awk '/BEGIN PRIVATE KEY/,/END PRIVATE KEY/' "$PEM" > "$ROOT/band/sign/$variant/private.pem"
  awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/' "$PEM" > "$ROOT/band/sign/$variant/certificate.pem"
done

echo "已生成签名：${JKS}"
keytool -list -v -keystore "$JKS" -storepass "$PASS" | grep -m1 'SHA256:'
