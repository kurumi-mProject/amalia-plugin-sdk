# Quick start

Everything a plugin is: a **zip** with a `plugin.json` at its root, named
`<something>.amaliaplug`. That is the whole format.

There are two levels:

- **declarative** — `plugin.json` only (themes, greeting phrases);
- **code** — `plugin.json` + `classes.dex` with a class implementing `AmaliaPlugin`.

Start declarative. Most ideas don't need code.

---

## 1. Minimal declarative plugin

Create a folder and a `plugin.json`:

```json
{
  "id": "com.example.phrases.hello",
  "name": "Hello Pack",
  "version": "1.0.0",
  "author": "your name",
  "category": "phrases",
  "description": "Extra greetings",
  "minHostVersion": "2.0.0",
  "phrases": {
    "en": ["Hello! Ready when you are."],
    "ru": ["Привет! Готова, когда ты."]
  }
}
```

Rules that matter:

- `id` — reverse-domain, **stable forever**. Enabled/disabled state is stored by
  `id`; changing it makes Amalia treat your plugin as a brand new one.
- `version` — semver. The store compares it to offer updates.
- `category` — one of `theme engine tools screen widget prompt phrases command general`.
  It decides which icon is drawn and how the plugin is grouped.

## 2. Package it

```bash
cd my-plugin
zip -r ../hello-pack.amaliaplug plugin.json
```

`plugin.json` must be at the **root** of the archive, not inside a folder.
Verify:

```bash
unzip -l hello-pack.amaliaplug     # must list plugin.json at the top level
```

## 3. Install it

Pick any of the three paths:

| Path | How |
|---|---|
| From a file | **Settings → Plugins → Install from file**, choose the `.amaliaplug` |
| From a link | `https://mirabel.tech/plugin?id=com.example.phrases.hello` |
| From a catalog | publish it (see [catalog.md](catalog.md)) and it appears in the store |

After install, the plugin is listed in **Settings → Plugins** with a switch.
Disabling it removes its contributions immediately — no restart.

## 4. Verify it works

- **Phrases** → open the assistant screen; your greetings join the rotation.
- **Themes** → **Settings → Appearance**; your theme appears in the gallery.

If it doesn't show up: check the `category`, that the `id` is unique, and that
`plugin.json` is really at the archive root.

---

## 5. Going further: a code plugin

When declarations aren't enough, add code:

1. Write a class implementing `AmaliaPlugin` plus the contributions you need
   (see [extension-points.md](extension-points.md)).
2. Compile it to `classes.dex` (see [packaging.md](packaging.md)).
3. Add `"entryPoint": "com.example.hello.HelloPlugin"` to `plugin.json`.
4. Zip `plugin.json` and `classes.dex` together.

The `id` inside `PluginMetadata` must match the `id` in `plugin.json` — the host
logs a mismatch instead of failing silently.

---

## Next

- [manifest.md](manifest.md) — every field
- [themes.md](themes.md) — palettes, ladders, motifs
- [extension-points.md](extension-points.md) — all contributions
- [capabilities.md](capabilities.md) — what you may ask for
- [security.md](security.md) — the trust model
