# Declarative themes

A theme is a set of **palettes**. A palette is a vertical gradient, a few
drifting aurora colours, a decorative motif, and the colour temperature it
represents. The whole UI — accents, shadows, text — is derived from one palette,
so you do not specify interface colours: you specify light.

No code required.

---

## The shape of a theme

```json
{
  "themes": [
    {
      "id": "com.example.theme.sunset.dusk",
      "name": "Sunset Dusk",
      "forceDark": true,
      "preview":        { "…PaletteSpec…": "" },
      "staticDark":     { "…PaletteSpec…": "" },
      "staticLight":    { "…PaletteSpec…": "" },
      "ladder": [
        { "cct": 2400, "palette": { "…PaletteSpec…": "" } },
        { "cct": 5600, "palette": { "…PaletteSpec…": "" } }
      ]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | Unique theme id (reverse-domain). **Winner takes it**: the first plugin to declare an id wins; a later one is skipped, not merged. |
| `name` | Name in the appearance gallery. |
| `forceDark` | The theme is dark even if the system is in light mode. |
| `preview` | Palette for the gallery card. If omitted, the first ladder entry is used. |
| `staticDark` / `staticLight` | Palettes used when the user pins a fixed mode instead of the circadian one. |
| `ladder` | The circadian ladder: palettes sorted by `cct` (cold → warm) and applied by the time of day. |

You may provide only `preview` (a plain theme), only `ladder` (a fully adaptive
one), or both (`preview` for the card, `ladder` for behaviour).

---

## `PaletteSpec`

All colours are strings: `#RRGGBB` or `#AARRGGBB`.

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

| Field | Type | Meaning |
|---|---|---|
| `name` | string | Internal name of the palette. |
| `stops` | array | The background gradient, top to bottom. `position` is `0..1`. |
| `isDark` | bool | Whether the palette is dark. |
| `cct` | int | Colour temperature in Kelvin (e.g. `2600` warm, `5600` neutral). |
| `melanopicDer` | float `0..1` | How "daylight-like" the palette feels — drives contrast and accent behaviour. Higher = brighter, more activating. |
| `auroras` | string[] | Colours of the drifting light blobs. **The first one becomes the accent colour of the entire UI.** |
| `motif` | string | Decorative layer: `OFF` `AUTO` `SAKURA` `MAPLE` `FIREFLY` `SNOW` `STARS`. |
| `motifAccent` / `motifAccentAlt` | string | Colours for the motif particles. |
| `shadow` | string | Base shadow colour (alpha matters). Default `#75000000`. |
| `sheen` | float `0..1` | Strength of the glass highlight. Subtle: `0.08`–`0.13`. |

### Design guidance

The palette carries almost all the identity — typography and shapes are
inherited on purpose (fonts without metric tuning break layout more often than
they improve it). So:

- **Two or three `auroras`** are enough. More than four turns the background
  into noise.
- Keep `stops` dark-to-dark for `isDark` themes; a mid-stop that is *lighter*
  than both ends reads as a hole.
- `sheen` above `0.15` makes glass look like plastic.
- Match `cct` to the mood: `2200–2900` night, `3800–4600` morning/evening,
  `5200–6500` day.

---

## The circadian ladder

Amalia adapts the theme to the time of day. The ladder is your theme's answer to
"what does this look like at 7am and at midnight".

```json
"ladder": [
  { "cct": 2400, "palette": { "motif": "STARS",   "auroras": ["#FF2E88", "#7C4DFF"] } },
  { "cct": 4200, "palette": { "motif": "FIREFLY", "auroras": ["#FF6EB0", "#00E5FF"] } },
  { "cct": 5600, "palette": { "motif": "SAKURA",  "auroras": ["#00E5FF", "#FF2E88"] } }
]
```

- Entries are sorted by `cct` automatically; write them in any order.
- The host interpolates between the two nearest entries of the current time.
- Two entries is a valid ladder. One is a static theme.

See [`examples/theme-neon-glass/plugin.json`](../examples/theme-neon-glass/plugin.json)
for a complete three-step ladder.

---

## Themes plus phrases

A theme plugin usually also brings greetings — same manifest, second block:

```json
{
  "category": "theme",
  "themes":  [ /* … */ ],
  "phrases": { "en": ["Neon is on."], "ru": ["Неон зажёгся."] }
}
```
