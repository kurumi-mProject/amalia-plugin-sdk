> **Legacy API reference.** For SDK3 use [SDK_CLI.md](SDK_CLI.md) and [migration](MIGRATION_V3.md). This document describes the retained v2 loader.

# Amalia Extension SDK — что можно построить и как не сломать

Этот документ — для того, кто добавляет в Амалию **новое поведение**:
команду, экран, уведомление, фоновую фичу, управление звуком. Он отвечает
на три вопроса: **что** доступно, **как** этим пользоваться и **что нельзя**
трогать.

Основным функционалом Амалии управляет **ИИ**. Расширение не заменяет
ассистента — оно добавляет ему рук, ушей и экранов.

---

## 1. Три кита расширений

| Понятие | Что это | Где живёт |
|---|---|---|
| **Возможность** (`Capability`) | Что система **разрешает** делать | `capability/CapabilityRegistry.kt` |
| **Особый доступ** (`SpecialAccess`) | Согласие через системный экран | `capability/SpecialAccess.kt` |
| **Расширение** (`Contributor`) | Новое поведение на этих возможностях | `plugin/ExtensionPoints.kt` |

Расширение объявляет, какие **возможности** ему нужны. Хост проверяет
разрешения и, если их нет, помечает фичу **«недоступна»** — а не роняет
приложение.

---

## 2. Каталог возможностей (51)

Возможность — это сценарий на человеческом языке плюс нужные разрешения.
Полный список — `CapabilityRegistry.all`. Группы:

| Группа | Примеры |
|---|---|
| `AUDIO` | слушать, играть, приглушать во время речи, запись системного звука |
| `CAPTURE` | фото, видео, запись экрана, датчики движения и тела |
| `COMMS` | звонки, SMS, контакты, журнал звонков, ответ на вызов |
| `MEDIA` | аудиотека, видеотека, галерея |
| `FILES` | доступ к файлам, установка приложений |
| `DEVICE` | Wi-Fi, Bluetooth, яркость, фонарик, батарея, NFC |
| `TIME` | часы, будильники (в т.ч. точные), таймеры, календарь |
| `NOTIFICATIONS` | показ, кнопки, чтение чужих уведомлений |
| `LOCATION` | текущее местоположение |
| `SYSTEM` | запуск приложений, настройки, веб, фон, оверлей, статистика |

Каждая возможность говорит, **есть ли под неё готовый инструмент** ядра
(`Capability.tools`). Пустой список — возможность объявлена, но инструмента
нет: расширение может реализовать её само.

---

## 3. Особые доступы (12)

Не разрешения, а отдельные согласия в системных настройках. Дают то, чего
разрешения физически не могут:

`accessibility` · `notification_listener` · `overlay` · `usage_stats` ·
`exact_alarm` · `write_settings` · `do_not_disturb` · `battery_optimization` ·
`install_packages` · `media_projection` · `device_admin` · `modify_phone_state`

У каждого — описание и **предупреждение о риске** (`SpecialAccess.riskRes`).
Если риск задан, хост обязан показать его пользователю до выдачи.

Полный список — `SpecialAccessRegistry.all`.

---

## 4. Контракты расширений

Плагин реализует `AmaliaPlugin` плюс один или несколько интерфейсов:

### 4.1 `CapabilityContributor` — что мне нужно
```kotlin
override fun requiredCapabilities(): List<String> =
    listOf("audio.music", "notifications.actions", "system.background")
```

### 4.2 `CommandContributor` — команда до модели
Срабатывает **раньше** LLM. Узкий класс фраз: «стоп», «громче», «следующий».
```kotlin
override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
    if (phrase.trim() != "следующий трек") return CommandResult.NotMine
    player.next()
    return CommandResult.Handled(spoken = "Играю дальше")
}
```
**Правило:** не перехватывай всё. Неверный перехват отнимает у ассистента ответ.

### 4.3 `ScreenContributor` — свой экран
```kotlin
override fun screens(): List<ScreenSpec> = listOf(
    ScreenSpec(
        route = "amalia://screen/player",
        titleRes = R.string.player_title,
        inNavigation = true,
        content = { host -> PlayerScreen(host) },
    ),
)
```
Экран получает **`ScreenHost`** (только `goBack()` и `navigate()`), а не
`NavController`. Навигационный граф остаётся в ядре.

### 4.4 `NotificationContributor` — уведомление с кнопками
```kotlin
override suspend fun post(host: NotificationHost) {
    host.notify("playback", NotificationSpec(
        titleRes = R.string.now_playing,
        textRes = R.string.track_name,
        onGoing = true,
        actions = listOf(
            ActionSpec("pause", R.drawable.ic_pause, R.string.pause),
            ActionSpec("next", R.drawable.ic_next, R.string.next),
        ),
    ))
}
```
Кнопки приходят обратно в `NotificationActionHandler.onAction(id)` — даже
если процесс был пересоздан.

### 4.5 `BackgroundContributor` — фоновое состояние
```kotlin
override suspend fun state(): BackgroundState =
    if (player.isPlaying)
        BackgroundState.Active(
            titleRes = R.string.music_playing,
            textRes = R.string.track_name,
            actions = listOf(ActionSpec("pause", R.drawable.ic_pause, R.string.pause)),
        )
    else BackgroundState.Idle
```
Хост держит foreground-сервис, пока хоть одна фича `Active`, и гасит его,
когда все замолчали. **Лишнего уведомления не будет.**

### 4.6 `AudioSessionContributor` — приглушение во время речи
```kotlin
override fun session(host: AudioSessionHost) = object : AudioSessionHandler {
    override fun onAssistantSpeaking(duckVolume: Float) { player.setVolume(duckVolume) }
    override fun onAssistantDone() { player.setVolume(1f) }
    override fun release() { player.stop() }
}
```
Это тот самый сценарий «музыка стихает, когда говорит Амалия».

---

## 5. Что нельзя (граница доверия)

1. **Переписывать навигацию ядра.** Расширение добавляет экраны, но не
   перекраивает `AmaliaNavHost`.
2. **Трогать системные синглтоны напрямую.** Всё — через `PluginContext`
   и фасады (`ScreenHost`, `NotificationHost`, `AudioSessionHost`).
3. **Требовать разрешение вместо пользователя.** Расширение объявляет
   потребность; запрашивает хост, показывая объяснение и риск.
4. **Заменять конвейер.** Расширение не подменяет STT/LLM/TTS — это делают
   `EngineContributor`. Расширения **дополняют**, а не замещают.
5. **Ломать формат ответа модели.** JSON-контракт промпта защищён ядром.

---

## 6. Как хост ведёт расширения

```
включён ли плагин?
   └─ да → его вклады активны
          ├─ capabilities()        → питают экран разрешений
          ├─ commandContributors() → перехват фраз до модели
          ├─ screens()             → NavHost
          ├─ notificationContributors() → уведомления
          ├─ backgroundContributors()   → foreground-сервис
          └─ audioSessionContributors() → приглушение при речи
   └─ нет → вклады уходят из реестров, плагин остаётся в списке
```

Агрегаторы живут в `PluginManager`: `requiredCapabilities()`,
`commandContributors()`, `screens()`, `notificationContributors()`,
`backgroundContributors()`, `audioSessionContributors()`.

---

## 7. Пример: плеер целиком

1. `CapabilityContributor` → `["audio.music", "notifications.actions", "system.background", "audio.duck"]`
2. `ToolContributor` → инструменты `play_track`, `pause`, `next` для модели.
3. `CommandContributor` → «стоп», «пауза», «дальше» срабатывают мгновенно.
4. `NotificationContributor` → уведомление «играет» с кнопками.
5. `BackgroundContributor` → состояние `Active`, пока играет.
6. `AudioSessionContributor` → приглушение, когда говорит Амалия.
7. `ScreenContributor` → полный экран плеера с очередью.

Это и есть «сделать как плеер»: расширение живёт как настоящая фича
приложения, а ассистент остаётся мозгом.

---

## 8. Текущее состояние скелета

| Слой | Готово |
|---|---|
| Каталог возможностей | ✅ 51 |
| Особые доступы | ✅ 12 |
| `AmaliaPermission` | ✅ 42 |
| Манифест | ✅ все разрешения объявлены |
| Экран разрешений | ✅ показывает «что можно построить» |
| Контракты расширений | ✅ 16 интерфейсов |
| Агрегаторы в `PluginManager` | ✅ |
| **Движок исполнения** (Command/Screen/Notification в рантайме) | ⏳ следующий шаг |
| Локали (переводы 2-й волны) | ⏳ en-fallback, ждут перевода |

Скелет — это словарь и контракт. Движок, который **исполняет** команды,
рисует экраны и держит фоновые сервисы, — следующий слой.
