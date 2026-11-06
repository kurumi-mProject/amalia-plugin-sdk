# `sdk/api` — LEGACY reference contracts

> **Not the SDK3 API.** This directory is retained for compatibility with existing
> `plugin.json` / Contributor plugins. New projects should start with the
> [SDK3 quick start](../../README.md), [CLI guide](../../docs/SDK_CLI.md) and
> [migration guide](../../docs/MIGRATION_V3.md).

The current independent API is in
[`platform/protocol/src/main/kotlin/org/amalia/platform/protocol`](../../platform/protocol/src/main/kotlin/org/amalia/platform/protocol),
with the author facade in its `sdk` package. Its current Maven coordinate is
`org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`, published to a local
Maven repository from this checkout. No stable SDK 3.0 Maven release or public
Maven service is implied. Do not copy these legacy files into an SDK3 project.

## What remains here

| File | Legacy contents |
|---|---|
| `AmaliaPlugin.kt` | `AmaliaPlugin`, `PluginContext`, `HostServices` |
| `PluginMetadata.kt` | Plugin identity, name, version and other legacy metadata |
| `PluginCategory.kt` | Legacy category enum |
| `ExtensionPoints.kt` | Contributor interfaces and their data types |
| `minimal/` | Reduced non-Compose contracts for commands, notifications, background and capabilities |

These are reference copies of host contracts, not the independent protocol ABI.
The matching legacy host source is authoritative. The reduced
`minimal/Contributions.kt` is generated from `ExtensionPoints.kt` by
`tools/extract-minimal-contracts.py`; Android-dependent types still require an
appropriate `android.jar` and Kotlin dependencies. Full contribution interfaces
can refer to host types not included in this directory.

Existing legacy projects may keep their source-set configuration or vendored
copies while migrating. Do not bundle host-owned contract classes into plugin
DEX: duplicate definitions cause loading/type-identity problems. Keeping this
folder does not make its contracts interchangeable with the new protocol API.

## Which packaging tools to use

- Legacy `plugin.json` examples: `tools/pack.sh` and `tools/validate.py` are retained
  for that format.
- SDK3 `amalia.json` author projects: use `tools/amalia validate|package`; output
  is the signed `platform.json` + `package.sig` + `classes.dex` format.
- Existing low-level protocol projects: see
  [independent-plugin](../../examples/independent-plugin) and
  `tools/PackagePlatformPlugin.py`.

The formats share `.amaliaplug` but are not interchangeable. Signed SDK3 packages
still execute trusted code in the host process, not in a sandbox. Consult the
[status ledger](../../docs/SDK3_STATUS.md) for actual coverage and acceptance limits.
