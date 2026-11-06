# SDK facade: первый срез 3.x

Это новый authoring API поверх существующего protocol runtime, **не третий загрузчик**.
Артефакт пока остаётся `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`:
релиз стабильного SDK 3.x и новая ABI-линия ещё не объявлены. Нужен свежий SDK из этого checkout.

## Создать проект

Из корня репозитория:

```bash
./tools/amalia create plugin weather
./tools/amalia add network weather --domain api.example.com
./tools/amalia validate weather
```

`amalia.json` — единственный авторский manifest. Gradle генерирует из него
`GeneratedManifest`, включая типизированный `Capability.Network`.
`amalia add media|background|notifications|browser|device|secrets|screen` добавляет запрос
host capabilities. Он переводится в типизированные Kotlin capabilities и подписанные review tokens.
Это не runtime grant: пользователь отдельно подтверждает capabilities при установке.
Аргумент `--domain` допустим только у network. Не редактируйте
сгенерированный Kotlin или `platform.json`. Проект — Kotlin/JVM, без Android SDK
для JAR-компиляции; нужен Gradle 9.7.1 / JDK 17. Публичный Maven server не предполагается.

Публикация локального Maven-репозитория без APK:

```bash
./gradlew -p platform :protocol:publishSdkPublicationToSdkDistributionRepository :runtime:publishSdkPublicationToSdkDistributionRepository
```

Скопируйте `platform/build/sdk-repository` разработчику либо разместите как Maven repository.
Путь передаётся через `-PamaliaSdkRepository=/absolute/path/sdk-repository`.

## API

```kotlin
class Plugin : AmaliaPlugin() {
    override val manifest = GeneratedManifest.value
    override suspend fun install(api: AmaliaApi) {
        api.tools.register("remember", "Save a note") {
            string("text", description = "Note text")
            execute {
                api.storage.putString("note", string("text"))
                JsonPrimitive("Saved")
            }
        }
    }
}
```

Импорты: `org.amalia.platform.protocol.sdk.*`, `amalia.generated.GeneratedManifest`,
`kotlinx.serialization.json.JsonPrimitive`. Contract ID — `${id}.tools`, provider —
`${id}.local`, версия contract равна версии manifest. Регистрации публикуются после
`install`; runtime сохраняет свои проверки схем/identity/grants. DSL поддерживает
типизированные примитивные аргументы, output schema, timeout, effect/idempotency; effect по умолчанию WRITE.
В Android-хосте tools подключены к AI-каталогу; WRITE требует одноразового подтверждения.
Декларация tool сама по себе не выдаёт invocation grants.
Регистрации отзываются до `uninstall`, cleanup пытается закрыть все регистрации и сохраняет ошибки.

Storage — существующие bounded data/settings namespaces с installer identity,
миграциями и generation fencing, а не SharedPreferences плагина.
`getString`, `putString`, `delete` работают с `data`. Обычный storage не шифруется этим фасадом; секреты сохраняйте отдельно через
`api.secrets` с capability `secrets` (Android-хост использует Keystore AES-GCM).

Сеть: `api.network.get(url, headers)` возвращает status, headers и bounded bytes.
Фасад допускает только объявленные точные HTTPS-домены, порт 443, без userinfo/fragment.
Android transport принадлежит хосту: общий OkHttp, 30s call timeout, response ≤2 MiB,
без redirects, cookies, automatic retry и логирования секретов. Поколение и отмена
проверяются runtime; host approval проверяется до запроса и перед выдачей ответа.

**Декларация не выдаёт разрешение.** Хост проверяет identity, текущее поколение,
подписанный manifest digest и сохранённое явное согласие пользователя из installer review.
Дополнительная доверенная политика `packageNetworkApproval` по умолчанию deny.

## Упаковка

```bash
./tools/amalia package weather \
  --private-key /private/publisher.pem --key-id author-1 \
  --gradle-arg=-PamaliaSdkRepository=/absolute/path/sdk-repository
```

При необходимости `--d8 /absolute/path/d8`, `--output /path/weather.amaliaplug`.
D8 выбирается из PATH или Android SDK build-tools. CLI получает реальные compile/runtime
classpath от Gradle, передаёт Kotlin/kotlinx/protocol только как D8 classpath, включает
сторонние JVM JAR, проверяет duplicate/reserved definitions и отклоняет AAR, resources,
native payloads, multidex. Одного compileOnly для библиотек, нужных плагину в runtime,
недостаточно — обычные JVM зависимости объявляйте `implementation`.

Выходной файл публикуется только после проверки inventory и подписи; существующий файл
не перезаписывается. Проверка DEX compatibility всё ещё принадлежит host importer.

Пакет использует существующие `platform.json`, `package.sig`, `classes.dex`.
Подписываются точные bytes manifest и inventory. Создание publisher P-256 key,
передача public pin, trust, activation consent и invocation grants — отдельные действия.
Совместимость библиотек с Android не доказывается проверкой имён классов.
`validate` — статическая проверка, не доказательство установки/безопасности.

## JVM fake host

`org.amalia.platform:amalia-runtime` содержит `org.amalia.platform.runtime.testing`:
`FakePluginHost`, bounded `FakePluginStorage`, fixture-only `FakeNetworkAccess`.
Хост использует настоящий CoreRuntime (schema/grants/lifecycle), а не всегда успешный mock.

```kotlin
val host = FakePluginHost()
host.grantStorage(plugin.manifest.id)
val activation = host.activate(plugin)
host.grant(RuntimeGrant(
    id = "manual", caller = host.caller,
    contractId = "${plugin.manifest.id}.tools",
    providerId = "${plugin.manifest.id}.local",
    expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
))
val response = host.invoke(plugin, "remember", buildJsonObject { put("text", "hello") })
activation.stop()
host.close()
```

Это suspend-код; импорты RuntimeGrant — `org.amalia.platform.runtime`, JSON — kotlinx.
Сеть требует отдельно `grantNetwork(packageId, domain)` и fixture
`networkFor(packageId).respond(url, NetworkResponse(...))`. Реального HTTP fake не делает.
`stopAll` вызывает cleanup; `close` только отзывает authority, как CoreRuntime.close.

### Интерактивный JVM-стенд

```bash
./tools/amalia dev examples/sdk3/notes \
  --config examples/sdk3/notes/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$PWD/platform/build/sdk-repository"
```

CLI временно генерирует Kotlin main и отдельный Gradle source set, запускает JavaExec
с `FakePluginHost` и настоящим CoreRuntime. Dev-код не попадает в author JAR/DEX.
Генерируемый source set требует **runtime** Maven artifact того же snapshot, что protocol.
Это запуск доверенного локального кода, **не sandbox**. Нет подключения к Android,
установки на устройство, real HTTP, watch/hot reload или provisioning Android permissions.
По умолчанию все grants выключены; установка плагина, использующего storage в install,
требует заранее включить `grantStorage` в config. Изменения Kotlin требуют перезапуска dev.

Команды консоли:

```text
manifest
permissions
tools
grant storage
grant invoke
invoke save {"name":"shopping","text":"Buy milk"}
invoke read {"name":"shopping"}
storage note.shopping
revoke storage
fixtures
fixture {"url":"https://api.example.com/status","status":200,"body":"ok"}
grant network api.example.com
revoke network api.example.com
quit
```

`tools` выводит реально зарегистрированные операции с input/output JSON schemas и effect.
`invoke` проходит схемы, runtime grants, budget и lifecycle проверки, возвращает сериализованный
`InvocationResponse`, включая protocol errors. `grant invoke` выдаёт/обновляет session grant
на один час; `permissions` показывает конфигурацию lease, а runtime сам проверяет истечение.
Доступ `storage KEY` читает реальное хранилище текущего activation и уважает revoke.
KV перечисление SDK не предоставляет: известный ключ передаётся явно. `fixtures` показывает
именно fake ответы, не результаты HTTP; установка fixture не выдаёт сетевой grant.
`manifest`, `storage` и `fixtures` намеренно показывают содержимое — не используйте реальные
секреты и не публикуйте console logs с приватными данными. Обычные ошибки команды скрывают
exception message, чтобы URL/токены не утекали через него.

Опциональный `--config` — JSON следующей формы (неизвестные поля отклоняются):

```json
{
  "grantStorage": true,
  "grantInvoke": true,
  "networkGrants": ["api.example.com"],
  "storage": {"note.shopping": "Buy milk"},
  "fixtures": [
    {"url": "https://api.example.com/status", "status": 200,
     "headers": {"content-type": ["text/plain"]}, "body": "ok"}
  ]
}
```

Значения storage — строки; body — UTF-8 строка. Отдельные ограничения fake host сохраняются:
64 fixtures, response ≤1 MiB, headers ≤16 KiB; storage ≤256 записей, key ≤256 bytes,
value ≤64 KiB и общий лимит 1 MiB. Данные живут только в текущем процессе.
Объявление точного домена в `amalia.json`, explicit fake network grant и fixture для точного
URL требуются независимо друг от друга. Console shutdown вызывает plugin stop, затем close.

`amalia test` компилирует main (`classes`) и запускает Gradle `test`, но не генерирует тесты
и не считает `NO-SOURCE` покрытием. По умолчанию новый проект не содержит тестов.

### Восемь reference plugins

[`examples/sdk3`](../examples/sdk3/README.md) содержит standalone author projects:
weather (bounded GET/forecast), notes (save/read/delete), telegram (message draft),
reminders (host scheduling), music (host playback), smarthome
(brightness intent), local-ai (bounded inference request), screen (host UiDocument).
У каждого есть конкретный `dev.json`, `commands.txt` и README с запуском.
Weather делает bounded GET, notes использует package storage, music/reminders/screen используют
реальные host facades. Telegram/smarthome/local-ai остаются явными unexecuted intents,
а не вымышленными успешными интеграциями.

CI в `platform.yml` публикует SDK, создаёт проект CLI вне checkout, компилирует/упаковывает
его и все восемь references, запускает JVM-console сценарии. Подписывающий ключ CI эфемерный,
не publisher identity для production. D8 создаёт plugin DEX, **не APK**.

## Текущее покрытие и ограничения

Android-host adapters теперь подключены: media, notifications, encrypted secrets, short background,
JobScheduler, browser/device и declarative UI. Capabilities включены в подписанный manifest и
показываются перед activation; tools видны AI-каталогу, WRITE запрашивает одноразовое подтверждение.
CLI модули не дают grants автоматически. `dev.json.featureGrants` явно задаёт fake-host grants.

Точная матрица реализованного и оставшегося: [SDK3_STATUS.md](SDK3_STATUS.md).
Нет process-death восстановления, потокового видео/HLS/live audio, OAuth, полноценного sandbox,
всех восьми production integrations. Не считать их готовыми.
