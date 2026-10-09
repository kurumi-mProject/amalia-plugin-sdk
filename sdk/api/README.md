# `sdk/api` — reference copies of the host contracts

These files are **verbatim copies** of the plugin-facing contracts that live in
the Amalia host app:

| File | Contains |
|---|---|
| `AmaliaPlugin.kt` | `AmaliaPlugin`, `PluginContext`, `HostServices` |
| `PluginMetadata.kt` | `PluginMetadata` (`id`, `name`, `version`, `iconName`, …) |
| `PluginCategory.kt` | `PluginCategory` enum |
| `ExtensionPoints.kt` | every contribution interface and its data types |

And a trimmed set for plugins that need **no UI**:

| File | Contains |
|---|---|
| `minimal/` | `AmaliaPlugin`, `PluginMetadata`, `PluginCategory`, plus commands, notifications, background and capabilities — without Compose |

`minimal/Contributions.kt` is generated verbatim from `ExtensionPoints.kt` by
`tools/extract-minimal-contracts.py`, so it cannot drift by hand. Use it when
your plugin has no screens or widgets: it compiles with nothing but `android.jar`
and the Kotlin stdlib, which means CI does not need an Android toolchain.

## Why copies, and not a dependency

There is **no published Gradle artifact yet** — the contracts currently live
inside the host application module. This folder exists so you can compile a
plugin today instead of waiting for the `:plugin-api` artifact.

How to use them:

```kotlin
// add the folder to your plugin project's source sets
sourceSets["main"].kotlin.srcDir("../amalia-plugin-sdk/sdk/api")
```

…or simply copy the files into your project and keep them under version control
until an artifact exists.

## Important caveats

- These are reference copies. The **authoritative** source is the host
  application; if they ever disagree, the host wins.
- `ExtensionPoints.kt` refers to a few host types that are *not* included here
  (`AmaliaTool`, `EngineSlot`, `AmaliaThemeDefinition`, `PromptBuilder`
  dependencies). You only need those for the contributions you actually
  implement — a `CommandContributor` plugin, for example, compiles with just
  these four files plus nothing else.
- Do not bundle these classes inside your `classes.dex`. The host already has
  them; bundling them causes duplicate-class and cast errors at load time.

## Planned

A standalone `:plugin-api` artifact (Maven / GitHub Packages) so that a plugin
is a normal Gradle dependency:

```kotlin
dependencies {
    compileOnly("tech.mirabel:amalia-plugin-api:2.0.0")
}
```

When that lands, this folder becomes a changelog of the API surface rather than
something you have to vendor.
