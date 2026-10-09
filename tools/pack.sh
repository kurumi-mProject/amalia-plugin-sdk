#!/usr/bin/env bash
# Собирает папку плагина в один .amaliaplug и проверяет его.
#
# Использование:
#   tools/pack.sh examples/theme-neon-glass
#   tools/pack.sh examples/theme-neon-glass dist/
#
# Что делает:
#   1. валидирует plugin.json (tools/validate.py);
#   2. складывает plugin.json + classes.dex (если есть) в один zip;
#   3. проверяет, что plugin.json лежит в КОРНЕ архива;
#   4. печатает sha256 и размер — их кладут в каталог магазина.
#
# Зависимостей нет: zip-архив собирается через python3, поэтому скрипт
# работает и там, где не установлен `zip`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${1:-}"
OUT_DIR="${2:-$ROOT_DIR/dist}"

if [[ -z "$SOURCE_DIR" ]]; then
    echo "использование: tools/pack.sh <папка-плагина> [куда-сложить]" >&2
    exit 1
fi
if [[ ! -d "$SOURCE_DIR" ]]; then
    echo "нет такой папки: $SOURCE_DIR" >&2
    exit 1
fi
if [[ ! -f "$SOURCE_DIR/plugin.json" ]]; then
    echo "в $SOURCE_DIR нет plugin.json" >&2
    exit 1
fi

# 1. Валидация до упаковки: ошибки видно до того, как архив уйдёт в каталог.
python3 "$ROOT_DIR/tools/validate.py" "$SOURCE_DIR/plugin.json"

# Имя пакета — из названия плагина: пользователь получает «neon-glass.amaliaplug»,
# а не «plugin.amaliaplug». Если название не slug-уется, берём хвост id.
PLUGIN_ID="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['id'])" "$SOURCE_DIR/plugin.json")"
PLUGIN_NAME="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['name'])" "$SOURCE_DIR/plugin.json")"
SLUG="$(python3 -c "
import re, sys
name = sys.argv[1]
slug = re.sub(r'[^a-z0-9]+', '-', name.lower()).strip('-')
print(slug or 'plugin')
" "$PLUGIN_NAME")"
PACKAGE_NAME="${SLUG:-${PLUGIN_ID##*.}}"
TARGET="$OUT_DIR/${PACKAGE_NAME}.amaliaplug"

mkdir -p "$OUT_DIR"

# 2. Упаковка. Кладём только то, что хост реально читает: манифест и,
#    если есть, dex. Лишние файлы в пакете — лишний вес загрузки.
python3 - "$SOURCE_DIR" "$TARGET" <<'PY'
import pathlib, sys, zipfile

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])

# Порядок файлов фиксирован, отметки времени не пишутся: одинаковый вход
# даёт одинаковый sha256. Иначе сумма меняется на каждом запуске, и
# сравнить две сборки невозможно.
entries = [source / "plugin.json"]
dex = source / "classes.dex"
if dex.is_file():
    entries.append(dex)

with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in entries:
        info = zipfile.ZipInfo(path.name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        archive.writestr(info, path.read_bytes())

print(f"упаковано файлов: {len(entries)}: " + ", ".join(p.name for p in entries))
PY

# 3. Проверка структуры: plugin.json обязан быть в корне.
if ! python3 -c "
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
sys.exit(0 if 'plugin.json' in names else 1)
" "$TARGET"; then
    echo "ОШИБКА: plugin.json не в корне архива" >&2
    exit 1
fi

# 4. Итог: контрольная сумма и размер — прямо в строку каталога магазина.
python3 - "$TARGET" "$SOURCE_DIR/plugin.json" <<'PY'
import hashlib, json, pathlib, sys

target = pathlib.Path(sys.argv[1])
manifest = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
data = target.read_bytes()

print()
print(f"готово: {target}")
print(f"размер: {len(data)}")
print(f"sha256: {hashlib.sha256(data).hexdigest()}")
print()
print("строка для каталога магазина:")
print(json.dumps({
    "id": manifest["id"],
    "name": manifest["name"],
    "version": manifest["version"],
    "author": manifest.get("author", ""),
    "category": manifest.get("category", "general"),
    "description": manifest.get("description", ""),
    "iconName": manifest.get("iconName", ""),
    "downloadUrl": f"https://example.com/plugins/{target.name}",
    "sha256": hashlib.sha256(data).hexdigest(),
    "sizeBytes": len(data),
}, ensure_ascii=False, indent=2))
PY
