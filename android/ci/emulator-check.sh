#!/usr/bin/env bash
set -euo pipefail

report_dir="app/build/reports/release-smoke"
mkdir -p "$report_dir"
trap 'adb logcat -d -b crash > "$report_dir/crashes.txt" 2>/dev/null || true' EXIT

if [ "${HAS_SIGNED_RELEASE:-false}" = 'true' ]; then
  adb logcat -c
  adb install -r release/shunshoupai-android-v1.2.0.apk
  adb shell am start -W -n com.shunshoupai.app/.MainActivity | tee "$report_dir/launch.txt"
  grep -q 'Status: ok' "$report_dir/launch.txt"
  sleep 3
  adb shell pidof com.shunshoupai.app
  adb shell uiautomator dump /sdcard/release-window.xml
  adb pull /sdcard/release-window.xml "$report_dir/window.xml"
  adb exec-out screencap -p > "$report_dir/screenshot.png"
  grep -q '给日程连个家' "$report_dir/window.xml"
  grep -q 'com.shunshoupai.app:id/connect_button' "$report_dir/window.xml"
  adb logcat -d -b crash > "$report_dir/crashes.txt"
  if grep -q 'com.shunshoupai.app' "$report_dir/crashes.txt"; then
    echo 'The signed release app crashed during launch.' >&2
    exit 1
  fi
  # The instrumentation build uses the Android development certificate, so the
  # signed release must be uninstalled before Gradle installs the debug APK.
  adb uninstall com.shunshoupai.app
fi

./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.backend_url=http://10.0.2.2:3000

# The UI test captures the real connected workspace. Some device/Gradle
# configurations remove app data after instrumentation, so this extra image
# is best-effort and cannot turn failed business assertions into a success.
adb pull /sdcard/Android/data/com.shunshoupai.app/files/android-workspace.png \
  "$report_dir/workspace.png" || true
