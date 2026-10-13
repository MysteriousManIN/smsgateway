#!/usr/bin/env bash
# Fix "Activity class {com.smsgateway/com.smsgateway.MainActivity} does not exist".
# Cause: app was renamed — namespace (classes) is com.smsgateway but the
# installed package (applicationId) is now com.sandesh.app. The emulator still
# has the stale com.smsgateway build, and Studio tries to launch it.
# This script removes BOTH packages, rebuilds, reinstalls, and launches the
# correct component: com.sandesh.app/com.smsgateway.MainActivity
set -euo pipefail
cd "$(dirname "$0")"

PKG_NEW="com.sandesh.app"
PKG_OLD="com.smsgateway"
ACTIVITY="$PKG_NEW/com.smsgateway.MainActivity"

command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found in PATH. Open Android Studio > SDK Manager > install Platform-Tools."; exit 1; }
adb start-server >/dev/null 2>&1 || true

echo "--- 1/4: uninstall stale packages (failures are OK) ---"
adb uninstall "$PKG_OLD" || echo "(old $PKG_OLD not installed — OK)"
adb uninstall "$PKG_NEW" || echo "(new $PKG_NEW not installed — OK)"

echo "--- 2/4: clean + rebuild debug APK ---"
./gradlew clean :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "ERROR: $APK not found after build."; exit 1; }

echo "--- 3/4: install $PKG_NEW ---"
adb install -r "$APK"

echo "--- 4/4: launch $ACTIVITY ---"
adb shell am start -n "$ACTIVITY" \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER

echo
echo "OK: installed and launched $ACTIVITY"
echo "If Studio still shows the error: File > Sync Project with Gradle Files,"
echo "then set Run > Edit Configurations > app > Launch Options = Default Activity."
