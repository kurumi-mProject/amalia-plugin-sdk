# Amalia Plugin SDK

**Amalia** — голосовой ассистент для Android (Kotlin, Jetpack Compose). Ядро у
неё намеренно маленькое: распознавание речи, языковая модель, голос — это и
есть продукт. Всё остальное — **плагин**: темы, выбор движков, инструменты
устройства, голосовые команды, экраны, уведомления, фоновые функции,
приветственные фразы, черты характера.

Этот репозиторий — SDK: документация, формат пакета, контракты расширений и
рабочие примеры.

> **Читать на других языках:**
> [English](README.md) · [中文](README.zh.md) · [Español](README.es.md) ·
> [日本語](README.ja.md) · [Deutsch](README.de.md) · [Français](README.fr.md) ·
> [हिन्दी](README.hi.md) · [العربية](README.ar.md)

---

## Что умеет плагин

| Вклад | Интерфейс | Пример |
|---|---|---|
| Темы (фон, акценты, циркадная лестница) | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| Движки: распознавание, LLM, синтез | `EngineContributor` | — |
| Инструменты, которые зовёт модель | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| Приветствия главного экрана | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| Блоки характера в системном промпте | `PromptContributor` | — |
| Заявка на нужные возможности | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| Команды **до** модели | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| Свой экран внутри приложения | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Уведомления с кнопками | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| Долгая фоновая работа (foreground-сервис) | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| Приглушение звука, пока говорит Амалия | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Замена визуала главного экрана | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

Полный справочник — **[docs/extension-points.md](docs/extension-points.md)**.

---

## Два вида плагинов

**1. Декларативный — без кода.** Только `plugin.json` с темами и фразами.
Этого хватает для большинства запросов «сделай, чтобы выглядело иначе».

**2. Кодовый.** `plugin.json` плюс `classes.dex` с классом на Kotlin/Java,
который реализует `AmaliaPlugin` и нужные вклады.

Оба поставляются одним файлом **`.amaliaplug`** — это zip:

```
my-plugin.amaliaplug
├── plugin.json     ← обязателен
├── classes.dex     ← необязателен (только кодовые плагины)
└── ...             ← любые свои ресурсы, читаете их сами
```

---

## Быстрый старт — тема за пять минут

1. Создайте `plugin.json`:

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "Тёплое стекло на закате",
  "iconName": "palette",
  "minHostVersion": "2.0.0",
  "themes": [
    {
      "id": "com.example.theme.sunset.dusk",
      "name": "Sunset Dusk",
      "staticDark": {
        "name": "Sunset",
        "stops": [
          { "color": "#1A0F14", "position": 0.0 },
          { "color": "#2A1418", "position": 0.55 },
          { "color": "#120A0D", "position": 1.0 }
        ],
        "isDark": true,
        "cct": 2600,
        "auroras": ["#FF7A59", "#FF3D7F"],
        "motif": "FIREFLY"
      }
    }
  ],
  "phrases": { "ru": ["Закат включён."], "en": ["Sunset is on."] }
}
```

2. Соберите пакет (в корне архива обязан лежать `plugin.json`):

```bash
cd my-plugin
zip -r ../sunset.amaliaplug plugin.json
```

3. В приложении: **Настройки → Плагины → Установить из файла** и выберите
   `.amaliaplug`. Либо выложите файл и добавьте его в каталог
   ([docs/catalog.md](docs/catalog.md)).

Подробнее: **[docs/quickstart.md](docs/quickstart.md)**.

---

## Что где лежит

| Путь | О чём |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | От нуля до установленного плагина |
| [`docs/manifest.md`](docs/manifest.md) | Все поля `plugin.json` |
| [`docs/themes.md`](docs/themes.md) | Декларативные темы и палитры |
| [`docs/packaging.md`](docs/packaging.md) | Сборка `classes.dex`, упаковка, проверка |
| [`docs/extension-points.md`](docs/extension-points.md) | Все интерфейсы-вклады |
| [`docs/capabilities.md`](docs/capabilities.md) | Возможности, особые доступы, разрешения |
| [`docs/localization.md`](docs/localization.md) | Мультиязычные плагины |
| [`docs/catalog.md`](docs/catalog.md) | Протокол магазина и публикация |
| [`docs/security.md`](docs/security.md) | Модель доверия — читать до публикации |
| [`examples/`](examples) | Рабочие примеры: от простого до полноценной фичи |
| [`sdk/api/`](sdk/api) | Референс-копии контрактов хоста |
| [`tools/`](tools) | Валидатор и упаковщик пакетов |

---

## Примеры

| Пример | Вид | Что показывает |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | декларативный | полная тема: превью, лестница, мотив, фразы |
| [`phrases-localized`](examples/phrases-localized) | декларативный | приветствия на девяти языках и откат по тегу |
| [`reminder-kotlin`](examples/reminder-kotlin) | код | **новая функция**: голосовые напоминания, без UI и разрешений |
| [`command-kotlin`](examples/command-kotlin) | код | команда, срабатывающая до модели |
| [`tool-kotlin`](examples/tool-kotlin) | код | инструмент, который зовёт модель |
| [`notification-kotlin`](examples/notification-kotlin) | код | уведомление с кнопками + фоновое состояние |
| [`store-catalog.json`](examples/store-catalog.json) | данные | минимальный каталог для магазина |

Инструменты:

```bash
# проверить манифест или готовый пакет
python3 tools/validate.py examples/reminder-kotlin/plugin.json
python3 tools/validate.py dist/reminder.amaliaplug

# собрать пакет и получить строку для каталога (с sha256 и размером)
tools/pack.sh examples/reminder-kotlin
```

---

## Поддержка языков

Амалия выходит на девяти языках: русский (по умолчанию), английский,
японский, немецкий, испанский, французский, китайский, хинди, арабский.

Плагин **не обязан** быть переведён, но всё, что читает пользователь, стоит
перевести. Ключи `phrases` — теги языка; `en-US` откатывается к `en`.
Подробнее: **[docs/localization.md](docs/localization.md)**.

---

## Установка без магазина

Амалия понимает deep-link, поэтому плагин можно раздать ссылкой:

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

Приложение откроется, найдёт запись в своём каталоге и покажет то же
стеклянное подтверждение, что и в магазине.

---

## Состояние и честные ограничения

Лучше знать это заранее:

- **Опубликованного Gradle-артефакта пока нет.** Контракты живут внутри
  приложения; в [`sdk/api/`](sdk/api) лежат референс-копии, против которых
  можно собрать плагин. Отдельный `:plugin-api` — в планах.
- **Кодовый плагин — доверенный код.** Песочницы нет: плагин исполняется в
  процессе приложения. Ставьте только те плагины, чьему автору доверяете.
- **Подписи пакетов пока нет.** Целостность проверяется необязательной суммой
  `sha256` из каталога, а не подписью.
- **Визуальный слой закрыт.** Плагин выбирает иконку из набора хоста
  (`iconName`) и может занять **слот** главного экрана, но рисовать свободно
  не даёт: «стекло, свет, воздух» — это продукт.
- **Некоторые вещи нельзя**: добавить свои строковые ресурсы, повесить
  `BroadcastReceiver` в манифест, запросить точный будильник. Каждый пример
  объясняет, чем это заменяется.

---

## Лицензия

MIT — см. [LICENSE](LICENSE).
