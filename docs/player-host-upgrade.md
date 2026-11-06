> **Legacy SDK (v2).** This document describes `plugin.json` / `com.my.amali` contracts, not the SDK3 author API. For new plugins start with [SDK_CLI.md](SDK_CLI.md), [SDK3_STATUS.md](SDK3_STATUS.md) and [migration](MIGRATION_V3.md).

# Host API 2.1 upgrade for external screens and background work

The full local player is still an external `.amaliaplug`. Host changes are generic:

- `ScreenSpec.title` allows plugin-owned localized labels without merging Android resources.
- `BackgroundState.Active.foregroundType` declares DATA_SYNC or MEDIA_PLAYBACK.
- `HostServices.ensureForeground(pluginId)` waits for confirmed foreground ownership.
- Host R8 preserves the exposed UI facades, lifecycle and activity-result ABI.
- Notification receivers hold their pending result while dispatch runs.
- Installation dispatches lifecycle registration to Main.

Update `/root/amalia-3.0` through its normal APK CI. Do not try this player on the old
host: its manifest requires plugin API 2.1.0 and installation must reject older hosts.
The `PluginMetadata.HOST_VERSION` marker is the plugin API version, distinct from
Android `versionName`.

## Binary compatibility

Adding fields to Kotlin data classes changes constructor/copy JVM signatures.
Existing independently compiled plugins that use ScreenSpec or BackgroundState.Active
must be rebuilt against this SDK before being used with API 2.1.0. Updating only their
manifest version does not fix binary compatibility. Source defaults keep ordinary
source calls compatible but do not restore already compiled bytecode.

The player uses only the reviewed minimal + player-ui contracts. Other pre-existing
full SDK declarations (including WidgetSpec) are not covered by this player build;
compare their signatures with the host before publishing unrelated widget plugins.

## Required device verification

No host APK was built in this workspace. Before publishing the host/plugin pair:

1. Build/install a release APK through GitHub Actions.
2. Install music-player 2.0.0 and grant audio access on its screen.
3. Check denial/settings return, search, playback, seek, shuffle/repeat, playlist edits.
4. Check notification controls, screen-off playback and audio focus/headphone unplug.
5. Check speech ducking while a song is preparing and switching songs during speech.
6. Check AI discovery/search/playlist creation with real returned track IDs.
7. Check disable/uninstall stops playback and re-enable creates a fresh engine.

The shared service retains existing DATA_SYNC behavior for other plugins/helper work.
Android 15 dataSync quotas and process-death recovery require separate device testing;
mediaPlayback work itself is explicitly declared separately from dataSync.
