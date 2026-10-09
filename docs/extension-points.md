# Extension points — full reference

Implement `AmaliaPlugin` and any number of the interfaces below. Each interface
is one kind of contribution, and every one is only honoured while the plugin is
**enabled** — disable it and the contributions vanish from the registries
instantly, with no restart.

```kotlin
interface AmaliaPlugin {
    val metadata: PluginMetadata
    fun onLoaded(context: PluginContext) {}   // once, on every launch
    fun onEnabled() {}                        // after the user enables it
    fun onDisabled() {}                       // after the user disables it
    fun onUnloaded() {}                       // plugin removed / replaced
}
```

| Interface | Contribution |
|---|---|
| `ThemeContributor` | Themes → the appearance gallery |
| `EngineContributor` | STT / LLM / TTS engines → the pipeline |
| `ToolContributor` | Tools the model can call |
| `PhraseContributor` | Greeting phrases |
| `PromptContributor` | Character blocks in the system prompt |
| `CapabilityContributor` | Declares which capabilities you need |
| `CommandContributor` | Voice commands handled **before** the model |
| `ScreenContributor` | Your own screen inside the app |
| `NotificationContributor` | Notifications with action buttons |
| `BackgroundContributor` | Long-running state → foreground service |
| `AudioSessionContributor` | Ducking other audio while Amalia speaks |
| `WidgetContributor` | Replace a home-screen visual slot |

---

## `PluginContext` and `HostServices`

The only environment a plugin gets. There is no access to app singletons.

```kotlin
interface PluginContext {
    val appContext: Context
    val hostVersion: String
    fun isEnabled(pluginId: String): Boolean
    val services: HostServices
}

interface HostServices {
    fun stopSpeaking()   // interrupt Amalia mid-sentence
    fun canNotify(): Boolean
}
```

That is deliberately tiny: a plugin can make Amalia stop talking and can ask
whether notifications are possible. Nothing else about the host is reachable.

---

## Themes

```kotlin
interface ThemeContributor {
    fun themes(): List<AmaliaThemeDefinition>
}
```

Usually you don't implement this by hand — a declarative theme in `plugin.json`
is converted into exactly this. Implement it only if you need a theme the JSON
cannot express.

---

## Engines

```kotlin
interface EngineContributor {
    fun engines(): List<EngineSlot<*>>
}
```

An engine slot is a factory for an STT, LLM or TTS implementation. The first
plugin to register a given slot id wins, which is how the built-in engines can
be replaced or wrapped.

---

## Tools

```kotlin
interface ToolContributor {
    fun tools(context: PluginContext): List<AmaliaTool>
}
```

A tool is a name, a JSON schema, and a handler. The model sees the schema and
calls the name; your handler runs and returns a JSON string for the model to
read. **Never invent tool names that collide** with another plugin's — the
registry keeps the first and drops the rest.

---

## Phrases

```kotlin
interface PhraseContributor {
    fun greetingPhrases(languageTag: String): List<String>
}
```

Called with the current language tag. Return an empty list if you have nothing
for that language — the host falls back to the built-in greetings.

---

## Character (system prompt)

```kotlin
interface PromptContributor {
    fun contribute(builder: PromptBuilder)
}
```

`PromptBuilder.block(text)` adds a free-form paragraph, `rule(text)` adds a
behavioural rule. Keep it short: this text is sent with every request.

> The JSON answer contract of the model is protected by the host. Prompt text
> that tells the model to break that contract will be fought by the host's own
> instructions — don't rely on it.

---

## Capabilities

```kotlin
interface CapabilityContributor {
    fun requiredCapabilities(): List<String>
}
```

Declares what your plugin needs, e.g. `audio.music`, `device.flashlight`,
`notifications.actions`. **Declaring does not grant** — the host shows the
permission screen and the user decides. If a capability isn't granted, mark your
feature unavailable instead of failing. See [capabilities.md](capabilities.md).

---

## Commands (before the model)

```kotlin
interface CommandContributor {
    suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult
}

sealed interface CommandResult {
    data class Handled(val spoken: String? = null) : CommandResult
    data object NotMine : CommandResult
}
```

This is the fastest path in the app: the phrase never reaches the network.

```kotlin
override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
    if (normalize(phrase) != "пауза") return CommandResult.NotMine
    player.pause()
    return CommandResult.Handled(spoken = "Поставила на паузу")
}
```

**Then the golden rule:** match a *narrow, exact* set of phrases. Every extra
word you capture is a sentence the user can no longer say to the assistant.

Contributors are asked in registration order; the first `Handled` wins. Return
`Handled(spoken = null)` for a command that needs no reply (like "stop").

---

## Screens

```kotlin
interface ScreenContributor {
    fun screens(): List<ScreenSpec>
}

data class ScreenSpec(
    val route: String,
    val titleRes: Int,
    val inNavigation: Boolean = false,
    val iconName: String = "",
    val content: @Composable (ScreenHost) -> Unit,
)

interface ScreenHost {
    fun goBack()
    fun navigate(route: String)
}
```

The screen receives `ScreenHost`, **not** a `NavController` — the navigation
graph stays in the core. Routes that collide with core routes are skipped.

`inNavigation = true` asks for a slot in the floating bottom bar. The bar is
built for exactly three core tabs and never gives them up; plugin tabs come
after them, at most two, and the pills shrink to fit. Extra screens stay
reachable by route.

---

## Notifications

```kotlin
interface NotificationContributor {
    suspend fun post(host: NotificationHost)
}

interface NotificationHost {
    val channelId: String
    fun notify(id: String, spec: NotificationSpec)
    fun cancel(id: String)
}

data class NotificationSpec(
    val titleRes: Int,
    val textRes: Int,
    val onGoing: Boolean = false,
    val actions: List<ActionSpec> = emptyList(),
    val title: String? = null,   // ready-made text, overrides titleRes
    val text: String? = null,    // ready-made text, overrides textRes
)

data class ActionSpec(
    val id: String,
    val iconRes: Int,
    val titleRes: Int,
    val label: String? = null,   // ready-made caption, overrides titleRes
)

interface NotificationActionHandler {
    suspend fun onAction(actionId: String)
}
```

The host owns the notification channel — plugins never create one, so the user
can manage everything in one place.

Action ids are namespaced by the host (`your.plugin.id#pause`) when the
notification is shown and routed back to *your* handler when tapped. Taps work
**after the process was killed**: the broadcast receiver brings the app back up
and then calls `onAction`.

### Dynamic text

A `.amaliaplug` cannot add Android string resources, so `*Res` fields must point
at **host** resources. That is fine for fixed labels, and useless for anything
that changes — a countdown, a track title, a remaining time.

For those, pass the finished string and set the resource id to `0`:

```kotlin
host.notify("timer", NotificationSpec(
    titleRes = 0,
    textRes = 0,
    title = "Take the tea off",
    text = "17 min left",
    actions = listOf(ActionSpec("done", android.R.drawable.checkbox_on_background, 0, label = "Done")),
))
```

The host prefers your string when it is not blank. Localize it yourself — see
[localization.md](localization.md).

---

## Background state

```kotlin
interface BackgroundContributor {
    suspend fun state(): BackgroundState
}

sealed interface BackgroundState {
    data object Idle : BackgroundState
    data class Active(
        val titleRes: Int,
        val textRes: Int,
        val actions: List<ActionSpec> = emptyList(),
        val title: String? = null,   // ready-made text, overrides titleRes
        val text: String? = null,    // ready-made text, overrides textRes
    ) : BackgroundState
}
```

While at least one enabled plugin reports `Active`, the host keeps a foreground
service alive and shows an ongoing notification. When everybody is `Idle`, the
service stops itself — no leftover notification.

`state()` is polled (every ~2 s), so keep it cheap and non-blocking.

This is also the **only reliable way for a plugin to stay alive**. An
`AlarmManager` alarm would need a manifest-declared receiver and the exact-alarm
permission, neither of which a plugin can obtain. While you report `Active`, the
host holds a foreground service for you — so "wake up later and do something" is
implemented by answering `state()` correctly, not by scheduling.

---

## Audio sessions (ducking)

```kotlin
interface AudioSessionContributor {
    fun session(host: AudioSessionHost): AudioSessionHandler
}

interface AudioSessionHost { val isSpeaking: Boolean }

interface AudioSessionHandler {
    fun onAssistantSpeaking(duckVolume: Float = 0.25f)
    fun onAssistantDone()
    fun release()
}
```

The classic scenario: music fades while Amalia talks, then comes back.

```kotlin
override fun session(host: AudioSessionHost) = object : AudioSessionHandler {
    override fun onAssistantSpeaking(duckVolume: Float) { player.setVolume(duckVolume) }
    override fun onAssistantDone() { player.setVolume(1f) }
    override fun release() { /* release anything you held */ }
}
```

Sessions are created when your plugin becomes enabled and `release()` is called
when it is disabled — always clean up there.

---

## Home-screen widgets

```kotlin
interface WidgetContributor {
    fun widgets(): List<WidgetSpec>
}

enum class WidgetSlot { HERO_VISUAL, LAMP, GREETING }

data class WidgetSpec(
    val id: String,
    val slot: WidgetSlot,
    val content: @Composable (WidgetContext) -> Unit,
)

data class WidgetContext(
    val voiceState: VoiceState,
    val audioLevel: Float,
    val lightLabelRes: Int,
    val cct: Int,
    val breath: Float,
    val compact: Boolean,
    val onPrimaryAction: () -> Unit,
)
```

A slot is a fixed place in the home-screen composition, not a free canvas. Your
widget occupies exactly that place and receives prepared state — it cannot move
the cards, break the spring between the hero and the dialogue, or make the
screen jump when it is enabled.

If no plugin takes a slot, the host draws its own default widget. That means
**disabling your plugin always restores the original screen** — a plugin can
never leave the home screen broken.

The motif layer is intentionally **not** a slot: the background belongs to the
theme (`PaletteSpec.motif`), because "glass, light, air" is the product.

---

## Order of resolution

```
is the plugin enabled?
   ├─ yes → its contributions are live
   │        ├─ requiredCapabilities()   → permission screen
   │        ├─ commandContributors()    → phrase interception
   │        ├─ screens()/widgets()      → navigation and home screen
   │        ├─ notificationContributors() → notifications
   │        ├─ backgroundContributors() → foreground service
   │        └─ audioSessionContributors() → ducking
   └─ no  → contributions leave the registries, plugin stays listed
```
