# Packaging a code plugin

A code plugin = `plugin.json` + `classes.dex`, zipped into one `.amaliaplug`.

---

## 1. Write the class

```kotlin
package com.example.hello

import com.my.amali.plugin.AmaliaPlugin
import com.my.amali.plugin.PhraseContributor
import com.my.amali.plugin.PluginCategory
import com.my.amali.plugin.PluginMetadata

class HelloPlugin : AmaliaPlugin, PhraseContributor {

    override val metadata = PluginMetadata(
        id = "com.example.phrases.hello",
        name = "Hello Pack",
        version = "1.0.0",
        author = "you",
        description = "Extra greetings",
        category = PluginCategory.PHRASES,
        iconName = "wave",
    )

    override fun greetingPhrases(languageTag: String): List<String> =
        if (languageTag.startsWith("ru")) listOf("Привет!") else listOf("Hello!")
}
```

Requirements:

- **No-argument constructor.**
- Implements `AmaliaPlugin` plus whatever contributions you need.
- `metadata.id` **must equal** the `id` in `plugin.json`.
- Keep constructors and `onLoaded()` cheap — they run on the main thread.
- Do heavy work in coroutines launched from `onLoaded(context)`, using
  `context.appContext` for lifecycle-independent work.

---

## 2. Compile to `classes.dex`

You need a JDK and the Android build tools (`d8`). Compile the sources against
the host contracts (see [`sdk/api/`](../sdk/api) for reference copies), then
convert the JVM bytecode to dex.

Example with Gradle producing a plain jar, then `d8`:

```bash
# 1. compile to a jar (any toolchain: Gradle, kotlinc, Maven)
./gradlew jar          # → build/libs/hello-plugin.jar

# 2. jar → dex
$ANDROID_HOME/build-tools/34.0.0/d8 \
    --min-api 26 \
    --output out/ \
    build/libs/hello-plugin.jar

# out/classes.dex is what you ship
```

Notes:

- `--min-api 26` matches Amalia's minimum supported Android version.
- Do **not** bundle the host classes (`com.my.amali.plugin.*`, Compose,
  `androidx.*`) into your dex — the host already has them. `d8` will warn; pass
  `--lib` pointing at `android.jar` and keep your jar limited to your own code.
- Only Kotlin/Java. Native `.so` libraries are not loaded from the package.
- If you use Kotlin, remember that without the Kotlin stdlib in the dex you must
  either rely on the host's stdlib or shade it in. Shading it in is safest.

---

## 3. Zip

```
my-plugin.amaliaplug
├── plugin.json     ← root level, mandatory
└── classes.dex     ← root level
```

```bash
zip -r hello-pack.amaliaplug plugin.json classes.dex
unzip -l hello-pack.amaliaplug      # both at the root?
```

---

## 4. Check before shipping

```bash
# structure
unzip -l hello-pack.amaliaplug

# manifest is valid JSON
python3 -c "import json;json.load(open('plugin.json'));print('ok')"

# checksum for the catalog
sha256sum hello-pack.amaliaplug
```

On the device, **Settings → Plugins** lists the plugin with its author and
version. If a code plugin fails to load, the app logs the reason and continues —
your plugin is skipped, not fatal.

---

## 5. Versioning and updates

- Bump `version` on every release. The store compares it against the installed
  copy and offers **Update** when the catalog version is higher.
- Users' enabled/disabled state is keyed by `id`, so updates keep their choice.
- Changing `id` is a **new plugin**: the old one stays installed side by side.
- Declare `minHostVersion` honestly. Raising it prevents installation on old
  hosts instead of crashing later.
