#!/usr/bin/env bash
# Build only a JVM plugin JAR, dex it, and package with the SDK's common packer.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="$(cd "$ROOT/../.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ANDROID_API="${ANDROID_API:-35}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"
D8="$ANDROID_HOME/build-tools/$BUILD_TOOLS/d8"
ANDROID_JAR="$ANDROID_HOME/platforms/android-$ANDROID_API/android.jar"
OUT="$ROOT/dist"
export ANDROID_HOME
for tool in "$D8" "$ANDROID_JAR"; do
    if [[ ! -f "$tool" ]]; then
        echo "Missing $tool; install platforms;android-$ANDROID_API and build-tools;$BUILD_TOOLS" >&2
        exit 1
    fi
done
if [[ -x "$ROOT/gradlew" ]]; then
    GRADLE=("$ROOT/gradlew")
elif [[ -n "${GRADLE_BIN:-}" ]]; then
    GRADLE=("$GRADLE_BIN")
elif command -v gradle >/dev/null 2>&1; then
    GRADLE=(gradle)
else
    echo "Gradle 8.10.2 is required: install gradle or set GRADLE_BIN." >&2
    exit 1
fi
if ! "${GRADLE[@]}" --version | grep -q '^Gradle 8\.10\.2$'; then
    echo "Use Gradle 8.10.2 (set GRADLE_BIN to its executable)." >&2
    exit 1
fi
cd "$ROOT"
"${GRADLE[@]}" --no-daemon -PandroidApi="$ANDROID_API" :plugin:dex
JAR="$ROOT/plugin/build/libs/plugin.jar"
[[ -f "$JAR" ]] || { echo "Missing $JAR" >&2; exit 1; }
# A fresh staging directory prevents obsolete dex shards/assets from entering a new release.
mkdir -p "$OUT"
STAGE="$(mktemp -d "$OUT/stage.XXXXXX")"
trap 'rm -r "$STAGE"' EXIT
cp "$ROOT"/dex/classes*.dex "$STAGE/"
for dex in "$STAGE"/classes*.dex; do
    python3 "$SDK_ROOT/tools/check-dex.py" "$dex"
done
cp "$STAGE/classes.dex" "$ROOT/classes.dex"
# The common packer validates the manifest and includes plugin-owned assets/.
bash "$SDK_ROOT/tools/pack.sh" "$ROOT" "$OUT"
