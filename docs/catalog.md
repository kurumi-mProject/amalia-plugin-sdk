# The store: catalog protocol and publishing

The Amalia store is not a server we own — it is a **JSON file at a URL**. Host it
anywhere: your site, GitHub Pages, a static bucket, a gist. Amalia fetches it,
renders the list, downloads packages and verifies checksums.

The default catalog of the project lives at:

```
https://mirabel.tech/plugins/catalog.json
```

Users can point the app at any other catalog in
**Settings → Plugins → Store → Custom source**.

---

## Catalog format

```json
{
  "items": [
    {
      "id": "com.example.theme.sunset",
      "name": "Sunset",
      "version": "1.0.0",
      "author": "you",
      "category": "theme",
      "description": "Warm dusk glass",
      "iconName": "palette",
      "downloadUrl": "https://example.com/plugins/sunset.amaliaplug",
      "sha256": "9f2c…",
      "sizeBytes": 3072
    }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `id` | **yes** | Must match the plugin's own `id`. Used for "installed" detection and updates. |
| `name` | **yes** | Shown in the store list. |
| `version` | **yes** | Compared with the installed version → **Update** appears when higher. |
| `author` | no | Shown on the card. |
| `category` | no | Same values as the manifest; groups the list. |
| `description` | no | One line under the name. |
| `iconName` | no | Host vector icon, same set as the manifest. |
| `downloadUrl` | **yes** | Direct link to the `.amaliaplug`. Must be a plain file URL, not an HTML page. |
| `sha256` | recommended | Hex digest of the package. A mismatch **aborts** the install. |
| `sizeBytes` | no | Used for the progress bar in the install dialog. |

Unknown fields are ignored — you can add your own metadata for a website build.

---

## Installing from a catalog

1. Amalia downloads the catalog (10 s connect / 20 s read / 60 s call timeouts).
2. The list is grouped by category. Installed entries show **Installed**;
   entries with a newer catalog version show **Update**.
3. Tapping an entry opens the glass dialog with author, version and size.
4. Install runs in three reported phases: **downloading** (with percentage when
   the server sends `Content-Length`) → **verifying** → **installing**.

If the catalog is empty or unreachable, the screen says so in human language and
offers a retry — it never shows a spinner forever.

---

## Deep links

Share a plugin with a URL:

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

The app opens, looks the `id` up in its catalog (downloading it if necessary),
and shows the same confirmation dialog. Also works with the `amalia://` scheme:

```
amalia://plugin?id=com.example.theme.sunset
```

For `https://mirabel.tech/...` links to open the app without a chooser, that
domain must be verified by the host app via `assetlinks.json` — that part is
already configured in the app.

---

## Publishing checklist

- [ ] `plugin.json` at the root of the archive.
- [ ] Archive named `<something>.amaliaplug`.
- [ ] `sha256` matches the published file exactly (`sha256sum sunset.amaliaplug`).
- [ ] `downloadUrl` returns the file itself, with a sensible `Content-Type`
      (`application/zip` or `application/octet-stream`) — not an HTML landing page.
- [ ] `version` bumped since the last release.
- [ ] `minHostVersion` set honestly.
- [ ] The catalog file is UTF-8 and served without a BOM.
- [ ] `downloadUrl` is HTTPS.

---

## Hosting your own catalog

Any static hosting works.

```bash
# build a catalog from a folder of packages
python3 - <<'PY'
import json, hashlib, pathlib
items = []
for f in sorted(pathlib.Path("plugins").glob("*.amaliaplug")):
    data = f.read_bytes()
    items.append({
        "id": "com.example." + f.stem,
        "name": f.stem.replace("-", " ").title(),
        "version": "1.0.0",
        "downloadUrl": f"https://example.com/plugins/{f.name}",
        "sha256": hashlib.sha256(data).hexdigest(),
        "sizeBytes": len(data),
    })
pathlib.Path("catalog.json").write_text(json.dumps({"items": items}, indent=2))
PY
```

A complete ready-to-edit example is in
[`examples/store-catalog.json`](../examples/store-catalog.json).

---

## Version comparison

Simple semver segment comparison: `1.2.0` vs `1.10.0` is compared numerically,
so `1.10.0` is newer (the common string-comparison bug is avoided). If either
version is not parseable as numbers, any difference counts as an update —
better to offer an update once too often than to miss one.
