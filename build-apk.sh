#!/usr/bin/env bash
# Generate non-debug (release) APK for Sandesh.
# Release is signed with the debug keystore (see app/build.gradle.kts) —
# fine for sideload/testing, NOT for Play Store.
set -euo pipefail
cd "$(dirname "$0")"

./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
OUT="sandesh-release.apk"
cp -f "$APK" "$OUT"

echo "APK ready: $(pwd)/$OUT"
ls -la "$OUT"
