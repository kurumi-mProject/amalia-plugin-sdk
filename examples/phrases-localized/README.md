# Example: localized phrases only

The smallest useful plugin: no code, no themes — just extra greetings in nine
languages.

```
phrases-localized/plugin.json
```

## What it shows

- `category: "phrases"` so it is grouped with greeting packs.
- `phrases` keyed by language tag; the host looks up the exact tag (`en-US`)
  first, then the prefix (`en`).
- An empty list for a language means "nothing to add here" — the built-in
  greetings keep working. No error, no fallback text to maintain.

## Build it

```bash
cd phrases-localized
zip -r ../phrase-pack.amaliaplug plugin.json
```

## Install it

**Settings → Plugins → Install from file**, or a deep link if you publish it
with a catalog entry:

```
https://mirabel.tech/plugin?id=com.mirabel.phrases.pack
```

## Try changing it

- Add `"it": ["Ciao, sono qui."]` — a locale Amalia doesn't ship. It simply
  never matches on a device in Italian... unless the device language is Italian,
  in which case your phrase is the only one shown. Both are valid outcomes;
  translating into the nine supported locales is where the value is.
- Add a phrase that is *too long* to be a greeting and listen to it out loud.
  This is the fastest way to learn the right length: one short sentence.
