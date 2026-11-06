# SDK3 implementation and acceptance ledger

SDK3 provides the author facade over the protocol runtime.
No local Android/APK build is permitted. New Android code needs CI compilation and device acceptance.

| Requirement | Actual implementation / boundary |
|---|---|
| One public author workflow | `tools/amalia`, `amalia.json`, generated Kotlin manifest, existing signed platform.json wire format |
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
| UI | pure-JVM declarative UiDocument adapter reuses Amalia renderer/theme/nav and lifecycle withdrawal; not arbitrary Compose binary UI or WebView |
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
