# Amalia Plugin SDK

**Amalia** is a voice assistant for Android (Kotlin, Jetpack Compose). Its core is
deliberately small: speech recognition, a language model, a voice — that is the
product. Everything else is a **plugin**: themes, engine choices, device tools,
voice commands, screens, notifications, background features, greeting phrases,
character blocks.

This repository is the SDK: documentation, the package format, the extension
contracts, and runnable examples.

> **Read in your language:**
> [Русский](README.ru.md) · [中文](README.zh.md) · [Español](README.es.md) ·
> [日本語](README.ja.md) · [Deutsch](README.de.md) · [Français](README.fr.md) ·
> [हिन्दी](README.hi.md) · [العربية](README.ar.md)

---

## What a plugin can do

| Contribution | Interface | Example |
|---|---|---|
| Themes (backgrounds, accents, circadian ladder) | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| Engines: speech recognition, LLM, TTS | `EngineContributor` | — |
| Tools the model can call | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| Greeting phrases on the home screen | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| Character / system prompt blocks | `PromptContributor` | — |
| Ask for the capabilities you need | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| Voice commands handled **before** the model | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin), [command-kotlin](examples/command-kotlin) |
| Your own screen inside the app | `ScreenContributor` | [extension-points.md](docs/extension-points.md) |
| Notifications with action buttons | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| Long-running background state (foreground service) | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| Mute other audio while Amalia speaks | `AudioSessionContributor` | [extension-points.md](docs/extension-points.md) |
| Replace the home-screen visual (wave, lamp, greeting) | `WidgetContributor` | [extension-points.md](docs/extension-points.md) |

Full reference: **[docs/extension-points.md](docs/extension-points.md)**.

---

## Two kinds of plugins

**1. Declarative — no code at all.** A `plugin.json` describing themes and
phrases. This covers most "make it look different" requests.

**2. Code plugin.** A `plugin.json` plus a `classes.dex` with a Kotlin/Java class
that implements `AmaliaPlugin` and any contributions it needs.

Both are shipped as a single **`.amaliaplug`** file — a zip:

```
my-plugin.amaliaplug
├── plugin.json     ← mandatory
├── classes.dex     ← optional (code plugins only)
└── ...             ← any extra resources you read yourself
```

---

## Quick start — a declarative theme in 5 minutes

1. Create `plugin.json`:

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "Warm dusk glass",
  "iconName": "palette",
  "minHostVersion": "2.0.0",
  "themes": [
    {
      "id": "com.example.theme.sunset.dusk",
      "name": "Sunset Dusk",
      "forceDark": true,
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
  "phrases": {
    "ru": ["Закат включён."],
    "en": ["Sunset is on."]
  }
}
```

2. Zip it (the file **must** contain `plugin.json` at its root):

```bash
cd my-plugin && zip -r ../sunset.amaliaplug plugin.json
```

3. In Amalia: **Settings → Plugins → Install from file**, pick the
   `.amaliaplug` — or host it and publish it in a catalog
   ([docs/catalog.md](docs/catalog.md)).

Full guide: **[docs/quickstart.md](docs/quickstart.md)**.

---

## Repository map

| Path | What |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | From zero to an installed plugin |
| [`docs/manifest.md`](docs/manifest.md) | Every `plugin.json` field |
| [`docs/themes.md`](docs/themes.md) | Declarative themes and palettes |
| [`docs/packaging.md`](docs/packaging.md) | Building `classes.dex`, zipping, checking |
| [`docs/extension-points.md`](docs/extension-points.md) | All contribution interfaces |
| [`docs/capabilities.md`](docs/capabilities.md) | Capabilities, special accesses, permissions |
| [`docs/localization.md`](docs/localization.md) | Multi-language plugins and phrase keys |
| [`docs/catalog.md`](docs/catalog.md) | The store protocol and publishing |
| [`docs/security.md`](docs/security.md) | Trust model — read before shipping |
| [`examples/`](examples) | Working examples, from trivial to a full plugin |
| [`sdk/api/`](sdk/api) | Reference copies of the host contracts |

---

## Examples

| Example | Kind | Shows |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | declarative | full theme: preview, ladder, motif, phrases |
| [`phrases-localized`](examples/phrases-localized) | declarative | greetings in 9 languages, key fallback |
| [`reminder-kotlin`](examples/reminder-kotlin) | code | a new ability: voice reminders (no UI, no permissions) |
| [`command-kotlin`](examples/command-kotlin) | code | voice command handled before the model |
| [`tool-kotlin`](examples/tool-kotlin) | code | a tool the model can call |
| [`notification-kotlin`](examples/notification-kotlin) | code | notifications with buttons + background state |
| [`store-catalog.json`](examples/store-catalog.json) | data | a minimal catalog for the store |

---

## Get a ready-to-install plugin package

You do not have to build anything to try this. The *Build plugin package*
workflow compiles a code example and publishes the finished `.amaliaplug`:

1. **Actions → Build plugin package → Run workflow** (default example:
   `reminder-kotlin`).
2. Download the artifact — or grab it from the **`plugins-latest`** release.
3. In Amalia: **Settings → Plugins → Install from file**.

---

## Language support

Amalia ships in nine locales: Russian (default), English, Japanese, German,
Spanish, French, Chinese, Hindi, Arabic.

A plugin is **not required** to be translated — but phrases and any user-facing
text should be. Keys are language tags, and `en-US` falls back to `en`.
See **[docs/localization.md](docs/localization.md)**.

---

## Install a plugin without the store

Amalia understands a deep link, so you can share a plugin with a URL:

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

The app opens, finds the entry in its catalog, and shows the same
glass confirmation dialog as in the store.

---

## Status and honest limitations

We would rather you knew this up front:

- **No published Gradle artifact yet.** The contracts currently live inside the
  host app. [`sdk/api/`](sdk/api) holds reference copies you can compile against;
  a standalone `:plugin-api` artifact is planned.
- **Code plugins are trusted code.** There is no sandbox: a plugin runs in the
  app process. Only install plugins whose author you trust.
- **No package signing yet.** Integrity is verified by an optional `sha256`
  checksum from the catalog, not by a signature.
- **The visual layer is not open for drawing.** Plugins select from the host's
  vector icon set (`iconName`) and may take over a home-screen *slot*, but they
  do not get to paint freely — the "glass, light, air" design is the product.
- **Background and audio contributions are executed**, but there is no built-in
  plugin demonstrating them yet; a media player plugin is the intended reference.

---

## License

MIT — see [LICENSE](LICENSE).
