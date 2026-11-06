# Amalia SDK: подробное руководство разработчика

Документ описывает SDK из текущих исходников Amalia в `platform/protocol` и его исполнение в Android-хосте. Дата сверки: 5 ноября 2026 года. Базовый commit: `6f98cdd`; раздел UI включает текущие изменения рабочего дерева и требует соответствующей версии хоста.

Основной SDK для нового исполняемого плагина — **`org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`**. Плагин пишется на Kotlin/JVM, компилируется в JAR, затем преобразуется Android D8 в один `classes.dex` и поставляется в подписанном ZIP. Исходники приложения для компиляции плагина не нужны, если получен опубликованный SDK Maven repository.

## Содержание

1. [Что входит в SDK](#1-что-входит-в-sdk)
2. [Языки и инструменты](#2-языки-и-инструменты)
3. [Основные понятия](#3-основные-понятия)
4. [Создание проекта](#4-создание-проекта)
5. [Полный пример плагина](#5-полный-пример-плагина)
6. [Контракты и JSON Schema](#6-контракты-и-json-schema)
7. [Вызовы других плагинов](#7-вызовы-других-плагинов)
8. [Ошибки, отмена и повторные вызовы](#8-ошибки-отмена-и-повторные-вызовы)
9. [События и состояние](#9-события-и-состояние)
10. [Хранилище и бинарные ресурсы](#10-хранилище-и-бинарные-ресурсы)
11. [Сборка, подпись и установка](#11-сборка-подпись-и-установка)
12. [Манифест, зависимости и обновления](#12-манифест-зависимости-и-обновления)
13. [Голосовые движки и агент](#13-голосовые-движки-и-агент)
14. [SDK интерфейсов и Studio](#14-sdk-интерфейсов-и-studio)
15. [Задачи и удалённые операции](#15-задачи-и-удалённые-операции)
16. [Ограничения и безопасность](#16-ограничения-и-безопасность)
17. [Совместимость со старым SDK](#17-совместимость-со-старым-sdk)
18. [Проверка и диагностика](#18-проверка-и-диагностика)
19. [Карта исходников](#19-карта-исходников)

## 1. Что входит в SDK

### `amalia-protocol`: API автора плагина

Kotlin package: `org.amalia.platform.protocol`.

Содержит:

- `PluginEntrypoint`, `PluginContext`, `Registration` — запуск, остановку и регистрацию;
- `ContractDescriptor`, `OperationDescriptor`, `ProviderDescriptor` — описание возможностей;
- `ProtocolClient`, `InvocationRequest`, `InvocationResult` — обращение к провайдерам;
- `JsonSchema` — поддерживаемый диалект схем и валидацию;
- `StreamHandler`, `SubscriptionRequest`, `StreamMessage` — события и состояние;
- `PluginStorage`, `StorageNamespace` — собственные данные и настройки;
- `ResourceAccess`, `ResourceRef` — бинарные объекты;
- `Version`, `VersionRange` — версии протокола и контрактов;
- `org.amalia.platform.protocol.ui` — модели декларативного UI и контракта сборки Studio.

Модуль не зависит от Android, Compose или исходников приложения. В зависимостях API есть kotlinx.serialization JSON и kotlinx.coroutines.

### `amalia-runtime`: реализация для хоста

Координаты: `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`.

Это библиотека для разработчика **среды исполнения**, а не обязательная зависимость обычного плагина. Она реализует `CoreRuntime`, grants, выбор провайдеров, replay, leases, файловые ресурсы, проверку пакетов, установку, журнал активаций, durable tasks и remote transport.

Публичный runtime использует пространства имён `org.amalia.platform.runtime` и, для части package/task API, `tech.amalia.platform.runtime`. Не следует угадывать import по Maven group: сверяйте конкретный класс.

Для стандартного Android DEX-плагина подключайте protocol как `compileOnly`. Не включайте runtime в DEX и не пытайтесь администрировать хост через него.

### Где получить SDK

Workflow [platform.yml](../.github/workflows/platform.yml) создаёт:

- `amalia-sdk-repository` — Maven repository с POM, JAR и исходниками;
- `amalia-platform-jars` — отдельные JAR protocol/runtime.

Наличие этих workflow в репозитории не означает публикацию SDK в Maven Central. Авторский пример использует явно указанный локальный Maven repository. Версию SDK и ABI нужно получать вместе с целевой версией хоста.

## 2. Языки и инструменты

| Задача | Подход |
|---|---|
| Исполняемый Android-плагин | Kotlin/JVM → JAR → D8 → DEX |
| Декларативный экран | JSON, Kotlin не требуется |
| Собственный JVM-хост | Kotlin/JVM + protocol/runtime |
| Удалённый сервис на другом языке | Реализация wire-протокола и разрешённого transport; готового Python/JS SDK в этих модулях нет |

Рекомендуемый язык — **Kotlin**. SDK использует `suspend`, `Flow`, sealed interfaces и kotlinx.serialization. Java может обращаться к JVM API, но прямое написание `suspend start/stop` требует работы с Kotlin Continuation ABI; отдельного удобного Java-фасада здесь нет. Наличие JVM-совместимости не означает, что обычные `void start()` и `void stop()` подойдут загрузчику.

Python, JavaScript, TypeScript, Lua и Rust/WASM не являются встроенными runtime этого Android-загрузчика. Написать сервер на них отдельно возможно, но это другой способ интеграции, требующий wire/host transport, а не загрузки скрипта из `.amaliaplug`.

Согласованный стек текущих модулей:

| Компонент | Значение |
|---|---|
| JDK toolchain | 17 |
| JVM bytecode target | 11 |
| Kotlin | 2.2.10 |
| Kotlin serialization compiler plugin, если нужен | 2.2.10 |
| kotlinx.serialization-json | 1.9.0 |
| kotlinx.coroutines | 1.10.2 |
| SDK | 0.2.0-SNAPSHOT |
| Версия wire-протокола | 1.0.0 |
| Версия Android runtime в compatibility policy | 1.0.0 |
| D8 min API | 26 |
| Поддерживаемые DEX | 035 или 037 |

Версия SDK, версия wire-протокола, версия runtime, версия собственного контракта и версия пакета — разные величины.

Точный ABI Android-хоста:

```text
amalia-sdk-0.2-kotlin-2.2.10-coroutines-1.10.2-json-1.9.0-dex1
```

Обновление исходников с сохранением wire protocol 1.0.0 всё равно может потребовать перекомпиляции DEX: бинарный Kotlin ABI и JSON-совместимость не равнозначны.

## 3. Основные понятия

**Пакет** — устанавливаемый архив с publisher, package ID, версией и runtime.

**Контракт** — описание протокола предметной области: операции, входы/выходы, ошибки, события, состояние. Например, `org.example.independent.clock@1.0.0`.

**Провайдер** — конкретная реализация контракта. Один контракт могут реализовывать несколько провайдеров.

**Entrypoint** — класс, через который хост запускает пакет.

**CallerIdentity** — установленная хостом идентичность вызывающего: publisher/package/instance/device. Строка из JSON не может назначить вызывающему эту идентичность.

**Grant** — разрешение хоста на конкретные вызовы. Обнаружение провайдера и установка пакета grants не создают.

**Activation generation** — номер активации. При замене экземпляра старое поколение теряет право регистрировать/возвращать актуальные результаты и использовать привязанное хранилище.

Пример разделения идентификаторов:

```text
publisher: org.example.independent
package:   org.example.independent.clock
contract:  org.example.independent.clock @ 1.0.0
provider:  org.example.independent.clock.local
operation: read
entrypoint: org.example.independent.ClockPlugin
```

Новый ID контракта должен начинаться с publisher ID и точки. Чужой уже опубликованный контракт можно реализовать, не объявляя его заново. Если вы всё же регистрируете известный descriptor, он должен совпадать с существующим: нельзя переопределить известный ID несовместимым описанием.

## 4. Создание проекта

Готовая основа: [examples/independent-plugin](../examples/independent-plugin/README.md). Она использует только опубликованный protocol artifact.

Структура:

```text
my-plugin/
├── settings.gradle.kts
├── build.gradle.kts
├── offers.json
└── src/main/kotlin/org/example/independent/ClockPlugin.kt
```

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.2.10" }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("amaliaSdkRepository").get())
        }
        mavenCentral()
    }
}
rootProject.name = "independent-amalia-plugin"
```

`build.gradle.kts`:

```kotlin
plugins { id("org.jetbrains.kotlin.jvm") }

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
val sdkVersion = providers.gradleProperty("amaliaSdkVersion")
    .getOrElse("0.2.0-SNAPSHOT")
dependencies {
    compileOnly("org.amalia.platform:amalia-protocol:$sdkVersion")
}
```

`compileOnly` означает: API доступен компилятору, но его классы предоставляет хост. Не создавайте fat JAR с Kotlin, kotlinx, protocol, Android или внутренними классами Амалии.

Для собственного `@Serializable` DTO понадобится serialization compiler plugin той же версии Kotlin. Для показанного ниже плагина достаточно `buildJsonObject` и `JsonSchema.parse`, отдельные serializers не нужны.

JDK API не равен Android API. Хотя компилятор работает на JDK 17 и выдаёт target 11, используемые Java-классы должны существовать на целевом Android либо поддерживаться выбранным способом desugaring. Успешная JVM-компиляция этого не доказывает.

## 5. Полный пример плагина

Этот пример повторяет структуру существующего независимого ClockPlugin. Он предоставляет одну операцию `read` и возвращает Unix time в миллисекундах.

```kotlin
package org.example.independent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.amalia.platform.protocol.*

class ClockPlugin : PluginEntrypoint {
    private val registrations = mutableListOf<Registration>()

    override suspend fun start(context: PluginContext) {
        val contract = ContractDescriptor(
            id = "org.example.independent.clock",
            version = Version(1),
            description = "Read the executor wall clock",
            operations = listOf(
                OperationDescriptor(
                    id = "read",
                    description = "Return Unix epoch milliseconds",
                    input = JsonSchema.parse(
                        """{"type":"object","properties":{},"additionalProperties":false}"""
                    ),
                    output = JsonSchema.parse(
                        """{"type":"object","properties":{"epochMillis":{"type":"integer"}},"required":["epochMillis"],"additionalProperties":false}"""
                    ),
                    effect = OperationEffect.READ,
                    idempotency = Idempotency.NONE,
                )
            ),
        )
        registrations += context.registerContract(contract)
        registrations += context.registerProvider(
            ProviderDescriptor(
                id = "org.example.independent.clock.local",
                publisherId = context.identity.publisherId,
                packageId = context.identity.packageId,
                packageVersion = Version(1),
                deviceId = context.identity.deviceId,
                activationGeneration = context.activationGeneration,
                displayName = "Executor clock",
                contracts = listOf(ContractOffer(contract.id, contract.version)),
            ),
            object : OperationHandler {
                override suspend fun invoke(
                    request: InvocationRequest,
                    context: InvocationContext,
                ): InvocationResult {
                    context.ensureActive()
                    return InvocationResult.Success(
                        buildJsonObject {
                            put("epochMillis", System.currentTimeMillis())
                        }
                    )
                }
            },
        )
    }

    override suspend fun stop() {
        registrations.asReversed().forEach { it.close() }
        registrations.clear()
    }
}
```

Требования к entrypoint Android DEX:

- публичный конкретный класс;
- реализует `PluginEntrypoint` из protocol;
- имеет публичный конструктор без аргументов;
- имеет конкретные `suspend start(PluginContext)` и `suspend stop()`;
- указан полным именем Java binary class в `entryPoint`.

Kotlin `object`, абстрактный класс, factory notation и конструктор с зависимостями не подходят. Зависимости плагин получает в `start`, а не через DI-контейнер приложения.

### Жизненный цикл

1. Хост проверяет архив, доверие издателю, совместимость и согласие на активацию.
2. Загрузчик проверяет DEX и разрешает тип entrypoint без его инициализации.
3. После начала журнальной попытки хост создаёт экземпляр и вызывает `start`.
4. Во время `start` плагин регистрирует контракты, провайдеров и stream handlers.
5. Runtime атомарно публикует прошедший проверку набор регистраций.
6. Хост проверяет готовность заявленных providers и подтверждает активацию.
7. При отключении сначала отзывает доступность и управляемую работу, затем выполняет ограниченный stop/cleanup.

Регистрации разрешены только во время стартовой staging-фазы. Чтобы изменить набор провайдеров, нужна новая активация. `Registration.close()` идемпотентен. Хост выполняет cleanup и при ошибках `start/stop`, однако плагин обязан сам освобождать свои сокеты, callbacks и прочие ресурсы.

Не рассчитывайте на главный поток и не выполняйте блокирующий IO без подходящего dispatcher. Не запускайте `GlobalScope` или неуправляемые потоки. Расширение `ManagedPluginContext.scope` существует в runtime-модуле, но не входит в поддерживаемую linkage surface стандартного Android DEX-плагина. Не импортируйте его в compile-only protocol-проект; долгую работу связывайте с вызовом/коллекцией Flow и корректным `stop`.

## 6. Контракты и JSON Schema

`ContractDescriptor` описывает:

| Поле | Назначение |
|---|---|
| `id`, `version` | Идентичность версии контракта |
| `operations` | Вызываемые операции |
| `events` | События |
| `states` | Снимки состояния |
| `resourceTypes` | Предметные типы бинарных ресурсов |
| `transports` | Заявленные транспорты |
| `features` | Дополнительная семантика |
| `compatibility` | Текст правил совместимости |
| `documentation` | Ссылки на документацию |
| `extensions` | Явные JSON-расширения |

У операции есть `input`, `output`, открытая карта `errors`, `effect` (`READ/WRITE`), `idempotency` (`NONE/INHERENT/KEYED`), `execution` (`IMMEDIATE/TASK`), `cancellable` и `requiredFeatures`.

Эти декларации не реализуют поведение за автора. Например, `KEYED` не создаёт persistent deduplication, `TASK` не запускает scheduler, а `cancellable = true` не откатывает внешний эффект.

### Поддерживаемый диалект схем

Поддерживаются:

```text
type: один тип или непустой union
properties
required
additionalProperties: boolean
items: schema object
enum, const
minimum, maximum
minLength, maxLength
minItems, maxItems
```

Аннотации: `title`, `description`, `default`, `examples`, `$comment`, а также `x-*`. `default` не дописывает данные автоматически.

Не поддерживаются `$ref`, `$schema`, `format`, `pattern`, `oneOf/anyOf/allOf`, boolean schemas, tuple items, schema-valued additionalProperties и прочие неизвестные validation keywords. Неизвестные keywords отклоняются при создании/декодировании схемы.

Пример:

```json
{
  "type": "object",
  "properties": {
    "steps": {"type": "integer", "minimum": 1, "maximum": 100}
  },
  "required": ["steps"],
  "additionalProperties": false
}
```

По умолчанию неизвестные свойства разрешены. Для строгого запроса указывайте `additionalProperties:false`. Runtime проверяет input до dispatch, успешный output и details объявленных доменных ошибок — перед выдачей результата.

`validate()` возвращает список `SchemaViolation` с JSON Pointer путями. Длины строк считаются в Unicode code points. Числа сравниваются десятично, `1` и `1.0` эквивалентны как числовые значения; числовая строка числом не становится. Для межъязыкового обмена точные деньги и большие числа, которые другой клиент не умеет хранить без потерь, лучше описывать отдельными строковыми полями контракта.

Версии `Version` — от одного до трёх числовых компонентов, нормализованных в три. `Version(1)` — `1.0.0`. Prerelease вроде `1.0.0-beta` в `Version.parse` не поддерживается. Диапазон — включённый минимум и исключённый максимум: `[1.0.0, 2.0.0)`.

## 7. Вызовы других плагинов

Плагин получает caller-bound `context.client`:

```kotlin
val discovery = context.client.discover(
    ProviderQuery(
        contractId = "org.example.independent.clock",
        versions = VersionRange(Version(1), Version(2)),
        providerId = "org.example.independent.clock.local",
    )
)
```

`discover` сообщает кандидатов и причины недоступности. Он не выдаёт доступ. При нескольких candidates выбор должен быть явным или сохранённым пользователем; порядок регистрации не определяет нужного исполнителя.

Пример запроса внутри suspend-кода потребителя:

```kotlin
val response = context.client.invoke(
    InvocationRequest(
        requestId = java.util.UUID.randomUUID().toString(),
        contractId = "org.example.independent.clock",
        contractVersion = Version(1),
        providerId = "org.example.independent.clock.local",
        operationId = "read",
        input = kotlinx.serialization.json.buildJsonObject {},
        deadlineEpochMillis = System.currentTimeMillis() + 5_000,
    )
)
when (val result = response.result) {
    is InvocationResult.Success -> println(result.output)
    is InvocationResult.Failure -> println(result.error.code)
    is InvocationResult.Accepted -> println(result.task.id)
    is InvocationResult.InteractionRequired -> println(result.interaction.id)
}
```

Для фактического выполнения хост должен выдать grant **этому caller**, этому контракту и операции, при необходимости конкретному provider. Даже чтение часов в примере не разрешается автоматически.

`InvocationRequest` не содержит effective grants или доверенную caller identity. `InvocationContext` создаёт runtime отдельно и передаёт handler. Не принимайте «права» из `input` или `extensions` за полномочия.

У общего управления пакетами есть выбор известных продуктовых ролей. Универсальный UI выдачи grants произвольным внешним контрактам нельзя предполагать: для своего consumer/provider-сценария потребуется соответствующая host-интеграция или отдельно предусмотренная политика хоста.

## 8. Ошибки, отмена и повторные вызовы

Четыре вида результата:

| Результат | Значение |
|---|---|
| `Success(output, evidence)` | Операция вернула успешный результат |
| `Accepted(TaskRef)` | Задача принята; цель ещё не подтверждена |
| `InteractionRequired` | Нужно продолжение/ответ пользователя |
| `Failure(ProtocolError)` | Протокольная или доменная ошибка |

`ProtocolError` содержит namespaced `code`, `LocalizedText`, `retry`, `details`, evidence и при необходимости `retryAfterEpochMillis`.

Основные коды: `protocol.access_denied`, `invalid_input`, `invalid_output`, `provider_unavailable`, `unsupported_version`, `unsupported_operation`, `unsupported_feature`, `deadline_exceeded`, `cancelled`, `execution_failed`, `unknown_outcome`, `stream_overflow` — все с префиксом `protocol.`.

Доменные ошибки можно добавлять собственными строками без изменения SDK. Не передавайте stack traces, токены и секреты в explanations/details.

`RetryAdvice`:

- `NEVER` — повтор не предусмотрен;
- `SAFE` — контракт разрешает безопасный повтор;
- `AFTER_DELAY` — повтор позже;
- `AFTER_INTERVENTION` — после вмешательства;
- `VERIFY_OUTCOME` — сначала проверить фактический результат.

**Таймаут не доказывает, что действие не состоялось.** Для отправки сообщения, платежа или изменения внешней системы нельзя автоматически повторять запрос после потери ответа. `protocol.unknown_outcome` обязательно использует `VERIFY_OUTCOME`.

Если используется `IdempotencyKey`, runtime требует scope:

```text
<caller.instanceId>/<providerId>/<contractId>/<operationId>
```

и будущий срок истечения. Проверка формата не создаёт хранилище результатов и exactly-once execution. Это должен реализовать provider/домен.

Вызывайте `InvocationContext.ensureActive()` перед внешним эффектом и в долгих циклах. Не поглощайте `CancellationException` и не превращайте отмену в успех. Отзыв grant/поколения проверяется повторно, в том числе перед доставкой результата, но уже выполненное действие это не отменяет.

Общие `ProtocolClient.respond()` и `cancel(TaskRef)` объявлены в SDK, однако текущий `CoreRuntime` возвращает `UNSUPPORTED_OPERATION`. Для реально поддерживаемого управления задачами нужны отдельные операции task-контракта.

## 9. События и состояние

Провайдер регистрирует `StreamHandler` во время `start`:

```kotlin
context.registerStreamProvider(providerId, handler)
```

Handler возвращает `Flow<StreamMessage>`. Потребитель вызывает `client.subscribe(SubscriptionRequest(...))`. Каждая collection владеет подпиской; отмена collection должна освобождать ресурсы. Для callbacks используйте корректную регистрацию/отписку, например `callbackFlow` и `awaitClose`.

Типы сообщений:

- `Event(eventId, topicId, payload, orderingScope, sequence, cursor)`;
- `Snapshot(topicId, revision, value, cursor)`;
- `Closed(error?)`.

Для STATE сначала требуется snapshot. Последующие state-сообщения — также snapshots; произвольного общего delta-протокола нет. Для EVENTS допускаются повторы/replay, глобальный порядок между разными ordering scopes не обещан.

Grant на подписку обозначается `subscribe:<topicId>`. Само наличие event descriptor не разрешает подписку.

`bufferCapacity` в протоколе: 1–65536, но runtime может установить меньший предел. Текущий default runtime limit — 256. Поддерживаемая политика:

- `SUSPEND` — backpressure;
- `FAIL` — закрытие с overflow;
- `DROP_OLDEST/DROP_LATEST` присутствуют в SDK, но текущий runtime их отклоняет: разрешение потерь ещё не реализовано как достаточная contract policy.

`ReplayEvents` в runtime — bounded in-memory event log. Он не persistent: после рестарта меняется epoch. Чужой, будущий или вытесненный cursor приводит к явному `runtime.replay_gap`; синхронизацию через snapshot проектирует автор контракта. Это helper для runtime-проекта, не автоматически доступный класс Android protocol-only DEX.

Runtime проверяет payload events/state, но предметную семантику stream `filter` должен проверять provider. Ограничивайте размер и значения filter самостоятельно.

## 10. Хранилище и бинарные ресурсы

### Собственные настройки и данные

```kotlin
val previous = context.storage.settings.read("locale")
context.storage.settings.write("locale", "ru")
context.storage.data.write("last-result", "42")
context.storage.data.delete("last-result")
```

Оба namespace — string key/value. Это synchronous IO: вызывайте на worker dispatcher. Не выполняйте файловые операции на UI-потоке.

В общем SDK storage по умолчанию запрещён. Android `PlatformHost` выдаёт доступ точной installer-authenticated identity собственного пакета и повторно проверяет поколение/политику. Старый context не получает доступ к новой активации.

Текущие квоты:

- 1024 ключа на namespace;
- значение до 16 KiB UTF-8;
- combined live keys/values до 2 MiB.

Нет доступа к выбору чужого package ID, перечислению всех пакетов, произвольным путям, управлению snapshot/migration или installer. Вызовам контрактов и private storage соответствуют разные политики: invocation grant не является разрешением на KV.

Это не secret vault. Не храните здесь host grants, системные токены и данные, для которых требуется отдельное защищённое хранение.

### Бинарные данные

Для изображения, аудио и файла используйте `ResourceAccess`, а в JSON передавайте `ResourceRef`.

```kotlin
val bytes = "example".toByteArray(Charsets.UTF_8)
val resource = context.resources.write(
    ResourceWriteRequest("text/plain", bytes.size.toLong()),
    kotlinx.coroutines.flow.flow { emit(bytes) },
)
context.resources.read(resource).collect { chunk ->
    // Обработать ограниченный chunk.
}
context.resources.delete(resource)
```

Для фрагмента нужны coroutine/Flow imports в содержащем проекте. Запись создаёт новый immutable объект; редактирования существующего файла на месте нет.

`ResourceRef` содержит owner, MIME, version, права, размер, expiry и optional transfer locator. Это метаданные, не bearer credential. Передача ref другому плагину не даёт ему права чтения; точные grants выдаёт host.

Default local store:

| Ограничение | Значение |
|---|---:|
| Один ресурс | 32 MiB |
| Общая квота | 256 MiB |
| Chunk | 64 KiB |
| Число ресурсов | 4096 |
| Число grants | 4096 |

Ресурсы доступны owning package между активациями, чужие instance grants не сохраняются при reopening. Store проверяет права, размер, digest и частичные записи. Прикладного шифрования и remote transfer в этом store нет.

## 11. Сборка, подпись и установка

Команды ниже предназначены для компьютера автора или CI. При подготовке этого документа сборки не запускались.

### 11.1. Получить Maven repository

Загрузите и распакуйте CI artifact `amalia-sdk-repository`. Если вы поддерживаете сам хост, существующая команда публикации из корня репозитория:

```bash
./gradlew -p platform :protocol:publishSdkPublicationToSdkDistributionRepository :runtime:publishSdkPublicationToSdkDistributionRepository
```

Результат — `platform/build/sdk-repository/`.

### 11.2. Собрать JAR

Для проекта из раздела 4, с установленным Gradle версии, используемой SDK-примером:

```bash
gradle -p my-plugin jar \
  -PamaliaSdkRepository=/absolute/path/to/sdk-repository \
  -PamaliaSdkVersion=0.2.0-SNAPSHOT
```

Репозиторный пример ориентирован на Gradle 9.7.1. При использовании wrapper сначала проверьте его существование и версию в своём проекте: скопированный пример собственного wrapper не содержит.

### 11.3. Преобразовать в DEX

Из каталога, содержащего `my-plugin`:

```bash
mkdir -p my-plugin/build/dex
"$ANDROID_HOME/build-tools/36.0.0/d8" \
  --min-api 25 \
  --output my-plugin/build/dex \
  my-plugin/build/libs/independent-amalia-plugin.jar
```

`36.0.0` — пример пути к установленному D8. Выберите реально установленную версию Android build-tools. При необходимости разрешения типов добавьте `--classpath` с точными shared SDK/Kotlin/kotlinx библиотеками хоста и соответствующий Android library input средствами вашей версии D8. Shared библиотеки должны быть classpath, а не program inputs.

`--min-api 25` здесь выбирает совместимый формат DEX 037, а не снижает minSdk
хоста: приложение по-прежнему требует Android API 26+. D8 с min-api 26 выдаёт
DEX 038, который текущий inventory reader не принимает.

Результат для загрузчика — ровно один `classes.dex`. Обычный JAR с `.class` не устанавливается напрямую.

### 11.4. Заявить offers

`my-plugin/offers.json`:

```json
[
  {
    "providerId": "org.example.independent.clock.local",
    "contracts": [
      {
        "contractId": "org.example.independent.clock",
        "version": "1.0.0",
        "features": []
      }
    ]
  }
]
```

Inventory должен соответствовать реально зарегистрированным providers. Host readiness probe проверяет provider/package/publisher/device/generation и точные contract offers. Пустые offers не проходят текущий declared-provider readiness для DEX.

### 11.5. Создать ключ издателя

Создайте каталог для ключей вне исходников/архива плагина и ограничьте доступ к нему. Например, в рабочем каталоге автора:

```bash
mkdir -p publisher-keys
chmod 700 publisher-keys
openssl ecparam -name prime256v1 -genkey -noout \
  -out publisher-keys/author.pem
chmod 600 publisher-keys/author.pem
openssl pkey -in publisher-keys/author.pem -pubout \
  -out publisher-keys/author-public.pem
openssl pkey -pubin -in publisher-keys/author-public.pem -outform DER \
  -out publisher-keys/author-public.der
```

Приватный ключ нельзя включать в пакет или репозиторий. Хосту передаётся только публичный P-256 SPKI DER/PEM, отдельно от доверия архиву. Нужна явная проверка/подтверждение ключа издателя.

### 11.6. Упаковать и подписать

Из корня репозитория Amalia, при указанных абсолютных путях:

```bash
python3 tools/PackagePlatformPlugin.py \
  --dex /absolute/path/my-plugin/build/dex/classes.dex \
  --package-id org.example.independent.clock \
  --publisher org.example.independent \
  --version 1.0.0 \
  --entrypoint org.example.independent.ClockPlugin \
  --key-id author-1 \
  --offers /absolute/path/my-plugin/offers.json \
  --private-key /absolute/path/publisher-keys/author.pem \
  --output clock.amaliaplug
```

Утилита принимает **уже готовый DEX**, вычисляет SHA-256, генерирует platform manifest и подписывает его через OpenSSL. Она не компилирует Kotlin, не запускает D8, не добавляет ключ в хост и не выдаёт grants. Существующий output не перезаписывается.

Утилита поддерживает базовые offers/compatibility, но не предоставляет CLI-параметры для dependencies, data migrations или declaration UI. Для расширенного manifest нужен свой packaging pipeline по формату ниже и новая подпись итоговых байтов.

### 11.7. Установка в приложении

Путь нового API: управление platform packages из настроек плагинов.

1. Добавить/подтвердить публичный ключ издателя и создать свежий host с текущими pins.
2. Импортировать архив через файловый выбор.
3. Проверить immutable review: конкретный stage, hash, publisher, version и plan.
4. Одобрить активацию.
5. Дождаться health/readiness и подтверждения live-активации.
6. Для продуктовой роли отдельно выбрать provider и подтвердить точные операции.
7. Для собственного контракта обеспечить grants и consumer-интеграцию со стороны хоста.

Изменение publisher pins требует нового host: verifier использует snapshot pins. Добавление ключа само по себе не отзывает уже работающий код. Новые external platform packages не запускаются автоматически при старте приложения; сохранённая selected-версия и live-процесс — разные состояния.

## 12. Манифест, зависимости и обновления

### Формат Android code package

```text
clock.amaliaplug (ZIP)
├── platform.json
├── package.sig
└── classes.dex
```

Минимальный manifest для описанного примера:

```json
{
  "packageId": "org.example.independent.clock",
  "publisher": "org.example.independent",
  "version": "1.0.0",
  "runtime": "android-dex",
  "entryPoint": "org.example.independent.ClockPlugin",
  "compatibility": {
    "protocol": {"minimum": "1.0.0", "maximumExclusive": "2.0.0"},
    "runtimeVersion": {"minimum": "1.0.0", "maximumExclusive": "2.0.0"},
    "abi": "amalia-sdk-0.2-kotlin-2.2.10-coroutines-1.10.2-json-1.9.0-dex1"
  },
  "offers": [
    {
      "providerId": "org.example.independent.clock.local",
      "contracts": [
        {"contractId": "org.example.independent.clock", "version": "1.0.0", "features": []}
      ]
    }
  ],
  "sha256": {
    "classes.dex": "0000000000000000000000000000000000000000000000000000000000000000"
  }
}
```

Нулевая SHA-256 здесь показывает формат поля; для установки требуется digest реального DEX. Штатная packaging utility вычисляет его автоматически.

`package.sig` — JSON с полями `algorithm`, `keyId`, `signature`. Алгоритм — `SHA256withECDSA`, ключ — P-256, подпись DER ECDSA закодирована standard padded Base64. Подписываются **точные raw bytes `platform.json`**, включая пробелы и завершающий перевод строки. Любое изменение manifest после подписи требует переподписи.

Payload inventory покрывает все файлы кроме `platform.json` и `package.sig`. Для текущего `android-dex` inventory допускает только `classes.dex`; assets, native libraries и отдельные resources туда добавлять нельзя.

ZIP должен содержать обычные file entries с STORED/DEFLATED, без explicit directory records. Пути — относительные ASCII с `/`; traversal, case aliases, collisions и reserved names отклоняются. Unknown metadata fields и duplicate JSON keys отклоняются.

### Дополнительные поля

| Поле | Назначение |
|---|---|
| `dependencies` | Требования к контрактам, версиям, features и при необходимости provider ID |
| `conflicts` | Package IDs конфликтующих пакетов |
| `data` | Версия KV schema, readable schemas и декларативные migrations |

Пример добавляемой секции зависимости:

```json
{
  "dependencies": [
    {
      "id": "clock",
      "contractId": "org.example.independent.clock",
      "versions": {"minimum": "1.0.0", "maximumExclusive": "2.0.0"},
      "providerId": "org.example.independent.clock.local",
      "features": [],
      "optional": false
    }
  ]
}
```

Это фрагмент полного manifest. Dependency resolver планирует exact bindings, отклоняет отсутствующие/неоднозначные mandatory providers и startup cycles. Он не скачивает зависимости, не выдаёт grants и не запускает автоматически весь план.

Package version формально может быть opaque token, но при непустых `offers` обязана разбираться numeric `Version`. Для DEX примера используйте `1.0.0`.

### Миграция собственных данных

```json
{
  "data": {
    "schema": "v2",
    "readableSchemas": ["v2"],
    "migrations": [
      {
        "from": "v1",
        "to": "v2",
        "operations": [
          {"namespace": "settings", "operation": "rename", "key": "old-name", "destination": "new-name"},
          {"namespace": "data", "operation": "set", "key": "format", "value": "2"}
        ]
      }
    ]
  }
}
```

Поддерживаются `set`, `delete`, `copy`, `rename`; операции ограничены namespace `data/settings`. Нет Kotlin migration callbacks, SQL, файловой миграции, сетевых вызовов и произвольного кода миграции.

Обновление сначала проходит preflight. Старое поколение отзывается до миграции. Host сохраняет snapshot data/settings, запускает новую версию и подтверждает её после health. При неуспехе данные восстанавливаются через transaction/recovery policy; старый код автоматически не перезапускается.

Rollback требует отдельного review, подходящего snapshot и явного подтверждения совместимости данных. Он не откатывает отправленные сообщения, внешние файлы, grants и другие внешние эффекты. Native recovery позволяет отключить пакет без выполнения кода; запуск предыдущей версии выполняется нормальным installer-путём.

## 13. Голосовые движки и агент

Новый plugin provider может реализовать известную продуктовую роль через JSON wire contract. Для этого не требуется реализовывать внутренние app-интерфейсы движков.

| Контракт v1 | Способ вызова | Роль |
|---|---|---|
| `com.my.amali.voice.recognition` | EVENTS `turn` | Распознавание одного микрофонного turn |
| `com.my.amali.language.generation` | EVENTS `generate` | Генерация текста и tool calls |
| `com.my.amali.voice.output` | EVENTS `speak` + operation `ack` | Синтез речи в PCM resources |
| `com.my.amali.tools.execution` | operation `execute` | Исполнение named tools |
| `com.my.amali.agent.strategy` | operation `decide` | Следующее решение агента |

Точные схемы и compatibility text находятся в [ProductRoleContracts.kt](../app/src/main/java/com/my/amali/platform/builtin/ProductRoleContracts.kt) и [AgentStrategyContract.kt](../app/src/main/java/com/my/amali/platform/agentroles/AgentStrategyContract.kt). Эти app-объекты не входят в protocol JAR: используйте их как спецификацию wire-контракта, не добавляйте app dependency в DEX.

### STT

`turn` — whole-turn subscription, включающая acquisition/segmentation в текущем provider. Filter содержит `language`, `silenceSeconds`; события `level`, `partial`, `final`. Отмена collection освобождает capture. Permission и арбитраж микрофона остаются обязанностью реализации/хоста.

### LLM

`generate` использует bounded filter с prompt, messages, tools и nonsecret options. Нужна поддержка установленной семантики tool calling, source completion и финального `end`. Поток завершён только согласно контракту; нельзя выдавать частичный tool call как готовое действие.

Keys/custom endpoint остаются у host settings для встроенных providers; штатный wire-запрос не передаёт весь `EngineOptions` с секретами внешнему плагину. Для собственной сетевой реализации механизм получения собственных credentials нужно проектировать отдельно.

### TTS

`speak` возвращает `ResourceRef` на PCM signed 16-bit little-endian mono. Нельзя подменять его MP3 или base64 JSON. Ресурсный chunk — до 65536 байт, sample rate задаётся source в пределах контракта. После потребления caller вызывает `ack(resourceId)`; sharing/read grants обеспечивает host policy.

Текущий контракт — whole-text synthesis. Token/WebSocket TTS sessions и полная замена output manager через этот API не реализованы.

### Tools

`execute` получает `{name, argumentsJson}` и возвращает `{output}`. Provider обязан поддерживать уже объявленный продуктовый каталог tools и их схемы.

**Регистрация произвольного platform operation не добавляет его в каталог инструментов ИИ.** Для нового tool name нужна отдельная интеграция каталога. Replacement executor и расширение каталога — разные задачи.

### Agent strategy

`decide` обменивается полным bounded JSON context/decision. Screenshot передаётся как `image/jpeg ResourceRef`, а чтение требует host-issued sharing lease. В текущей ServiceLocator sharing для выбранной стратегии подключён через exact activation identity.

Strategy replacement не заменяет весь AgentLoopRunner, wake scheduler, runtime задач и системные права. Wake, VAD как независимая роль, весь voice session manager, playback и стандартная навигация ещё не вынесены целиком в заменяемые SDK-роли.

Выбор роли и выдача exact grants требуют отдельного host approval после установки. Provider ID выбирается явно; недоступный выбор не означает автоматический переход на первый найденный provider.

## 14. SDK интерфейсов и Studio

Для собственного экрана существует отдельный data runtime **`declarations`**. Это JSON UI, интерпретируемый встроенным renderer.

```text
screen.amaliaplug
├── platform.json
├── ui.json
└── components.json  (необязательно)
```

Manifest: `runtime: "declarations"`, без `entryPoint`, compatibility ABI `amalia-ui-document-v1`, inventory `ui.json` и при необходимости `components.json`. `offers` должны быть пустыми. Подпись необязательна, но если она присутствует, обязана пройти проверку; неподписанный пакет не получает аутентифицированного publisher.

> **ИИ, создающему UI:** до генерации документа обязательно прочитать [контракт сильного UI/UX](AI_UI_UX_GUIDE_RU.md). В нём — композиция, запрещённые антипаттерны, состояния, checklist и [полный локализованный пример](examples/beautiful-focus-ui.json). Этот раздел описывает технический контракт; гайд отвечает за продуктовое качество результата.

### Модели UI SDK

- `UiDocument`: schemaVersion, id, route, titleKey, placement, root, nodes, actions, sources, strings, states;
- `UiNode`: component, label, action, binding, properties, children;
- `UiAction`: точный provider/contract/version/operation и input bindings;
- `UiSource`: provider и topic state subscription;
- `UiBinding`: `LITERAL`, `STATE`, `FORM`, `ITEM` и JSON Pointer;
- `UiPhase`: `LOADING`, `EMPTY`, `READY`, `ERROR`, `DENIED`, `CONFLICT`.

Поддерживаемые native primitives:

```text
Композиция:  stack, column, grid, list, spacer, divider
Смысл:       hero, section, item, metric, progress, badge, notice, state
Контент:     glass, heading, text, input, toggle, slider, choices
Действия:    button, actions, primary, action
```

Полное имя — `org.amalia.ui.<имя>`, например `org.amalia.ui.hero`. Базовые `column`, `glass`, `text`, `action`, `input`, `heading`, `stack`, `primary`, `divider` не принимают произвольные CSS-подобные настройки. `hero`, `section`, `item` поддерживают `surface: auto|plain|glass`: auto объединяет содержимое стеклянной section в одну поверхность; plain оставляет типографику на фоне. `choices` отображается единым радиосписком. Семантические компоненты имеют закрытые JSON-схемы: текст передаётся через `*Key`, композиция — через `children`/`columns`, иконки/тон/размер spacer — только перечисленными enum-значениями. Подробные свойства и UX-правила: [AI UI/UX guide](AI_UI_UX_GUIDE_RU.md#3-компоненты-какие-есть-и-когда-их-брать). HTML, JavaScript, WebView, reflection, shell expressions и загрузки собственного Compose renderer через этот документ нет.

`schemaVersion` должен явно присутствовать и равняться 1. `placement` — `screen`. `id` и `route` должны начинаться с `${packageId}.`. Все используемые text keys должны быть переведены в девяти словарях: `ru`, `en`, `ja`, `de`, `es`, `fr`, `zh`, `hi`, `ar`.

`button` принимает `variant: primary|secondary|quiet|destructive` (default secondary), `icon`, `enabled` (default true), требует labelKey/actionId. Destructive требует confirmationKey действия. `actions` без properties адаптивно размещает children в 1–3 колонках. Primary/secondary используют компоненты приложения. Реальный pending виден в primary и блокирует дубликаты.

[Машинный контракт всех компонентов](UI_COMPONENTS.json) и [библиотека шаблонов](UI_TEMPLATES_RU.md) — источники точного синтаксиса и композиций для генерации моделью.

`list` разбивает полный массив (`value`) на локальные страницы; сетевой cursor/загрузка страниц не реализованы. Один дочерний узел — шаблон строки. `pageSize`: 1–50 (по умолчанию 6). Обязательные properties: `previousKey`, `nextKey`, `summaryKey`; необязательные: `emptyTitleKey`, `emptyDescriptionKey`. В summary поддержаны `{from}`, `{to}`, `{total}`, `{page}`, `{pages}`.

В строке `ITEM` выбирает текущий элемент массива; `pointer` выбирает поле. Для `item` доступны `titleBinding`, `subtitleBinding`, `valueBinding` с тем же форматом `UiBinding`. `ITEM` разрешён также в input действия, вызванного из строки; данные фиксируются при нажатии. Вне строки такое действие невалидно. Блокировка повторной отправки остаётся общей для `actionId`, до подтверждения результата host-ом.

`toggle`, `slider`, `choices` читают изменённое значение FORM, иначе собственный `value` (начальное значение, например LITERAL/STATE). Действия читают их через `FORM` с `sourceId` узла. Типы: toggle — JSON boolean, slider — JSON number, choices/input — string. Не задавайте контролу ссылку FORM на самого себя. Без value toggle начинается с false, slider — с min, choices — без выбора. Поля ввода в шаблонах list запрещены: отдельного пространства FORM для каждой строки пока нет.

См. [полный контракт компонентов](AI_UI_UX_GUIDE_RU.md#9-списки-формы-и-контекст-действий) и [пример списка](examples/paged-list-ui.json). Пример показывает вызов настоящего контракта из `examples/open-contract`, но требует отдельного подключения этого demo-provider и выдачи прав; штатная установка приложения его не содержит.

Пример статического документа:

```json
{
  "schemaVersion": 1,
  "id": "org.example.notes.screen",
  "route": "org.example.notes.home",
  "titleKey": "title",
  "placement": "screen",
  "root": "root",
  "nodes": [
    {"id": "root", "component": "org.amalia.ui.glass", "children": ["message"]},
    {"id": "message", "component": "org.amalia.ui.text", "labelKey": "message"}
  ],
  "strings": {
    "ru": {"title": "Заметки", "message": "Экран готов"},
    "en": {"title": "Notes", "message": "Screen ready"},
    "ja": {"title": "メモ", "message": "画面の準備ができました"},
    "de": {"title": "Notizen", "message": "Ansicht bereit"},
    "es": {"title": "Notas", "message": "Pantalla lista"},
    "fr": {"title": "Notes", "message": "Écran prêt"},
    "zh": {"title": "笔记", "message": "界面已就绪"},
    "hi": {"title": "नोट्स", "message": "स्क्रीन तैयार है"},
    "ar": {"title": "ملاحظات", "message": "الشاشة جاهزة"}
  }
}
```

Экран использует caller identity своего пакета. Подтверждение кнопки с `confirmationKey` не создаёт grant. `UiSource` сейчас использует STATE snapshots; EVENTS не становятся автоматически UI state. Для accepted/interaction/unknown результатов нужна связанная host-проекция; универсальная кнопка отмены упирается в неподдерживаемый общий `ProtocolClient.cancel`.

`components.json` даёт package-local композицию из этих primitives. IDs имеют вид `${packageId}.components.<name>`. Это не native renderer API и не межпакетная component library. Не используйте manifest-валидный package ID произвольного формата для шаблонов: template loader дополнительно требует lowercase dotted identifier, максимум 96 символов.

### Studio

Публичный контракт сборки: `org.amalia.build@1`, operation `build`. Модели: `StudioProject`, `StudioSource`, `BuildRequest`, `BuildArtifact`.

Реально подключённый локальный builder создаёт только declarations package:

```text
редактирование → валидация → сохранение revision → build
→ проверка source/attestation/archive → stage → consent → activate
```

Локальный builder запрещает network, dependencies и requested capabilities; архив ограничен 2 MiB. Он предназначен для собственного Studio caller. В текущей composition нет готового внешнего Kotlin/Compose build provider. Наличие `BuildRequest` не означает, что на телефоне уже можно компилировать произвольный Kotlin-плагин.

## 15. Задачи и удалённые операции

### Durable tasks

Продуктовый task-контракт: `tech.amalia.tasks@1.0.0`. Операции: `submit`, `get`, `respond`, `cancel`, `pause`, `resume`.

App helper `TaskServiceClient` не входит в protocol JAR, но wire-вызовы можно формировать через `ProtocolClient.invoke`, имея опубликованную схему и host grant. Owner checks сохраняются: внешний caller не получает product tasks другого владельца.

`Accepted` подтверждает приём. Только terminal task state `SUCCEEDED` означает успешное завершение. `UNKNOWN`/`RECONCILING` требуют проверки и не запускают автоматический повтор. Ответ на continuation требует реального host UI согласия, а не JSON, придуманного моделью.

Task engine локален; автоматической миграции старых executor generations и распределённого scheduler нет. Общие методы protocol respond/cancel не заменяют task operations.

### Remote

В runtime реализован pinned mutual TLS transport для immediate JSON invocation. Pairing и export/import policy управляются host; плагин не получает API самостоятельной выдачи trust/grants.

Текущая Android composition ограничивает каталог remote imports контрактом `com.my.amali.tools.execution`. После host import провайдер может вызываться обычным protocol client, но каждому caller всё ещё нужны grants.

Нет remote discovery, streams, tasks, ResourceRef transfer, relay, auto reconnect/retry/failover и sync. Потеря ответа после попытки отправки переводит результат в unknown outcome. Наличие `transfer.locator` в ResourceRef не делает ресурс доступным на другом устройстве.

## 16. Ограничения и безопасность

### Поддерживаемый Android DEX package

| Параметр | Ограничение |
|---|---|
| Runtime | Только `android-dex` для исполняемого пакета |
| DEX | Один root `classes.dex`, standard little-endian 035/037 |
| Размер DEX | 112 байт — 32 MiB |
| Multidex | Не поддерживается |
| APK/JAR как runtime payload | Не поддерживается |
| Native `.so` | Не поддерживается в package inventory |
| Assets/дополнительные payload files | Не поддерживаются этим DEX loader |
| Entry point | Публичный concrete PluginEntrypoint с public no-arg constructor |
| Compose/app/runtime imports | Не входят в поддерживаемую linkage surface |
| Сигнатура | Обязательная pinned P-256 подпись |

Разрешённая parent linkage surface: `java.*`, `javax.*`, `android.*`, `dalvik.*`, `kotlin.*`, `kotlinx.*`, `org.amalia.platform.protocol.*`. Доступность namespace не гарантирует наличие любого класса или совместимость любой версии библиотеки. Не включайте shared class definitions в DEX: reserved namespaces проверяются до ART loading.

### Размеры пакетов и runtime

| Ограничение | Default |
|---|---:|
| ZIP archive | 64 MiB |
| Entries | 2048 |
| Один файл | 32 MiB |
| Распакованный суммарный размер | 128 MiB |
| Manifest | 256 KiB |
| Signature envelope | 4 KiB |
| Compression ratio | 200:1 |
| Path length / depth | 240 символов / 16 сегментов |
| Startup | 15 секунд |
| Stop | 5 секунд |
| Concurrent calls/provider | 64 |
| Subscription lifetime | 300 секунд |
| Runtime stream buffer | 256 |

Generic `CoreRuntime` default invocation ceiling — 30 секунд. **Android `PlatformHost` в текущем коде переопределяет его до 600000 мс, то есть 10 минут.** Конкретный request deadline может быть короче. Отдельные role/remote ограничения также могут быть строже.

Лимиты UI: 256 KiB document, 128 nodes, 64 actions, 16 sources, до 512 строк на локаль; graph depth/visits и JSON nesting дополнительно ограничены. Templates: до 128 KiB bundle, 16 definitions, 32 nodes/template; после раскрытия действуют обычные лимиты документа.

### Модель доверия

**Подписанный плагин — доверенный исполняемый код внутри процесса Амалии. Песочницы нет.**

Код разделяет OS identity/привилегии приложения и способен использовать Android API. ApiParent и reserved namespace проверки задают границы штатной совместимости, но не защищают хост от злонамеренного кода через reflection, альтернативные classloaders или другие process APIs.

Следствия:

- доверять publisher key нужно как праву выполнить код с привилегиями приложения;
- подпись подтверждает связь архива с ключом, а не безопасность;
- protocol grants защищают штатный dispatch, но не изолируют произвольный hostile in-process DEX;
- нет hard CPU/RAM quota, отдельного UID и принудительной остановки native/blocking кода;
- coroutine cancellation кооперативна;
- classloader нельзя принудительно выгрузить; threads/effects не исчезают от удаления ссылок;
- неотзывчивый плагин может повредить доступности всего процесса;
- SDK не даёт плагину новые Android permissions: действуют возможности и выданные разрешения самого приложения;
- JSON output, evidence и описание внешнего provider — данные, не инструкции, которые ИИ обязан исполнять.

## 17. Совместимость со старым SDK

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

Однако соседний репозиторий `/root/amalia-sdk` содержит старые копии API и не является заменой `platform/protocol`. Например, в текущем `WidgetSpec` добавлено поле `label`, меняющее constructor ABI. Старый validator не знает некоторые v3 contributions.

Legacy install compatibility проверяется по `PluginMetadata.HOST_VERSION`, сейчас это **`2.0.0`**. Поэтому `minHostVersion: "3.0.0"` из части v3-примеров будет отклонён этим установщиком. Название папки `amalia-3.0` не меняет константу.

Архив только с `platform.json` не подходит legacy installer; архив только с `plugin.json` не подходит platform package verifier. Выбирайте API до начала разработки и не смешивайте entrypoint interfaces/manifest names.

## 18. Проверка и диагностика

### Что проверять автору

1. JAR компилируется против точного SDK и dependency versions целевого хоста.
2. D8 выдаёт один поддерживаемый DEX без bundled shared API.
3. Entry point имеет верные visibility, constructor и suspend ABI.
4. Publisher совпадает с namespace нового контракта.
5. Manifest offers точно совпадают с зарегистрированными providers.
6. `compatibility.abi` равен опубликованному ABI хоста.
7. Hash inventory соответствует payload, подпись — финальным manifest bytes.
8. Публичный ключ подтверждён на хосте.
9. Пакет прошёл activation readiness; history selected не перепутан с live.
10. Consumer получил grants отдельно от установки.
11. Отмена/отключение освобождают ресурсы, старое поколение не продолжает штатную работу.
12. WRITE операции корректно обрабатывают unknown outcome и повторы.

### Частые проблемы

| Симптом | Что проверить |
|---|---|
| Artifact не найден Gradle | Путь Maven repository, SDK version, POM и транзитивные зависимости |
| `NoSuchMethodError` / linkage failure | Точный Kotlin/SDK ABI, старый SDK, bundled shared classes |
| Unsupported runtime/ABI | runtime ID, runtime range и точное ABI поле |
| Не проходит executable import | Publisher pin, P-256 key, signature envelope и raw manifest bytes |
| Не проходит health | Непустой offers inventory, реальные регистрации и generation |
| `ACCESS_DENIED` | Caller identity, exact contract/operation/provider grant и expiry |
| `PROVIDER_UNAVAILABLE` | Live activation, provider ID, отключение или смена поколения |
| Контракт есть, tool не виден ИИ | Нужна отдельная интеграция product tool catalogue |
| Storage denied | Проверенная installer identity, актуальное поколение и host storage policy |
| UI action denied | Grant package caller; confirmation dialog его не создаёт |
| `cancel/respond` unsupported | Использовать конкретные task-контракт operations |
| После таймаута неизвестен эффект | Проверить внешний outcome; не повторять WRITE автоматически |

### Что уже есть для проверки SDK

[examples/open-contract](../examples/open-contract/README.md) — JVM-пример host/publisher/provider/consumer, проверяющий discovery, grants, schemas, versions, withdrawal и replacement generations. Он использует source modules и не загружает Android package.

[examples/independent-plugin](../examples/independent-plugin/README.md) — независимая компиляция против Maven artifact. CI копирует проект за пределы checkout и компилирует его отдельно.

Успешная JVM-компиляция не подтверждает D8 conversion, подпись, Android installation, release R8 linkage, lifecycle на устройстве и semantic correctness operations. В рамках подготовки этого документа сборки, device acceptance и установка примеров не выполнялись; изложенные гарантии сверены с исходниками.

## 19. Карта исходников

| Область | Источник |
|---|---|
| Protocol dependencies/publication | [protocol/build.gradle.kts](../platform/protocol/build.gradle.kts) |
| Основные SDK interfaces | [Runtime.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/Runtime.kt) |
| Contracts/providers/operations | [Descriptors.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/Descriptors.kt) |
| Requests/results/errors/resources | [Messages.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/Messages.kt) |
| KV API | [Storage.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/Storage.kt) |
| Версии | [Version.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/Version.kt) |
| JSON Schema | [JsonSchema.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/JsonSchema.kt) |
| Runtime enforcement | [CoreRuntime.kt](../platform/runtime/src/main/kotlin/org/amalia/platform/runtime/CoreRuntime.kt) |
| Runtime limits/grants | [RuntimePolicy.kt](../platform/runtime/src/main/kotlin/org/amalia/platform/runtime/RuntimePolicy.kt) |
| Package metadata parser | [PackageMetadata.kt](../platform/runtime/src/main/kotlin/tech/amalia/platform/runtime/packages/PackageMetadata.kt) |
| Package limits | [PackageModel.kt](../platform/runtime/src/main/kotlin/tech/amalia/platform/runtime/packages/PackageModel.kt) |
| Package verification/migrations | [packages/README.md](../platform/runtime/src/main/kotlin/tech/amalia/platform/runtime/packages/README.md) |
| Android loading | [AndroidDexRuntimeLoader.kt](../app/src/main/java/com/my/amali/platform/loading/AndroidDexRuntimeLoader.kt) |
| Android host/storage/ABI | [PlatformHost.kt](../app/src/main/java/com/my/amali/platform/PlatformHost.kt) |
| Упаковка/подпись | [PackagePlatformPlugin.py](../tools/PackagePlatformPlugin.py) |
| Пример автора | [ClockPlugin.kt](../examples/independent-plugin/src/main/kotlin/org/example/independent/ClockPlugin.kt) |
| UI SDK | [UiDocument.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/ui/UiDocument.kt) |
| Studio SDK | [StudioContracts.kt](../platform/protocol/src/main/kotlin/org/amalia/platform/protocol/ui/StudioContracts.kt) |
| Declaration loader | [DeclarationRuntimeLoader.kt](../app/src/main/java/com/my/amali/platform/presentation/DeclarationRuntimeLoader.kt) |
| UI renderers | [UiRenderer.kt](../app/src/main/java/com/my/amali/platform/presentation/UiRenderer.kt) |
| UI templates | [UiComponentTemplates.kt](../app/src/main/java/com/my/amali/platform/presentation/UiComponentTemplates.kt) |
| Product roles | [builtin/README.md](../app/src/main/java/com/my/amali/platform/builtin/README.md) |
| Agent strategy | [agentroles/README.md](../app/src/main/java/com/my/amali/platform/agentroles/README.md) |
| Resources | [resources/README.md](../platform/runtime/src/main/kotlin/org/amalia/platform/runtime/resources/README.md) |
| Remote host | [remote/README.md](../app/src/main/java/com/my/amali/platform/remote/README.md) |
| Tasks integration | [tasks/README.md](../app/src/main/java/com/my/amali/platform/tasks/README.md) |
| Package consent/recovery | [management/README.md](../app/src/main/java/com/my/amali/platform/management/README.md) |
| Legacy contributors | [ExtensionPoints.kt](../app/src/main/java/com/my/amali/plugin/ExtensionPoints.kt) |
| Реальная composition | [ServiceLocator.kt](../app/src/main/java/com/my/amali/core/di/ServiceLocator.kt) |

При расхождении старого README и реализации ориентируйтесь на текущий parser, loader и host composition. В частности, некоторые старые README всё ещё упоминают отсутствие уже добавленных migrations или default 30-секундный host timeout: выше отражено текущее состояние кода.
