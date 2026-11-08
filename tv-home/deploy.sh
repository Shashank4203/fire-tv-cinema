#!/usr/bin/env bash
# Rebuilds the custom home screen, updates it on the TV, pushes the theme files and re-arms the
# HOME guard (an app update unbinds its accessibility service on Fire OS).
#   ./deploy.sh            build + install + push assets
#   ./deploy.sh assets     push assets only (dock.txt, wallpapers, logos, ...), no rebuild
#   ./deploy.sh quiet      build + install + push, but leave what's on screen alone (no wake-up,
#                          no home screen): for updating while something is being watched
# The TV's address comes from ANDROID_SERIAL, or from deploy.conf (git-ignored), e.g.
#   ANDROID_SERIAL=192.168.1.20:5555
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

[[ -f deploy.conf ]] && source deploy.conf
: "${ANDROID_SERIAL:?set ANDROID_SERIAL=<tv-ip>:5555 in deploy.conf or the environment}"
export ANDROID_SERIAL
# The TV's address changes now and then: if it doesn't answer, find it by its serial number
# (tv-assets/tools/find_tv.py) and correct deploy.conf.
adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
if ! adb -s "$ANDROID_SERIAL" shell echo ok 2>/dev/null | grep -q ok && [[ -f ../tv-assets/tools/find_tv.py ]]; then
  if found=$(python3 -I ../tv-assets/tools/find_tv.py); then
    sed -i '' "s/^ANDROID_SERIAL=.*/ANDROID_SERIAL=$found/" deploy.conf
    ANDROID_SERIAL=$found
    echo "the TV moved: now at $found (deploy.conf updated)"
  fi
fi
# The daily refresh (tv-assets/tools/refresh_pool.py) pushes to the TV too: it reads the address here.
[[ -d ../tv-assets ]] && echo "$ANDROID_SERIAL" > ../tv-assets/tv-serial.txt
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
ASSETS=../tv-assets/tv
DEST=/sdcard/Android/data/tv.dumbtv.home/files/cinema
GUARD=tv.dumbtv.home/tv.dumbtv.home.Guard
LIGHTS=tv.dumbtv.home/tv.dumbtv.home.Lights

adb connect "$ANDROID_SERIAL" >/dev/null

if [[ ${1:-} != assets ]]; then
  launcher/build.sh | tail -1
  adb install -r launcher/build/dumbtv-home.apk | tail -1
fi

# Only files that changed are copied again (adb push --sync).
adb shell mkdir -p "$DEST"
for d in wallpapers logos icons fonts characters scenes; do
  [[ -d $ASSETS/$d ]] && adb push --sync "$ASSETS/$d/." "$DEST/$d/" | tail -1
done
if [[ -d $ASSETS/pool ]]; then
  adb shell mkdir -p "$DEST/pool/cutouts" "$DEST/pool/scenes"
  adb push --sync "$ASSETS/pool/." "$DEST/pool/" | tail -1
fi
for f in dock.txt favourites.txt weather.txt; do
  [[ -f $ASSETS/$f ]] && adb push "$ASSETS/$f" "$DEST/" | tail -1
done
# The light strip, Alexa results and Continue watching look titles up on TMDB; Continue watching
# finds YouTube videos' IDs and thumbnails with the YouTube Data API.
for f in tmdb.key youtube.key; do
  [[ -f $ASSETS/../$f ]] && adb push "$ASSETS/../$f" "$DEST/" | tail -1
done

# The watchdog switches the HOME guard back on if Fire OS drops it; that needs this grant.
adb shell pm grant tv.dumbtv.home android.permission.WRITE_SECURE_SETTINGS

# Restart first: a force-stop unbinds the guard, so it must come before re-arming.
adb shell am force-stop tv.dumbtv.home

# Re-arm the guard: clearing first makes Android rebind it. It counts as running only once it logs
# "connected": Fire OS has listed it as bound in dumpsys accessibility while nothing of it ran
# (HOME then stayed on Amazon's home), so a few more tries before giving up.
bound=0
for _ in 1 2 3; do
  since=$(adb shell date "'+%m-%d %H:%M:%S.000'" | tr -d '\r')
  adb shell settings put secure enabled_accessibility_services '""' >/dev/null
  sleep 1
  adb shell settings put secure enabled_accessibility_services "$GUARD"
  adb shell settings put secure accessibility_enabled 1
  for _ in $(seq 1 15); do
    adb logcat -d -T "$since" -s guard:I 2>/dev/null | grep -q " connected" && { echo "guard: running"; bound=1; break; }
    sleep 1
  done
  [[ $bound == 1 ]] && break
done
if [[ $bound == 0 ]]; then
  # Seen once: Android's accessibility manager got stuck mid-start after an update and ignored
  # every setting change. A restart makes Fire OS start enabled services afresh.
  echo "WARNING: the HOME guard did not start; HOME will show Amazon's home. Fix: adb reboot"
fi

if [[ ${1:-} != quiet ]]; then
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell am start -n tv.dumbtv.home/.Home >/dev/null
  echo "home screen restarted"
fi

# The light strip sees what's playing through a notification listener (it reads no
# notifications). The force-stop above unbound it; withdrawing and granting again rebinds it.
adb shell cmd notification disallow_listener "$LIGHTS" >/dev/null 2>&1 || true
adb shell cmd notification allow_listener "$LIGHTS"
for _ in $(seq 1 10); do
  adb shell dumpsys notification | grep -q "$LIGHTS" && { echo "light strip: listening"; break; }
  sleep 1
done
