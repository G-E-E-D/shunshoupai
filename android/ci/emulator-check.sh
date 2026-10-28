#!/usr/bin/env bash
set -euo pipefail

report_dir="app/build/reports/release-smoke"
mkdir -p "$report_dir"
launcher_package="com.google.android.apps.nexuslauncher"
restore_launcher=false

finish_checks() {
  result=$?
  trap - EXIT
  adb logcat -d -b crash > "$report_dir/crashes.txt" 2>/dev/null || true
  adb logcat -d -b events -v brief > "$report_dir/events.txt" 2>/dev/null || true
  adb shell dumpsys activity lastanr > "$report_dir/last-anr.txt" 2>/dev/null || true
  # A launcher workaround must never turn an ANR/crash in the application under
  # test into a pass. ANRs are in the events buffer, not necessarily crash logs.
  if grep -q 'com.shunshoupai.app' "$report_dir/crashes.txt" \
      || grep -Eq 'am_anr.*com[.]shunshoupai[.]app([,:[:space:]]|$)' "$report_dir/events.txt"; then
    echo 'The application under test crashed or became unresponsive.' >&2
    result=1
  fi
  if [ "$restore_launcher" = true ]; then
    adb shell pm enable --user 0 "$launcher_package" >> "$report_dir/launcher-isolation.txt" 2>&1 || true
  fi
  exit "$result"
}
trap finish_checks EXIT

# The API 35 Google image can show an unrelated Pixel Launcher ANR during cold
# boot. No test uses the home launcher: ActivityScenario and the release smoke
# both launch explicit application components. Isolate only this system package,
# preserving all global ANR settings and every failure from our own application.
adb shell dumpsys activity lastanr > "$report_dir/pre-test-last-anr.txt" 2>/dev/null || true
if adb shell pm list packages --user 0 "$launcher_package" | tr -d '\r' | grep -Fxq "package:$launcher_package"; then
  if ! adb shell pm list packages -d --user 0 "$launcher_package" | tr -d '\r' | grep -Fxq "package:$launcher_package"; then
    adb shell pm disable-user --user 0 "$launcher_package" | tee "$report_dir/launcher-isolation.txt"
    restore_launcher=true
    adb shell pm list packages -d --user 0 "$launcher_package" | tr -d '\r' | grep -Fxq "package:$launcher_package"
  fi
  adb shell am force-stop "$launcher_package"
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb logcat -c

if [ "${HAS_SIGNED_RELEASE:-false}" = 'true' ]; then
  : "${RELEASE_APK_NAME:?CI must provide the APK name exported by Gradle}"
  : "${EXPECTED_VERSION_NAME:?CI must provide the Gradle versionName}"
  : "${EXPECTED_VERSION_CODE:?CI must provide the Gradle versionCode}"
  test -f "release/$RELEASE_APK_NAME"
  (cd release && sha256sum --check SHA256SUMS.txt)
  adb logcat -c
  adb install -r "release/$RELEASE_APK_NAME"
  adb shell dumpsys package com.shunshoupai.app > "$report_dir/installed-package.txt"
  sed 's/^[[:space:]]*//' "$report_dir/installed-package.txt" | tr -d '\r' | grep -Fx "versionName=$EXPECTED_VERSION_NAME"
  sed 's/^[[:space:]]*//' "$report_dir/installed-package.txt" | tr -d '\r' | grep -E "^versionCode=$EXPECTED_VERSION_CODE([[:space:]]|$)"
  adb shell am start -W -n com.shunshoupai.app/.MainActivity | tee "$report_dir/launch.txt"
  grep -q 'Status: ok' "$report_dir/launch.txt"
  # Wait for the actual UI, rather than assuming every cold emulator has drawn
  # the window in three seconds. The same title and control assertions still
  # have to pass, and no unknown or application-owned error dialog is dismissed.
  for attempt in $(seq 1 10); do
    adb shell pidof com.shunshoupai.app
    adb shell uiautomator dump /sdcard/release-window.xml
    adb pull /sdcard/release-window.xml "$report_dir/window.xml"
    if grep -q 'com.shunshoupai.app:id/offline_add' "$report_dir/window.xml" \
        && grep -q 'com.shunshoupai.app:id/offline_connect' "$report_dir/window.xml"; then
      break
    fi
    if grep -Fq "Pixel Launcher isn't responding" "$report_dir/window.xml"; then
      cp "$report_dir/window.xml" "$report_dir/launcher-anr-window.xml"
      adb shell am force-stop "$launcher_package"
      adb shell am start -W -n com.shunshoupai.app/.MainActivity >> "$report_dir/launch.txt"
    fi
    sleep 1
  done
  adb exec-out screencap -p > "$report_dir/screenshot.png"
  grep -q 'com.shunshoupai.app:id/offline_add' "$report_dir/window.xml"
  grep -q 'com.shunshoupai.app:id/offline_connect' "$report_dir/window.xml"
  adb logcat -d -b crash > "$report_dir/crashes.txt"
  adb logcat -d -b events -v brief > "$report_dir/events.txt"
  if grep -q 'com.shunshoupai.app' "$report_dir/crashes.txt" \
      || grep -Eq 'am_anr.*com[.]shunshoupai[.]app([,:[:space:]]|$)' "$report_dir/events.txt"; then
    echo 'The signed release app crashed or became unresponsive during launch.' >&2
    exit 1
  fi
  # The instrumentation build uses the Android development certificate, so the
  # signed release must be uninstalled before Gradle installs the debug APK.
  adb uninstall com.shunshoupai.app
fi

adb forward tcp:43128 tcp:43127
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.backend_url=http://10.0.2.2:3000 \
  -Pandroid.testInstrumentationRunnerArguments.callback_origin=http://127.0.0.1:43128 \
  "-Pandroid.testInstrumentationRunnerArguments.bootstrap_key=${TEST_DEVICE_BOOTSTRAP_KEY:?Provide the isolated test backend bootstrap key}"

# The UI test captures the real connected workspace. Some device/Gradle
# configurations remove app data after instrumentation, so this extra image
# is best-effort and cannot turn failed business assertions into a success.
adb pull /sdcard/Android/data/com.shunshoupai.app/files/android-workspace.png \
  "$report_dir/workspace.png" || true
for capture in android-offline-home android-offline-editor android-offline-conflict android-devices android-peer-home android-peer-conflict; do
  adb pull "/sdcard/Android/data/com.shunshoupai.app/files/$capture.png" \
    "$report_dir/$capture.png" || true
done
