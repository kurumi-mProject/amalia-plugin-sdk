# Example: voice reminders (Kotlin, no UI)

A plugin that **adds a new ability** to Amalia without touching the interface:
"I'll remind you."

```
reminder-kotlin/
├── plugin.json
└── src/com/example/amalia/reminder/
    ├── ReminderPlugin.kt     ← commands + background + notification + actions
    ├── ReminderParser.kt     ← "через 20 минут", "в 18:30", with a word-number table
    ├── ReminderStore.kt      ← atomic file storage that survives process death
    └── Strings.kt            ← the plugin's own ru/en texts
```

## What it adds

| You say | What happens |
|---|---|
| «напомни через 20 минут выключить чай» | a reminder appears in an ongoing notification: **«выключить чай · 17 мин»** |
| «напомни в 18:30 позвонить маме» | same, at 18:30 today — or tomorrow if 18:30 already passed |
| «напомни через полчаса …» | word numbers work |
| «мои напоминания» | speaks the count and the nearest one |
| «убери напоминания» | clears them |
| tap **Готово** / **Отложить** | dismisses it, or moves it 10 minutes later |

Anything without the `напомни` / `remind me` prefix is left alone —
`CommandResult.NotMine` — and the assistant answers as usual.

## How it works, and why not `AlarmManager`

The obvious implementation is `AlarmManager`. It is also impossible from a
plugin, and it is worth understanding why:

- a **precise** alarm needs `SCHEDULE_EXACT_ALARM`, and a plugin cannot add
  manifest permissions;
- delivering the alarm needs a `BroadcastReceiver` **declared in the manifest**,
  and a plugin cannot add manifest entries either.

So this plugin uses the mechanism the host already provides:
**`BackgroundContributor`**. While it reports `Active`, the host keeps a
foreground service alive and polls the plugin every ~2 seconds. The process
stays up, and the plugin only has to answer "what do I have right now?".

That means:

- **no permissions for alarms**, no manifest, no boom;
- accuracy of about two seconds — irrelevant for a human reminder;
- the ongoing notification *is* the reminder, which is honest: while it is
  visible, the reminder is armed.

## What this plugin cannot do (honest limits)

1. **Speak the reminder out loud.** `HostServices` exposes `stopSpeaking()` and
   `canNotify()` — but no `speak(text)`. The plugin can show a notification; it
   cannot make Amalia say "time to take the tea off". This is the single most
   valuable addition the SDK could make, and it is listed in
   [docs/security.md](../../docs/security.md) as a planned API.
2. **Ship its own strings.** `.amaliaplug` packages cannot add Android
   resources, so every text here is in `Strings.kt` as a ru/en table, and the
   notification uses ready-made strings (`title`, `text`, `label`) instead of
   resource ids.
3. **Open a screen from the notification.** There is no content-intent API for
   plugins; notifications carry action buttons only.

## Build

```bash
# 1. compile to a jar
./gradlew jar

# 2. jar → dex
$ANDROID_HOME/build-tools/34.0.0/d8 \
    --min-api 26 --output out/ build/libs/reminder.jar
cp out/classes.dex .

# 3. validate and package (from the SDK root)
../../tools/pack.sh .
```

## Install and try

1. **Settings → Plugins → Install from file** → `reminder.amaliaplug`.
2. Enable it.
3. Say: «напомни через 2 минуты проверить напоминания».
4. Pull down the notification shade — the reminder is there with two buttons.

## Try changing it

- **Make it smarter about time.** Add "после обеда" → `NotOurs`, and let the
  model handle it. Try adding it yourself and see how quickly guessing goes
  wrong — that is exactly why the parser refuses.
- **Add a repeat.** A daily reminder is `dueAtMillis += 86_400_000` in
  `onAction` instead of `remove`.
- **Add a second kind of storage.** `ReminderStore` is deliberately dumb; swap
  it for `SharedPreferences` or SQLite and nothing else changes.
