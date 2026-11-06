#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ANDROID_API="${ANDROID_API:-35}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"
D8="$ANDROID_HOME/build-tools/$BUILD_TOOLS/d8"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$ANDROID_API/android.jar"
[[ -f "$D8" ]] || { echo "Missing $D8" >&2; exit 1; }
[[ -f "$ANDROID_JAR" ]] || { echo "Missing $ANDROID_JAR" >&2; exit 1; }
GRADLE_BIN="${GRADLE_BIN:-gradle}"
cd "$ROOT"
"$GRADLE_BIN" --no-daemon -PandroidApi="$ANDROID_API" :plugin:jar
mkdir -p dist/dex
rm -f dist/dex/classes*.dex
"$D8" --min-api 26 --lib "$ANDROID_JAR" --output dist/dex plugin/build/libs/youtube-music-plugin.jar
cp plugin.json dist/dex/plugin.json
(cd dist/dex && zip -q -j ../youtube-music.amaliaplug plugin.json classes*.dex)
unzip -t dist/youtube-music.amaliaplug >/dev/null
sha256sum dist/youtube-music.amaliaplug
