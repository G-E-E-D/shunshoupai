#!/usr/bin/env bash
set -euo pipefail
# Runs only on the disposable emulator, with the debug-only synthetic Activity.
report_dir="app/build/reports/background-smoke"
mkdir -p "$report_dir"
restore() {
  adb shell dumpsys deviceidle unforce >/dev/null || true
  adb shell dumpsys battery reset >/dev/null || true
  adb shell input keyevent KEYCODE_WAKEUP || true
  adb shell wm dismiss-keyguard || true
}
trap restore EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.shunshoupai.app android.permission.POST_NOTIFICATIONS
adb shell am start -W -n com.shunshoupai.app/.BackgroundFixtureActivity --ei delay_seconds 30 > "$report_dir/prepare.txt"
adb shell run-as com.shunshoupai.app cat files/background-fixture.json > "$report_dir/fixture.json"
sleep 2
old_pid="$(adb shell pidof com.shunshoupai.app | tr -d '\r')"
test -n "$old_pid"
adb shell input keyevent KEYCODE_SLEEP
adb shell am kill com.shunshoupai.app
sleep 2
if adb shell pidof com.shunshoupai.app; then
  echo 'The reminder process was not killed; this cannot prove process-death delivery.' >&2
  exit 1
fi
adb shell dumpsys battery unplug
adb shell dumpsys deviceidle force-idle | tee "$report_dir/idle-request.txt"
adb shell dumpsys deviceidle get deep | tr -d '\r' > "$report_dir/idle.txt"
grep -Fxq 'IDLE' "$report_dir/idle.txt"
event_id="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$report_dir/fixture.json")"
for attempt in $(seq 1 50); do
  adb shell dumpsys notification --noredact > "$report_dir/notifications.txt"
  if grep -E "NotificationRecord.*pkg=com.shunshoupai.app.*tag=schedule-reminder:$event_id" "$report_dir/notifications.txt" > "$report_dir/delivered.txt"; then break; fi
  sleep 1
done
test -s "$report_dir/delivered.txt"
new_pid="$(adb shell pidof com.shunshoupai.app | tr -d '\r')"
test -n "$new_pid" && test "$new_pid" != "$old_pid"
printf 'PASS: real AlarmManager notification after process death while screen off in forced Doze; old PID %s, new PID %s\n' "$old_pid" "$new_pid" | tee "$report_dir/result.txt"
restore

# Preserve a future reminder and a synthetic, unreachable paired device. Reboot
# must restore the calendar alarm and the peer service without launching the UI.
adb shell am start -W -n com.shunshoupai.app/.BackgroundFixtureActivity --ei delay_seconds 110 --ez with_peer true > "$report_dir/reboot-prepare.txt"
adb shell run-as com.shunshoupai.app cat files/background-fixture.json > "$report_dir/reboot-fixture.json"
event_id="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$report_dir/reboot-fixture.json")"
adb reboot
sleep 5
adb wait-for-device
for attempt in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = '1' ]; then break; fi
  sleep 2
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = '1'
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
for attempt in $(seq 1 30); do
  adb shell dumpsys activity services com.shunshoupai.app/.BackgroundSyncService > "$report_dir/reboot-service.txt"
  if grep -q 'isForeground=true' "$report_dir/reboot-service.txt"; then break; fi
  sleep 1
done
grep -q 'isForeground=true' "$report_dir/reboot-service.txt"
grep -q 'BOOT_COMPLETED' "$report_dir/reboot-service.txt"
for attempt in $(seq 1 130); do
  adb shell dumpsys notification --noredact > "$report_dir/reboot-notifications.txt"
  if grep -E "NotificationRecord.*pkg=com.shunshoupai.app.*tag=schedule-reminder:$event_id" "$report_dir/reboot-notifications.txt" > "$report_dir/reboot-delivered.txt"; then break; fi
  sleep 1
done
test -s "$report_dir/reboot-delivered.txt"
echo 'PASS: reboot restores the peer foreground service and a real calendar notification without opening the application.' | tee -a "$report_dir/result.txt"
trap - EXIT
