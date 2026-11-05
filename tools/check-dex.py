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
import struct
import sys

# Дескрипторы типов в dex: Lcom/my/amali/plugin/AmaliaPlugin;
BANNED = (
    b"Lcom/my/amali/plugin/",
    b"Lcom/my/amali/extension/",
)


def defined_classes(data: bytes) -> list[bytes]:
    """Читает дескрипторы из class_defs, не включая внешние ссылки из type_ids."""
    if len(data) < 0x70 or data[:4] != b"dex\n" or data[4:8] not in (
        b"035\0", b"037\0", b"038\0", b"039\0", b"040\0",
    ):
        raise ValueError("неподдерживаемый или повреждённый заголовок DEX")
    file_size, header_size, endian_tag = struct.unpack_from("<III", data, 0x20)
    if file_size != len(data) or header_size != 0x70 or endian_tag != 0x12345678:
        raise ValueError("некорректный размер или порядок байтов DEX")

    def table(header_offset: int, item_size: int) -> tuple[int, int]:
        size, offset = struct.unpack_from("<II", data, header_offset)
        if size and (offset < header_size or offset + size * item_size > len(data)):
            raise ValueError("таблица DEX выходит за границы файла")
        return size, offset

    string_count, strings_offset = table(0x38, 4)
    type_count, types_offset = table(0x40, 4)
    class_count, classes_offset = table(0x60, 32)
    descriptors = []
    for index in range(class_count):
        class_index = struct.unpack_from("<I", data, classes_offset + index * 32)[0]
        if class_index >= type_count:
            raise ValueError("некорректный индекс типа класса")
        string_index = struct.unpack_from("<I", data, types_offset + class_index * 4)[0]
        if string_index >= string_count:
            raise ValueError("некорректный индекс дескриптора класса")
        offset = struct.unpack_from("<I", data, strings_offset + string_index * 4)[0]
        if offset < header_size or offset >= len(data):
            raise ValueError("строка DEX выходит за границы файла")
        # string_data_item начинается с длины UTF-16 в формате ULEB128.
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("оборванная длина строки DEX")
            byte = data[offset]
            offset += 1
            if not byte & 0x80:
                break
        else:
            raise ValueError("некорректная длина строки DEX")
        end = data.find(b"\0", offset)
        if end == -1:
            raise ValueError("оборванный дескриптор класса DEX")
        descriptor = data[offset:end]
        if not descriptor.startswith(b"L") or not descriptor.endswith(b";"):
            raise ValueError("некорректный дескриптор класса DEX")
        descriptors.append(descriptor)
    return descriptors


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 1

    dex = pathlib.Path(argv[1])
    if not dex.is_file():
        print(f"нет файла: {dex}", file=sys.stderr)
        return 1

    try:
        data = dex.read_bytes()
        classes = defined_classes(data)
    except (OSError, ValueError) as error:
        print(f"ОШИБКА: {dex}: {error}", file=sys.stderr)
        return 1

    # type_ids и string_ids содержат и внешние типы: ссылки на API хоста
    # допустимы. Запрещены только собственные определения из class_defs.
    found = [
        descriptor.decode("utf-8", errors="replace")
        for descriptor in classes
        if descriptor.startswith(BANNED)
    ]
    if found:
        print(f"ОШИБКА: {dex}: найдены классы хоста — {', '.join(found)}", file=sys.stderr)
        print("Уберите их из компиляции (compileOnly) — в dex они попадать не должны.", file=sys.stderr)
        return 1

    print(f"ok: {dex} ({len(data)} байт) — только классы плагина")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
