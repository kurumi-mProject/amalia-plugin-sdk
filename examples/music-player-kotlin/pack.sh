#!/usr/bin/env bash
# pack.sh — компилирует плагин и упаковывает в .amaliaplug
#
# Использование:
#   ./pack.sh
#
# Требования:
#   • JDK 17
#   • Android SDK: platforms;android-34 + build-tools;34.0.0
#   • Gradle (скачается автоматически через wrapper, если есть)
#   • ANDROID_HOME задана

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
D8="$ANDROID_HOME/build-tools/34.0.0/d8"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
OUT="$ROOT/dist"

echo "==> Проверяем инструменты..."
if [[ ! -f "$D8" ]]; then
    echo "ERROR: d8 не найден: $D8"
    echo "Установите: sdkmanager 'build-tools;34.0.0'"
    exit 1
fi
if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "ERROR: android.jar не найден: $ANDROID_JAR"
    echo "Установите: sdkmanager 'platforms;android-34'"
    exit 1
fi

echo "==> Собираем plugin.jar..."
cd "$ROOT"
./gradlew --no-daemon :plugin:jar

JAR="$ROOT/plugin/build/libs/plugin.jar"
if [[ ! -f "$JAR" ]]; then
    echo "ERROR: jar не найден после сборки: $JAR"
    exit 1
fi

echo "==> Конвертируем jar → dex..."
mkdir -p "$OUT/dex"
"$D8" --min-api 26 \
    --lib "$ANDROID_JAR" \
    --output "$OUT/dex/" \
    "$JAR"

echo "==> Упаковываем .amaliaplug..."
mkdir -p "$OUT"
cp "$ROOT/plugin.json" "$OUT/dex/plugin.json"
(cd "$OUT/dex" && zip -r "$OUT/music-player.amaliaplug" plugin.json classes.dex)
rm "$OUT/dex/plugin.json"

echo ""
echo "✅ Готово: $OUT/music-player.amaliaplug"
echo ""
sha256sum "$OUT/music-player.amaliaplug"
