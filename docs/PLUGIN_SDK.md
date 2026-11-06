> **Legacy API reference.** For SDK3 use [SDK_CLI.md](SDK_CLI.md) and [migration](MIGRATION_V3.md). This document describes the retained v2 loader.

# Amalia Plugin SDK — руководство автора плагинов

Amalia 2.0 построена на плагинах: темы, движки (слух/мозг/голос), инструменты,
фразы и характер — всё заменяемо. Этот документ описывает, как написать
свой плагин и довести его до пользователей.

---

## 1. Формат пакета `.amaliaplug`

Пакет — обычный **zip** со следующей структурой:

```
neon-glass.amaliaplug
├── plugin.json          ← обязателен: манифест
├── classes.dex          ← опционален: кодовый плагин
└── (любые ресурсы — читайте сами из контекста)
```

Декларативный плагин (темы, фразы) — это **только `plugin.json`**, ни строчки кода.

---

## 2. Манифест `plugin.json`

```json
{
  "id": "com.example.theme.neon",
  "name": "Neon Glass",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "Неоновое стекло для Амалии",
  "iconName": "palette",
  "minHostVersion": "2.0.0",
  "entryPoint": null,
  "themes": [],
  "phrases": { "ru": ["Йо!"], "en": ["Yo!"] }
}
```

| Поле | Тип | Описание |
|---|---|---|
| `id` | string | Уникальный id, обычно обратный домен. По нему хранится вкл/выкл |
| `name` / `version` / `author` | string | Паспорт плагина для списка |
| `category` | string | `theme` `engine` `tools` `screen` `widget` `prompt` `phrases` `command` `general` |
| `iconName` | string | Необязательно. Иконка из набора хоста: `voice` `wave` `mic` `history` `settings` `repeat` `copy` `alert` `close` `lamp` `add` `palette` `engine` `tools` `screen` `widget` `prompt` `phrases` `command`. Пусто — по категории |
| `minHostVersion` | string | Минимальная версия Амалии; установка отклоняется, если хост старее |
| `entryPoint` | string\|null | Полное имя класса из `classes.dex`; `null` — чисто декларативный |
| `themes` | ThemeSpec[] | Декларативные темы (см. ниже) |
| `phrases` | map | Приветственные фразы по тегу языка |

---

## 3. Декларативная тема

Тема описывает: циркадную лестницу палитр (адаптация по времени суток),
статические палитры для ручного режима, превью и флаг «всегда тёмная».

```json
{
  "themes": [
    {
      "id": "com.example.theme.neon.midnight",
      "name": "Neon Midnight",
      "forceDark": true,
      "preview": { "palette": "…см. PaletteSpec…" },
      "staticDark": { "palette": "…" },
      "staticLight": { "palette": "…" },
      "ladder": [
        { "cct": 2400, "palette": { "…": "ночь" } },
        { "cct": 4200, "palette": { "…": "утро" } },
        { "cct": 5600, "palette": { "…": "день" } }
      ]
    }
  ]
}
```

**PaletteSpec** — палитра живого фона (все цвета — строки `#RRGGBB` / `#AARRGGBB`):

```json
{
  "name": "Neon Night",
  "stops": [
    { "color": "#0A0B14", "position": 0.0 },
    { "color": "#141026", "position": 0.55 },
    { "color": "#08060F", "position": 1.0 }
  ],
  "isDark": true,
  "cct": 2600,
  "melanopicDer": 0.15,
  "auroras": ["#FF2E88", "#7C4DFF", "#00E5FF"],
  "motif": "STARS",
  "motifAccent": "#9FA8FF",
  "motifAccentAlt": "#FF8AC2",
  "shadow": "#8A05060A",
  "sheen": 0.09
}
```

* `stops` — вертикальный градиент фона (позиция 0..1);
* `auroras` — цвета дрейфующих пятен; из первого выводится акцент всего UI;
* `motif` — декорации поверх: `OFF AUTO SAKURA MAPLE FIREFLY SNOW STARS`;
* лестница сортируется по `cct` автоматически (холодное → тёплое).

Типографика и формы наследуются от темы Liquid Glass: палитра — то, чем
темы реально различаются; шрифты без подгонки метрик чаще ломают вёрстку.
Кодовый плагин может переопределить и их.

---

## 4. Приветственные фразы

```json
"phrases": {
  "ru": ["Привет! Чем занята?", "Слушаю тебя."],
  "en": ["Hey! What's up?", "I'm listening."]
}
```

Фразы плагина смешиваются со встроенными на главном экране.
Ключ — тег языка (`ru`, `en-US` → префикс `en` тоже ищется).

---

## 5. Кодовый плагин

Если деклараций мало — пишите класс Kotlin/Java, кладите его в `classes.dex`
и указывайте `entryPoint: "com.example.neon.NeonPlugin"`.

```kotlin
class NeonPlugin : AmaliaPlugin, ThemeContributor, ToolContributor, PhraseContributor,
                   PromptContributor, EngineContributor {

    override val metadata = PluginMetadata(
        id = "com.example.neon",
        name = "Neon Pack",
        version = "1.0.0",
        category = PluginCategory.GENERAL,
    )

    override fun themes() = listOf(/* AmaliaThemeDefinition */)
    override fun tools(context: PluginContext) = listOf(/* AmaliaTool */)
    override fun greetingPhrases(lang: String) = listOf("Неон на связи!")
    override fun contribute(builder: PromptBuilder) {
        builder.block("Амалия любит неоновые метафоры.")
    }
    override fun engines() = listOf(/* EngineSlot: Stt/Llm/Tts */)
}
```

### Точки расширения (реализуйте нужные)

| Интерфейс | Что даёт |
|---|---|
| `ThemeContributor` | Темы → галерея «Внешний вид» |
| `EngineContributor` | Движки STT/LLM/TTS → конвейер |
| `ToolContributor` | Голосовые инструменты → LLM (tool calling) |
| `PhraseContributor` | Приветствия главного экрана |
| `PromptContributor` | Блоки системного промпта (характер) |

### Жизненный цикл

`onLoaded(ctx)` → (`onEnabled()` | `onDisabled()`)* → `onUnloaded()`.
Выключение пользователем не выгружает класс — но вклады исчезают из всех
реестров мгновенно. Методы вызываются на главном потоке; держите их дешёвыми.

### Правила id

Первый объявивший `id` выигрывает: конфликт со встроенной темой или чужим
плагином не подменяет существующее — ваш вклад просто не появится, пока
не смените id.

---

## 6. Магазин плагинов

Каталог — JSON по любому URL (свой хостинг, GitHub raw, Telegram-файл
с прямой ссылкой):

```json
{
  "items": [
    {
      "id": "com.example.theme.neon",
      "name": "Neon Glass",
      "version": "1.0.0",
      "author": "you",
      "category": "theme",
      "description": "Неоновое стекло",
      "downloadUrl": "https://example.com/neon.amaliaplug",
      "sha256": "…",
      "sizeBytes": 20480
    }
  ]
}
```

`sha256` необязателен, но рекомендован: несовпадение отклоняет установку.
Пользователь вставляет ссылку на каталог в **Настройки → Плагины → Магазин**,
либо устанавливает `.amaliaplug` прямо из Telegram/загрузок через
«Установить из файла».

---

## 7. Встроенные плагины (id для справки)

| id | Что несёт |
|---|---|
| `com.my.amali.theme.liquid_glass` | Тема Liquid Glass |
| `com.my.amali.theme.biophilic` | Тема Биофильная релаксация |
| `com.my.amali.engine.groq` | Groq Whisper + Groq LLM + Fish Audio |
| `com.my.amali.engine.mock` | Офлайн-заглушки конвейера |

Их можно выключить в «Настройки → Плагины» — например, отключив Mock,
вы гарантированно убираете офлайн-режим из конвейера.
