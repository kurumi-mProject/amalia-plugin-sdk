#!/usr/bin/env python3
"""Проверяет, что в собранный classes.dex не уехали контракты хоста.

Зачем: плагин загружается `DexClassLoader` с загрузчиком приложения в
качестве родителя. Если в dex окажется копия `com/my/amali/plugin/*`, она
никогда не будет использована (родитель выигрывает), а вот ошибки загрузки
и путаницу с типами даст охотно. Хост уже содержит эти классы — дублировать
их нельзя, поэтому проверяем до релиза, а не после жалобы пользователя.

Использование:
    python3 tools/check-dex.py path/to/classes.dex
Выход: 0 — чисто, 1 — найдены классы хоста.
"""
from __future__ import annotations

import pathlib
import sys

# Дескрипторы типов в dex: Lcom/my/amali/plugin/AmaliaPlugin;
BANNED = (
    b"Lcom/my/amali/plugin/",
    b"Lcom/my/amali/extension/",
)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 1

    dex = pathlib.Path(argv[1])
    if not dex.is_file():
        print(f"нет файла: {dex}", file=sys.stderr)
        return 1

    data = dex.read_bytes()
    found = [marker.decode() for marker in BANNED if marker in data]
    if found:
        print(f"ОШИБКА: {dex}: найдены классы хоста — {', '.join(found)}", file=sys.stderr)
        print("Уберите их из компиляции (compileOnly) — в dex они попадать не должны.", file=sys.stderr)
        return 1

    print(f"ok: {dex} ({len(data)} байт) — только классы плагина")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
