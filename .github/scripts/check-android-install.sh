#!/usr/bin/env bash
set -euo pipefail

apk="$1"
package=com.alemnt.midipro.bridge
component="$package/com.alemnt.midipro.MainActivity"

test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 33
adb shell input keyevent 82
adb logcat -c
trap 'if (( $? != 0 )); then adb logcat -d -t 250; fi' EXIT

adb install --no-streaming "$apk" | grep -Fx 'Success'
installed="$(adb shell dumpsys package "$package")"
grep -F 'versionName=1.1.0' <<< "$installed"
launch="$(adb shell am start -W -n "$component")"
printf '%s\n' "$launch"
grep -F 'Status: ok' <<< "$launch"
sleep 3
adb shell pidof "$package"
activities="$(adb shell dumpsys activity activities)"
windows="$(adb shell dumpsys window windows)"
printf '%s\n' "$activities" "$windows" | grep -E 'topResumedActivity|mResumedActivity|mCurrentFocus' | grep -F "$component"
crashes="$(adb logcat -d -s AndroidRuntime:E)"
if grep -Fq "Process: $package," <<< "$crashes"; then
    printf '%s\n' "$crashes"
    exit 1
fi
echo "PASS Android 13: release APK installed and its activity resumed."
