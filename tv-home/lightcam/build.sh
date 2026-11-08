#!/usr/bin/env bash
# Builds lightcam/build/lightcam.apk (the phone that watches the TV) from the bare SDK tools, the
# same way launcher/build.sh does: no Gradle, no libraries.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
JAR=$SDK/platforms/android-25/android.jar
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH=$JAVA_HOME/bin:$PATH  # d8 and apksigner run java from PATH
[[ -f $JAR ]] || { echo "error: $JAR missing — sdkmanager 'platforms;android-25'" >&2; exit 1; }

KEYSTORE=$PWD/release.keystore
KEYPASS=${LIGHTCAM_KEYPASS:-lightcam}
if [[ ! -f $KEYSTORE ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias lightcam -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=lightcam" >/dev/null
  echo "created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/lightcam.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build
"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" \
  --min-sdk-version 25 --target-sdk-version 25 --version-code 1 --version-name 1.0 \
  --java "$WORK/gen" -o "$WORK/app.apk"
find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api 25 --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)
"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/lightcam.apk "$WORK/aligned.apk"
echo "built build/lightcam.apk  $(wc -c < build/lightcam.apk | tr -d ' ') bytes"
