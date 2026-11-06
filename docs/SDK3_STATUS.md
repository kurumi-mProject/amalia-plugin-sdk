# SDK3 implementation and acceptance ledger

This change adds a modern author facade to the existing protocol loader; it does not remove legacy binaries.
No local Android/APK build is permitted. New Android code needs CI compilation and device acceptance.

| Requirement | Actual implementation / boundary |
|---|---|
| One public author workflow | `tools/amalia`, `amalia.json`, generated Kotlin manifest, existing signed platform.json wire format; legacy loader preserved for installed packages, not yet fully migrated |
| AmaliaApi | tools/commands/events/storage/network/media/mediaProviders/notifications/secrets/background/scheduler/browser/device/ui; protocol remains the low-level ABI |
| Tools | typed primitive parameters, input/output schema, timeout/cancellation, effect/idempotency; dynamic AI catalog; exact generation fence and temporary exact grants; WRITE needs host dialog |
| Storage | existing bounded package data/settings, migrations/snapshots; typed serializer dataStore convenience; full export/import/uninstall UX is not added |
| Network | shared host OkHttp, exact reviewed HTTPS domains, bounded responses, no automatic redirects/retry or secret logs; no new POST/OAuth integration client |
| Capabilities | signed bounded tokens, immutable review, unchecked acknowledgements and exact manifest-digest persistence; Android dangerous permission requests remain existing app UX, refusal propagates |
| Media | Android MediaPlayer/MediaSession/FGS, notification actions, focus/duck/resume, unplug, queue/repeat/shuffle/state/wake mode; bounded cached direct MP3/Ogg/FLAC/WAV only, no video/HLS/live streams/DRM/process queue restore |
| Background | short cooperative operation dispatch, 30s bound, activation cancellation; no arbitrary long-running FGS jobs |
| Scheduler | Android persisted inexact JobScheduler operation reference, explicit owner/generation, no automatic retry; current resolver only serves live generation, process-death delivery is dropped, NOT restored |
| Notifications/secrets | plugin namespaces and bounded notices; Keystore AES-GCM per package secrets, no plaintext fallback; no OAuth flow or uninstall key purge UI |
| Browser/device | HTTPS external browser requires fresh host confirmation; battery/network/media volume/vibrate subset; no embedded WebView, location/Bluetooth/sensors adapters |
| UI | Portable UiDocument uses host renderer; native mode additionally supports real Compose ScreenContributor with owner-aware withdrawal |
| CLI | create/add/validate/package/test/dev; actual JVM fake console, D8 inputs/class conflicts/resources/signature verification; JAR-only, AAR/resources/native/multidex rejected |
| Fake host | CoreRuntime-backed tools; fake network/storage/media/features/UI records, manual schedules; no Android simulation or sandbox |
| Reference plugins | weather and notes functional; media/reminder/screen use new facades; Telegram is a draft, smart home and local AI remain explicit unexecuted intents, not production integrations |
| Security | P-256 signed inventory, pinned publisher, exact review/grants/generation; in-process DEX is TRUSTED CODE, no CPU/RAM/security isolation and no claim of 90–95% coverage |

## Remaining acceptance

CI must compile all changed Android sources and generated author projects and exercise CLI package/dev.
Device checks must cover foreground-service eligibility, Android permission refusal, Bluetooth/headphones,
lock-screen controls, focus interaction with assistant speech, disable during playback/download, revocation,
Keystore corruption, screen withdrawal and scheduled delivery after process death.

A true sandbox, process recovery, streaming/video playback, OAuth/connectors and eight fully functional
production adapters are separate substantive implementations; API presence does not prove them.


## Native V3 extension update

Entry point: [PLUGINS.md](PLUGINS.md). NativePluginEntrypoint bridges existing
Contributors into V3 signed android-dex activation with explicit android.native review.
Themes support nativeMotif drawing through the existing MotifLayer in AUTO. Native
engines execute as role providers with explicit settings selection. Commands, events,
widgets, scenarios, capability definitions and Compose screens use host consumers.
Withdrawal checks owner identity; queued events include a host generation.

File, catalog and deep-link imports now route platform.json to staging/review; no
auto-approval. Catalog ID/version are checked against verified metadata. Store digest
is not publisher trust. Native contribution toggle and package activation remain separate.

Export/author tooling: exportNativeSdk, native_plugin.py create/validate/package,
BuildNativeAuthoringPack.py, Native SDK distribution workflow. Exact host classes,
resolved compile-only dependencies, contract sources and checksums are exported.
No claim of stable native ABI or arbitrary resource/native library support.

### Acceptance still required

Android compilation, real SDK export, independent generated Compose compilation/D8,
and device lifecycle acceptance must pass in CI/on device. Static source review and
Python fixture checks are not substitutes. The platform protocol/runtime JARs have
compiled successfully; subsequent low-memory local repetitions were inconclusive.
No APK was built locally. Do not label the complete native integration release-verified
until the native-sdk and Android build workflows succeed.
