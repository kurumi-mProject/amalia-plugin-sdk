# Legacy SDK: archived structure and migration

> **Архив / только для миграции.** Не включайте этот файл в контекст модели при создании нового SDK3-плагина. Актуальные API, структура и команды находятся в README и SDK_CLI.md.

SDK3 is the author workflow over the existing signed protocol loader, not a third
loader, a stable 3.0 Maven release or an automatic conversion of legacy plugins.
The current protocol and runtime artifacts are **0.2.0-SNAPSHOT**. Use freshly
published artifacts and a compatible host. The standalone SDK contains the JVM
protocol/runtime, not the Android host application or its adapters.

Read [SDK_CLI.md](SDK_CLI.md), [SDK3_STATUS.md](SDK3_STATUS.md) and the
[root quick start](../README.md). Actual source contracts take precedence over
older documents and architecture plans.

## Old and new contracts

| Area | Legacy SDK | SDK3 / protocol runtime |
|---|---|---|
| Author API | Host-coupled `AmaliaPlugin`, `PluginMetadata`, `*Contributor` contracts in `sdk/api` | `org.amalia.platform.protocol.sdk.AmaliaPlugin`, `PluginManifest`, `AmaliaApi`; underlying `PluginEntrypoint` / `PluginContext` remain available |
| Source manifest | `plugin.json` | `amalia.json`; Gradle generates `GeneratedManifest` and CLI emits the wire manifest |
| Runtime package | ZIP with `plugin.json`, optional `classes.dex` | Signed ZIP with `platform.json`, `package.sig`, one `classes.dex` |
| Build | Reference contract copies / legacy Android-dependent examples | Independent Kotlin/JVM JAR, local Maven protocol artifact; D8 only for Android packaging |
| Tools | Contributor-specific host objects | Schema-described operations; facade contract `${id}.tools`, provider `${id}.local`, explicit runtime invocation grants |
| Lifecycle | Legacy host lifecycle callbacks | `install(api)` / optional `uninstall(api)` over protocol start/stop; registrations withdrawn before user cleanup |
| Capability declaration | Legacy Contributor and host permission system | Typed manifest declarations, signed review tokens, exact manifest approval; declaration alone never grants permission |
| UI | Host-coupled screens/widgets and legacy themes | Host-rendered declarative `UiDocument`; no general binary Compose UI or WebView |
| Development | Legacy examples and scripts | CLI `validate`, `test`, `dev` with CoreRuntime-backed JVM fake host |
| Trust | Legacy catalog/checksum behavior | P-256 signed manifest inventory and pinned publisher; still trusted in-process code |

The `.amaliaplug` extension does **not** identify which format is inside. Renaming
`plugin.json` to `platform.json`, adding a signature file to an old ZIP, or replacing
imports does not convert the contracts or make a legacy plugin SDK3-compatible.
SDK3 does not remove the existing protocol entrypoints or legacy loader.

## Practical porting sequence

### 1. Keep the legacy plugin working

Keep the old project/package and publisher records while developing the port.
Inventory its actual contributions, stored data, runtime dependencies, permission
needs and host assumptions. Not every old Contributor has a one-for-one SDK3
replacement. In particular, do not assume theme/engine/widget/long-running service
contributions map to a facade method. Check the current protocol source and host
support, not just the list of API names.

### 2. Publish or obtain matching SDK artifacts

From this standalone repository root, with **JDK 17 and Gradle 9.7.1**:

```bash
gradle -p platform \
  :protocol:publishSdkPublicationToSdkDistributionRepository \
  :runtime:publishSdkPublicationToSdkDistributionRepository
export AMALIA_SDK_REPOSITORY="$PWD/platform/build/sdk-repository"
```

The resulting directory is a Maven repository, not `mavenLocal()`. Copy the whole
repository if compiling elsewhere, and pass its absolute path through
`-PamaliaSdkRepository=...`. No public Maven server is assumed. The root
`./gradlew` and `gradle/wrapper` are included, so `./gradlew -p platform ...`
works here too. Generated author projects need installed `gradle` on PATH or their
own wrapper (copy the root `gradlew` and `gradle/` into the author project).
These are JVM builds, not APK builds.

Use `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT` for author compilation;
`org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT` provides the JVM fake host/dev
runtime. Do not invent `3.0.0` coordinates or substitute an older snapshot with
the same version string. JDK 17 is the build toolchain; the modules target JVM 11.

### 3. Create a new author project and port behavior

```bash
./tools/amalia create plugin weather
./tools/amalia add network weather --domain api.example.com
./tools/amalia validate weather
gradle -p weather jar -PamaliaSdkRepository="$AMALIA_SDK_REPOSITORY"
```

The generated project contains `amalia.json`, Gradle files and a plugin source
class. Set a real publisher and an ID within that publisher's namespace, and keep
`entryPoint` synchronized with the Kotlin package/class. Use `GeneratedManifest.value`;
do not vendor `sdk/api` or edit generated Kotlin / `platform.json`.

Move tool behavior into `api.tools.register` with supported parameter/output schemas,
timeouts and accurate effects/idempotency. The default effect is WRITE, which
requires host confirmation. Move package data through `api.storage`; plain storage
is not encrypted secrets. Design and verify data migration explicitly: reusing an
ID does not provide automatic legacy-state import, publisher trust continuity or
an installer migration UX.

Declare only capabilities actually needed. Supported CLI modules are `network`,
`media`, `background`, `notifications`, `browser`, `device`, `secrets`, `screen`;
only network takes `--domain`. Host approvals and Android dangerous permissions
remain separate from declarations. Network is exact-domain HTTPS GET, not a
general HTTP/OAuth client. For host UI and other low-level contracts consult
[SDK_GUIDE_RU.md](SDK_GUIDE_RU.md) and
[protocol source](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol).

### 4. Exercise behavior in the fake host

```bash
./tools/amalia dev examples/sdk3/notes \
  --config examples/sdk3/notes/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$AMALIA_SDK_REPOSITORY"
./tools/amalia test weather \
  --gradle-arg=-PamaliaSdkRepository="$AMALIA_SDK_REPOSITORY"
```

Use a config tailored to the port, with explicit storage/invocation/network/feature
grants and fixtures. The notes command above is a ready reference, not a test of
your weather project. Fake HTTP is fixture-only; storage is volatile and unencrypted;
schedules are manual. The console is trusted local JVM execution, **not** a sandbox,
Android simulation, device install or hot reload. Test runs do not create tests;
`NO-SOURCE` means no test coverage was exercised.

### 5. Package with the new toolchain

Install Android build-tools D8 and OpenSSL. Create a P-256 publisher private key,
store it securely outside the project/package, and export the public SPKI pin
for the host trust configuration. Then:

```bash
./tools/amalia package weather \
  --private-key /private/publisher.pem --key-id author-1 \
  --gradle-arg=-PamaliaSdkRepository="$AMALIA_SDK_REPOSITORY" \
  --output "$PWD/weather.amaliaplug"
```

Add `--d8 /absolute/path/d8` if needed. The CLI obtains Gradle classpaths, compiles
the JAR, uses shared protocol/Kotlin/kotlinx only as D8 classpath, includes supported
third-party JVM runtime JARs, checks duplicate/reserved classes and verifies signed
inventory before publishing. It does not overwrite existing output. Runtime JVM
libraries should be `implementation` dependencies, not merely `compileOnly`.
AARs, Android resources/assets, native payloads and multidex are rejected; arbitrary
legacy package resources cannot simply be copied into the signed package.

For an existing low-level protocol project,
[PackagePlatformPlugin.py](../tools/PackagePlatformPlugin.py) remains the explicit
single-DEX signed packager; see [independent-plugin](../examples/independent-plugin).
The author facade itself adds no loader or wire-format change to that workflow.

### 6. Import and validate on the target host

Use a host compatible with the snapshot/ABI. Provision the trusted publisher P-256
pin, import the signed package, obtain activation/capability review consent and
issue the required invocation grants separately. Signing and `key-id` do not
establish trust by themselves. The importer still verifies DEX/ABI compatibility.
Do not assume the old catalog/install/deep-link workflow automatically provisions
new-format trust or permissions.

Validate revoke/disable/cleanup, persistence, Android permission refusal and
feature-specific device behavior. The standalone JVM repository cannot prove
Android adapter acceptance; see [SDK3_STATUS.md](SDK3_STATUS.md) for remaining CI
and device checks. In-process DEX remains **trusted code**, without security,
CPU or memory isolation. Process-death recovery, video/HLS/live media, OAuth,
a full sandbox and all eight production integrations are not implemented.

## Retained compatibility paths

These paths remain intentionally available and are not the SDK3 authoring API:

- [`sdk/api/`](../sdk/api/): legacy reference contracts, including `minimal/`.
- [`tools/pack.sh`](../tools/pack.sh) and [`tools/validate.py`](../tools/validate.py):
  legacy `plugin.json` packaging/validation, not SDK3 signing/validation tools.
- Original top-level examples such as `phrases-localized`,
  `command-kotlin`, `tool-kotlin`, `reminder-kotlin`, `notification-kotlin`, and
  legacy catalog data. SDK3 projects live under [`examples/sdk3`](../examples/sdk3).
- Existing legacy guides: `quickstart.md`, `manifest.md`, `packaging.md`,
  `extension-points.md`, `themes.md`, `capabilities.md`, `catalog.md`, `security.md`,
  plus `PLUGIN_API.md`, `PLUGIN_SDK.md` and `EXTENSION_SDK.md` for older/host-specific
  contracts. Their package-format and publication claims must not be applied to SDK3.

No full automatic legacy migration is claimed. Preserve compatibility deliberately
and document any contribution or data that cannot yet be ported.

## Архивная справка по совместимости

В проекте также сохранён legacy API `com.my.amali.plugin`. Это **другой SDK и другой формат**, даже если расширение архива такое же.

| | Новый SDK | Legacy SDK |
|---|---|---|
| API | `org.amalia.platform.protocol` | `com.my.amali.plugin` |
| Entry point | `PluginEntrypoint` | `AmaliaPlugin` |
| Manifest | `platform.json` | `plugin.json` |
| Lifecycle | suspend `start/stop` | `onLoaded/onEnabled/onDisabled/onUnloaded` |
| Вклады | Contracts/providers/streams | Contributor interfaces |
| Доступ к Android Context | Не в публичном context | `PluginContext.appContext` |
| Подпись executable package | Pinned P-256 обязательна | Новый механизм подписей здесь не используется |

Legacy `ExtensionPoints.kt` описывает темы, движки, tools, phrases, prompt, commands, screens, widgets, notifications, background, audio session, events, scenarios и capability contributions. Для новых тем/фраз/сценариев старый `plugin.json` всё ещё имеет значение; новый protocol API не экспортирует все эти contributors один к одному.

Старые копии контрактов находятся в `sdk/api/`; они не заменяют актуальный `platform/protocol`.

Legacy install compatibility проверяется по `PluginMetadata.HOST_VERSION`, сейчас это **`2.0.0`**. Поэтому `minHostVersion: "3.0.0"` из части v3-примеров будет отклонён этим установщиком. Название папки `amalia-3.0` не меняет константу.

Архив только с `platform.json` не подходит legacy installer; архив только с `plugin.json` не подходит platform package verifier. Выбирайте API до начала разработки и не смешивайте entrypoint interfaces/manifest names.


## Исходник старых Contributor-интерфейсов

[ExtensionPoints.kt](https://github.com/kurumi-mProject/amalia-private/blob/308463312411e7733bcad23206e3b10601d528e5/app/src/main/java/com/my/amali/plugin/ExtensionPoints.kt)
