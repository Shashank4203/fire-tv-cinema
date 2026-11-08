#!/usr/bin/env bash
# Builds the TV light camera, installs it on the phone, points it at the TV and starts it.
#   ./lightcam/deploy.sh          build + install + start
#   ./lightcam/deploy.sh frame    only fetch the camera's latest frame to lightcam/build/frame.jpg
# Addresses come from deploy.conf (git-ignored): ANDROID_SERIAL=<tv-ip>:5555 and
# PHONE_SERIAL=<phone-ip>:<port from the phone's Wireless debugging screen>.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
[[ -f ../deploy.conf ]] && source ../deploy.conf
: "${PHONE_SERIAL:?set PHONE_SERIAL=<phone-ip>:<port> in deploy.conf}"
: "${ANDROID_SERIAL:?set ANDROID_SERIAL=<tv-ip>:5555 in deploy.conf}"
PKG=tv.dumbtv.lightcam
DIR=/sdcard/Android/data/$PKG/files
phone() { adb -s "$PHONE_SERIAL" "$@"; }

adb connect "$PHONE_SERIAL" >/dev/null
mkdir -p build
if [[ ${1:-} == frame ]]; then
  phone pull "$DIR/frame.jpg" build/frame.jpg | tail -1
  exit
fi

./build.sh | tail -1
phone install -r build/lightcam.apk | tail -1
phone shell pm grant $PKG android.permission.CAMERA
phone shell pm grant $PKG android.permission.RECORD_AUDIO
# "Display over other apps": lets Power.java bring the camera back when the TV's USB power returns.
phone shell appops set $PKG SYSTEM_ALERT_WINDOW allow
phone shell mkdir -p "$DIR"
# Keep crop and exposure settings already on the phone; only the TV's address is ours to set.
conf=$(phone shell cat "$DIR/lightcam.conf" 2>/dev/null | grep -v '^tv=' || true)
printf 'tv=%s\n%s\n' "${ANDROID_SERIAL%%:*}" "$conf" | grep -v '^$' > build/lightcam.conf
phone push build/lightcam.conf "$DIR/lightcam.conf" | tail -1
# Reading titles off the screen for Continue watching asks Gemini with this key (kept on the Mac,
# outside git).
[[ -f ../../tv-assets/gemini.key ]] && phone push ../../tv-assets/gemini.key "$DIR/gemini.key" | tail -1
phone shell am start -n $PKG/.Main >/dev/null
echo "lightcam started"
