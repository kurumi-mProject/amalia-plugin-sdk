# `plugin.json` reference

The manifest is the only mandatory file in a `.amaliaplug` package.

```json
{
  "id": "com.example.player",
  "name": "Player",
  "version": "1.0.0",
  "author": "your name",
  "category": "general",
  "description": "One line for the store card",
  "iconName": "wave",
  "minHostVersion": "2.0.0",
  "entryPoint": "com.example.player.PlayerPlugin",
  "themes": [],
  "phrases": { "en": ["Ready."] }
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `id` | string | **yes** | Reverse-domain, unique, stable. State is stored by it. |
| `name` | string | **yes** | Display name. |
| `version` | string | **yes** | Semver. Used for update detection. |
| `author` | string | no | Shown on the store card. |
| `category` | string | no | `theme` `engine` `tools` `screen` `widget` `prompt` `phrases` `command` `general`. Default `general`. |
| `description` | string | no | One line; the store card subtitle. |
| `iconName` | string | no | Icon from the host vector set (below). Empty → icon by category. |
| `minHostVersion` | string | no | Minimum Amalia version. Empty → compatible with every host. |
| `entryPoint` | string \| null | no | Fully-qualified class name inside `classes.dex`. `null` → purely declarative. |
| `themes` | array | no | Declarative themes. See [themes.md](themes.md). |
| `phrases` | object | no | Greeting phrases keyed by language tag. See [localization.md](localization.md). |

Unknown fields are **ignored**, not rejected — the parser is lenient, so a
manifest written for a newer host still installs on an older one.

---

## `iconName` — icon from the host set

Plugins never ship images. They **name** one of the host's icons so the list
stays in one visual system (one stroke, one set, the current theme accent):

`voice` `wave` `mic` `history` `settings` `repeat` `copy` `alert` `close`
`lamp` `add` `palette` `engine` `tools` `screen` `widget` `prompt` `phrases`
`command`

An unknown name is not an error: the host silently falls back to the category
icon. So an old plugin on a new host and a new plugin on an old host both work.

---

## `category` and what it implies

| Category | Typical contributions |
|---|---|
| `theme` | `ThemeContributor` |
| `engine` | `EngineContributor` |
| `tools` | `ToolContributor` |
| `screen` | `ScreenContributor` |
| `widget` | `WidgetContributor` |
| `prompt` | `PromptContributor` |
| `phrases` | `PhraseContributor` |
| `command` | `CommandContributor` |
| `general` | anything |

Category is a hint for grouping and iconography — it does not restrict which
interfaces you implement. A `theme` plugin may also add phrases.

---

## `entryPoint` rules

- Must be the fully-qualified name of a class that implements `AmaliaPlugin`
  and has a **no-argument constructor**.
- The class must be inside `classes.dex` at the package root.
- If the class is missing or fails to load, the plugin is skipped and the error
  is logged; other plugins are unaffected.

---

## Validating before you ship

- `plugin.json` must be valid JSON (no comments, no trailing commas).
- Every `theme.id` must be unique and reverse-domain style.
- `plugin.json` must be at the **root** of the zip:

```bash
unzip -l my-plugin.amaliaplug
# Archive: my-plugin.amaliaplug
#   Length      Name
#   --------    ----
#   1234        plugin.json          ← good
#   1234        my-plugin/plugin.json ← wrong, will not install
```

- Compute a checksum for the catalog:

```bash
sha256sum my-plugin.amaliaplug
```
