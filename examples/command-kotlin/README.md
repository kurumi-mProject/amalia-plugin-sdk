# Example: a voice command handled before the model (Kotlin)

```
command-kotlin/
├── plugin.json
└── src/com/example/amalia/quicknotes/
    ├── QuickNotesPlugin.kt     ← AmaliaPlugin + CommandContributor + CapabilityContributor
    └── NotesStore.kt           ← the plugin's own storage
```

## What it does

- «запиши мысль купить хлеб» → appends a note, replies «Записала».
- «мои заметки» → speaks the last note.
- Anything else → `CommandResult.NotMine`, the assistant answers as usual.

## Why `CommandContributor` and not a tool

A tool is called by the model, which means a network round trip. For "write this
down" that round trip is the whole problem — the thought is gone by the time the
answer comes back. `CommandContributor` runs **locally, synchronously, before the
LLM**.

## The golden rule of interception

> Every phrase you capture is a phrase the user can no longer say to Amalia.

This example intercepts exactly two prefixes and three read phrases. Resist
"helpfully" widening that list: capturing `запиши` alone would swallow
"запиши мне напоминание на 7 утра" — a request the assistant handles better than
you can.

Signals that your interception is too broad:

- Users repeat themselves and then rephrase — the command ate their sentence.
- You find yourself writing regexes to *exclude* cases.
- Your `onPhrase` handles more than one "family" of intent.

## Returning a reply

```kotlin
CommandResult.Handled(spoken = "Записала")   // spoken out loud
CommandResult.Handled(spoken = null)         // do it silently ("stop")
CommandResult.NotMine                        // not ours
```

If your handler throws, the host logs it and falls through to the model — a
broken plugin never costs the user an answer. Still, don't rely on that: return
`NotMine` for anything you are not sure about.

## Build and package

```bash
# 1. compile (any Kotlin/Java toolchain) then convert to dex
./gradlew jar
$ANDROID_HOME/build-tools/34.0.0/d8 --min-api 26 --output out/ build/libs/quicknotes.jar

# 2. package
cp out/classes.dex .
zip -r ../quicknotes.amaliaplug plugin.json classes.dex
```

Detail: [docs/packaging.md](../../docs/packaging.md).

## Install

**Settings → Plugins → Install from file**, then enable it and say
«запиши мысль проверить плагин».
