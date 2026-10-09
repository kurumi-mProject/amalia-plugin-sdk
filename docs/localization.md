# Localization

Amalia ships in nine locales: **Russian** (default), English, Japanese, German,
Spanish, French, Chinese, Hindi, Arabic.

A plugin is not *required* to be translated, but anything a user reads should be.

---

## 1. Greeting phrases — built-in localization

`phrases` in `plugin.json` is keyed by language tag. This is the easy path and
needs no code:

```json
"phrases": {
  "ru": ["Неон зажёгся. Слушаю.", "Ночь молодая, я тоже."],
  "en": ["Neon is on. I'm listening.", "The night is young — so am I."],
  "ja": ["ネオン点灯。聞いてるよ。"],
  "de": ["Neon ist an. Ich höre."],
  "es": ["Neón encendido. Escucho."],
  "fr": ["Néon allumé. J'écoute."],
  "zh": ["霓虹亮了，我在听。"],
  "hi": ["नियॉन चालू है। सुन रही हूँ।"],
  "ar": ["النيون مضاء. أسمعك."]
}
```

**Fallback rules.** The host asks for the full tag first, then the prefix:

- device is `en-US` → `en-US` is looked up, then `en`;
- a language you didn't provide → the built-in greetings are used, no error.

So a plugin with only `ru` and `en` still works everywhere; it just doesn't add
anything new in the other seven locales.

**Write phrases like the character, not like a translator.** Amalia is an
18-year-old with dry humour. Literal translations of English idioms read wrong
in every language. Keep each phrase short — it is spoken aloud.

---

## 2. Text in a code plugin — do it yourself

This is the important limitation, stated plainly:

> **A `.amaliaplug` package cannot add Android string resources.** Resources are
> not merged into the app at install time. So `titleRes` / `textRes` in
> `ScreenSpec`, `NotificationSpec`, `ActionSpec` and `BackgroundState.Active`
> refer to **host** resources, not your own `R` class.

That leaves two honest options for code plugins that need their own text:

### a) Localize in code

Keep a map and pick by the current locale. `PluginContext.appContext` gives you
everything you need:

```kotlin
private fun tag(context: Context): String =
    context.resources.configuration.locales[0].language.lowercase()

private fun text(context: Context, ru: String, en: String): String =
    if (tag(context) == "ru") ru else en
```

For a screen:

```kotlin
override fun screens() = listOf(
    ScreenSpec(
        route = "amalia://screen/timer",
        titleRes = 0,                       // see note below
        inNavigation = true,
        content = { host ->
            val ctx = LocalContext.current
            Column { Text(text(ctx, "Таймер", "Timer")) }
        },
    ),
)
```

> `titleRes = 0` is accepted: the host falls back to a neutral title. Use it
> when you cannot point at a host string resource.

### b) Reuse host strings

When a concept already exists in Amalia ("Pause", "Cancel", "Settings"), prefer
the host's resource — it is already translated into all nine locales and stays
consistent with the rest of the UI.

---

## 3. Notifications and buttons — use the ready-made strings

`NotificationSpec` / `ActionSpec` / `BackgroundState.Active` take resource ids
(`titleRes`, `textRes`, `titleRes`) **and** ready-made strings (`title`, `text`,
`label`). The ready-made string always wins when it is not blank.

Two rules follow:

- **Fixed label, and the host already has that word?** Use the resource id
  (`R.string.common_cancel`) — it is already translated into nine locales.
- **Anything dynamic, or a word the host does not have?** Pass your own
  localized string in `label` / `title` / `text` and set the resource id to `0`.

```kotlin
ActionSpec(
    id = "snooze",
    iconRes = android.R.drawable.ic_menu_recent_history,
    titleRes = 0,
    label = if (ru) "Отложить" else "Snooze",
)
```

Resources cannot be shipped inside a `.amaliaplug` package, which is exactly why
these override fields exist. There are no other options: a plugin either reuses
a host string or brings its own.

---

## 4. Testing your localization

- Switch the app language in **Settings** (the app has its own language setting,
  independent of the system) and check your phrases.
- Check right-to-left: **Arabic** is RTL. Any screen you contribute should
  compose with start/end padding, not left/right, and mirror its layout.
- Long German or Hindi words: keep labels short enough not to wrap, or allow two
  lines in your own layouts.

---

## 5. Checklist

- [ ] `phrases` has at least `ru` and `en`.
- [ ] No hardcoded user-visible text in a language you can't maintain.
- [ ] RTL checked if you contribute a screen.
- [ ] Nothing in your UI is *only* distinguishable by colour.
- [ ] Your plugin `description` in `plugin.json` — decide on one language and be
      consistent (the store shows it as written).
