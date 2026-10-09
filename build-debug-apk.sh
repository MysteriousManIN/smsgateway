#!/usr/bin/env bash
# Generate debug APK for Sandesh (fast, no minify, debuggable).
set -euo pipefail
cd "$(dirname "$0")"

./gradlew :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
OUT="sandesh-debug.apk"
cp -f "$APK" "$OUT"

echo "APK ready: $(pwd)/$OUT"
ls -la "$OUT"
