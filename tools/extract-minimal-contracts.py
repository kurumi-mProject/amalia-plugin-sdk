#!/usr/bin/env python3
"""Собирает минимальный набор контрактов из полного ExtensionPoints.kt.

Зачем: чтобы собрать плагин с командами, уведомлениями и фоном, не нужно
тянуть на сборочный classpath Compose (экраны и виджеты) и весь остальной
хост. Этот скрипт вырезает из полного файла ровно те объявления, которые
нужны такому плагину, и кладёт их в `sdk/api/minimal/Contributions.kt`.

Так копия не разъезжается с оригиналом: меняется контракт в хосте —
перезапускается скрипт, и видно diff.

Использование:
    python3 tools/extract-minimal-contracts.py [путь-к-ExtensionPoints.kt]
"""
from __future__ import annotations

import pathlib
import re
import sys

DEFAULT_SOURCE = pathlib.Path("sdk/api/ExtensionPoints.kt")
TARGET = pathlib.Path("sdk/api/minimal/Contributions.kt")

# Объявления, нужные плагину без UI. Порядок = порядок в файле.
WANTED = [
    "CapabilityContributor",
    "CommandContributor",
    "CommandResult",
    "BackgroundContributor",
    "BackgroundState",
    "NotificationContributor",
    "NotificationHost",
    "NotificationSpec",
    "ActionSpec",
    "NotificationActionHandler",
]

DECL = re.compile(r"^(?:sealed\s+)?(?:interface|data class|enum class|object)\s+(\w+)\b")

HEADER = '''package com.my.amali.plugin

/**
 * Минимальный набор контрактов для сборки плагина без UI.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЗАЧЕМ ОТДЕЛЬНЫЙ ФАЙЛ, А НЕ ВЕСЬ ExtensionPoints.kt
 * ════════════════════════════════════════════════════════════════════
 *
 * Полные контракты описывают всё: экраны и виджеты на Compose, движки,
 * темы. Чтобы собрать плагин с командами, уведомлениями и фоном, тянуть
 * Compose на сборочный classpath не нужно — и не нужно, чтобы CI умел
 * собирать Android-библиотеки.
 *
 * Здесь ровно те объявления, которые нужны такому плагину. Текст взят
 * **дословно** из `ExtensionPoints.kt` хоста; при расхождении прав хост.
 *
 * Файл собран скриптом `tools/extract-minimal-contracts.py`, чтобы копия
 * не разъезжалась с оригиналом вручную.
 */

'''


def main(argv: list[str]) -> int:
    source = pathlib.Path(argv[1]) if len(argv) > 1 else DEFAULT_SOURCE
    if not source.is_file():
        print(f"нет файла: {source}", file=sys.stderr)
        return 1

    lines = source.read_text(encoding="utf-8").split("\n")
    blocks: list[str] = []

    for name in WANTED:
        start = next(
            (i for i, line in enumerate(lines) if (m := DECL.match(line)) and m.group(1) == name),
            None,
        )
        if start is None:
            print(f"объявление {name} не найдено", file=sys.stderr)
            return 1

        # KDoc непосредственно перед объявлением (между ними — только пустые строки)
        kdoc = start
        j = start - 1
        while j >= 0 and lines[j].strip() == "":
            j -= 1
        if j >= 0 and lines[j].strip().endswith("*/"):
            k = j
            while k >= 0 and not lines[k].strip().startswith("/**"):
                k -= 1
            if k >= 0:
                kdoc = k

        # Конец объявления — по балансу фигурных скобок
        depth = 0
        opened = False
        end = None
        for i in range(start, len(lines)):
            depth += lines[i].count("{") - lines[i].count("}")
            opened = opened or "{" in lines[i]
            if opened and depth == 0:
                end = i
                break
        if end is None:
            print(f"объявление {name} не закрылось", file=sys.stderr)
            return 1

        blocks.append("\n".join(lines[kdoc:end + 1]).rstrip())

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(HEADER + "\n\n".join(blocks) + "\n", encoding="utf-8")
    print(f"{TARGET}: объявлений {len(blocks)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
