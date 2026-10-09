#!/usr/bin/env python3
"""Валидатор плагина Amalia — запускается до публикации.

Проверяет то, на чём чаще всего спотыкаются авторы: опечатки в категории
и иконке, забытый `id`, `plugin.json` не в корне архива, `entryPoint` без
`classes.dex`. Всё это хост отвергнет или молча проигнорирует — лучше
узнать об этом здесь, чем от пользователя.

Использование:
    python3 tools/validate.py plugin.json
    python3 tools/validate.py my-plugin.amaliaplug
    python3 tools/validate.py examples/          # все плагины в дереве
Выход: 0 — всё хорошо, 1 — есть ошибки.
"""
from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path

CATEGORIES = {
    "theme", "engine", "tools", "screen",
    "widget", "prompt", "phrases", "command", "general",
}

ICON_NAMES = {
    "voice", "wave", "mic", "history", "settings", "repeat", "copy",
    "alert", "close", "lamp", "add", "palette", "engine", "tools",
    "screen", "widget", "prompt", "phrases", "command",
}

MOTIFS = {"OFF", "AUTO", "SAKURA", "MAPLE", "FIREFLY", "SNOW", "STARS"}

ID_RE = re.compile(r"^[a-z0-9]+(\.[a-z0-9_-]+)+$")
VERSION_RE = re.compile(r"^\d+(\.\d+)*([-+].*)?$")


class Report:
    def __init__(self, label: str) -> None:
        self.label = label
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    def print(self) -> None:
        status = "OK  " if not self.errors else "FAIL"
        print(f"{status} {self.label}")
        for warning in self.warnings:
            print(f"       ! {warning}")
        for error in self.errors:
            print(f"       x {error}")


def validate_manifest(data: dict, report: Report, has_dex: bool, packaged: bool = False) -> None:
    for field in ("id", "name", "version"):
        value = data.get(field)
        if not isinstance(value, str) or not value.strip():
            report.error(f"обязательное поле «{field}» отсутствует или пустое")

    plugin_id = data.get("id", "")
    if isinstance(plugin_id, str) and plugin_id and not ID_RE.match(plugin_id):
        report.error(
            f"id «{plugin_id}» не похож на обратный домен "
            "(ожидается com.example.my_plugin)",
        )

    version = data.get("version", "")
    if isinstance(version, str) and version and not VERSION_RE.match(version):
        report.warn(f"version «{version}» не похож на semver — обновления могут не находиться")

    category = data.get("category", "general")
    if category not in CATEGORIES:
        report.error(
            f"category «{category}» неизвестна; допустимые: "
            + ", ".join(sorted(CATEGORIES)),
        )
        category = None

    icon = data.get("iconName", "")
    if icon and icon not in ICON_NAMES:
        report.warn(
            f"iconName «{icon}» не из набора хоста — будет иконка категории; "
            "допустимые: " + ", ".join(sorted(ICON_NAMES)),
        )

    entry = data.get("entryPoint")
    if entry:
        if not has_dex:
            # В собранном пакете отсутствие dex — ошибка: плагин не запустится.
            # В дереве исходников dex ещё не собран — это нормальный этап.
            if packaged:
                report.error("entryPoint задан, но classes.dex в пакете нет")
            else:
                report.warn(
                    "entryPoint задан, но classes.dex ещё нет "
                    "(нормально для исходников — соберите dex перед упаковкой)",
                )
        if not re.match(r"^[A-Za-z_][A-Za-z0-9_.]*$", str(entry)):
            report.error(f"entryPoint «{entry}» не похож на имя класса")
    elif has_dex:
        report.warn("classes.dex есть, но entryPoint не задан — код не запустится")

    themes = data.get("themes") or []
    if not isinstance(themes, list):
        report.error("themes должен быть массивом")
        themes = []
    seen: set[str] = set()
    for index, theme in enumerate(themes):
        if not isinstance(theme, dict):
            report.error(f"themes[{index}] должен быть объектом")
            continue
        theme_id = theme.get("id")
        if not isinstance(theme_id, str) or not theme_id.strip():
            report.error(f"themes[{index}].id отсутствует")
        elif theme_id in seen:
            report.error(f"themes[{index}].id «{theme_id}» повторяется — первый победит")
        else:
            seen.add(theme_id)
        _validate_palettes(theme, index, report)

    phrases = data.get("phrases") or {}
    if not isinstance(phrases, dict):
        report.error("phrases должен быть объектом вида {\"en\": [\"…\"]}")
    else:
        for tag, values in phrases.items():
            if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
                report.error(f"phrases[\"{tag}\"] должен быть массивом строк")
        if phrases and "ru" not in phrases and "en" not in phrases:
            report.warn("нет ни «ru», ни «en» — фразы не появятся ни в одной локали Амалии")
        for values in phrases.values():
            if isinstance(values, list):
                for phrase in values:
                    if isinstance(phrase, str) and len(phrase) > 90:
                        report.warn(
                            f"фраза длиннее 90 символов будет читаться вслух долго: "
                            f"«{phrase[:40]}…»",
                        )


def _validate_palettes(theme: dict, index: int, report: Report) -> None:
    """Проверяет палитры темы: цвета, границы, мотивы."""
    for key in ("preview", "staticDark", "staticLight"):
        spec = theme.get(key)
        if spec is None:
            continue
        if not isinstance(spec, dict):
            report.error(f"themes[{index}].{key} должен быть объектом")
            continue
        _validate_palette(spec, f"themes[{index}].{key}", report, require_stops=False)

    ladder = theme.get("ladder") or []
    if not isinstance(ladder, list):
        report.error(f"themes[{index}].ladder должен быть массивом")
        return
    if len(ladder) == 1:
        report.warn(f"themes[{index}].ladder из одной ступени работает как статичная тема")
    for step_index, step in enumerate(ladder):
        if not isinstance(step, dict):
            report.error(f"themes[{index}].ladder[{step_index}] должен быть объектом")
            continue
        if not isinstance(step.get("cct"), int):
            report.error(f"themes[{index}].ladder[{step_index}].cct должен быть числом")
        palette = step.get("palette")
        if not isinstance(palette, dict):
            report.error(f"themes[{index}].ladder[{step_index}].palette отсутствует")
            continue
        _validate_palette(palette, f"themes[{index}].ladder[{step_index}].palette", report)


def _validate_palette(spec: dict, label: str, report: Report, require_stops: bool = True) -> None:
    stops = spec.get("stops") or []
    if require_stops and not stops:
        report.error(f"{label}.stops пуст — фону нечего рисовать")
    for stop_index, stop in enumerate(stops):
        if not isinstance(stop, dict):
            report.error(f"{label}.stops[{stop_index}] должен быть объектом")
            continue
        color = stop.get("color")
        if not isinstance(color, str) or not re.match(r"^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$", color):
            report.error(f"{label}.stops[{stop_index}].color «{color}» не #RRGGBB / #AARRGGBB")
        position = stop.get("position")
        if not isinstance(position, (int, float)) or not 0 <= position <= 1:
            report.error(f"{label}.stops[{stop_index}].position должен быть 0..1")

    motif = spec.get("motif")
    if motif is not None and motif not in MOTIFS:
        report.error(f"{label}.motif «{motif}» неизвестен; допустимые: " + ", ".join(sorted(MOTIFS)))

    sheen = spec.get("sheen")
    if isinstance(sheen, (int, float)) and sheen > 0.15:
        report.warn(f"{label}.sheen {sheen} — стекло начнёт выглядеть как пластик (>0.15)")

    auroras = spec.get("auroras") or []
    if len(auroras) > 4:
        report.warn(f"{label}.auroras: {len(auroras)} цветов — фон станет шумным")
    for color in auroras:
        if not isinstance(color, str) or not color.startswith("#"):
            report.error(f"{label}.auroras содержит не цвет: {color!r}")


def check_manifest_file(path: Path, has_dex: bool = False) -> Report:
    report = Report(str(path))
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        report.error(f"plugin.json не является валидным JSON: {error}")
        return report
    if not isinstance(data, dict):
        report.error("корень plugin.json должен быть объектом")
        return report
    validate_manifest(data, report, has_dex, packaged=False)
    return report


def check_package(path: Path) -> Report:
    report = Report(str(path))
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if "plugin.json" not in names:
                nested = [n for n in names if n.endswith("/plugin.json")]
                hint = f" (найден в «{nested[0]}» — уберите лишнюю папку)" if nested else ""
                report.error(f"plugin.json не в корне архива{hint}")
                return report
            has_dex = "classes.dex" in names
            data = json.loads(archive.read("plugin.json").decode("utf-8"))
    except zipfile.BadZipFile:
        report.error("файл не является zip-архивом")
        return report
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        report.error(f"plugin.json внутри пакета не читается: {error}")
        return report
    if not isinstance(data, dict):
        report.error("корень plugin.json должен быть объектом")
        return report
    validate_manifest(data, report, has_dex, packaged=True)
    if path.suffix != ".amaliaplug":
        report.warn("расширение файла не .amaliaplug — пользователи ждут именно его")
    return report


def collect(target: Path) -> list[Path]:
    if target.is_dir():
        found = sorted(target.rglob("plugin.json")) + sorted(target.rglob("*.amaliaplug"))
        return [p for p in found if ".git" not in p.parts]
    return [target]


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 1

    reports: list[Report] = []
    for raw in argv[1:]:
        target = Path(raw)
        if not target.exists():
            report = Report(raw)
            report.error("путь не существует")
            reports.append(report)
            continue
        for path in collect(target):
            if path.name == "plugin.json":
                reports.append(check_manifest_file(path, has_dex=(path.parent / "classes.dex").exists()))
            elif path.suffix == ".amaliaplug":
                reports.append(check_package(path))

    if not reports:
        print("нечего проверять: ни plugin.json, ни .amaliaplug не найдены")
        return 1

    for report in reports:
        report.print()

    failed = sum(1 for r in reports if r.errors)
    warned = sum(1 for r in reports if r.warnings and not r.errors)
    print(f"\nпроверено: {len(reports)} · ошибок: {failed} · предупреждений: {warned}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
