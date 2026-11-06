# SDK facade reference projects

Eight standalone Kotlin/JVM author projects, with one `amalia.json` per project.
They use only the existing `AmaliaPlugin`, tool DSL, package storage and bounded GET facade.
No Android imports. Music/reminders/screen use host facades; Telegram delivery, smart-home hardware and local inference are not implemented.

| Project | Implemented behavior |
| --- | --- |
| weather | Exact-origin weather GET and structured forecast; offline fixture available |
| notes | Named note save/read/delete |
| telegram | Persist/read message draft and explicit unexecuted send intent |
| reminders | Host one-shot schedule and notification operation |
| music | Host direct-audio player and playback state |
| smarthome | Validate brightness, persist/read lighting intent |
| local-ai | Validate token budget, persist/read inference request intent |
| screen | Register a host-rendered UiDocument |

Each project has `dev.json` with explicit fake-host grants and `commands.txt` with concrete
console requests. Run `amalia dev` as described in its README; input can also be piped from
`commands.txt`. Build against freshly published artifacts from this checkout, not an older
snapshot. Declarations grant no permissions. These are trusted local JVM programs, not a sandbox.
