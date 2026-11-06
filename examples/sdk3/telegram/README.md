# telegram — SDK facade reference

Persist a Telegram message draft and return a send intent. Never sends messages, stores tokens or claims OAuth support.

From the repository root, after publishing the SDK Maven repository:

```bash
./tools/amalia dev examples/sdk3/telegram \
  --config examples/sdk3/telegram/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$PWD/platform/build/sdk-repository"
```

Paste these console commands (also in `commands.txt`):

```text
invoke draft {"chat": "saved-messages", "text": "Remember the meeting"}
```

`dev.json` deliberately grants only this in-memory fake-host session. It never authorizes the Android host.
No tokens or private keys belong in these fixtures. Storage is bounded, volatile and unencrypted.

Compile/package with the repository CLI and your Gradle installation/wrapper; see `docs/SDK_CLI.md`.
Optional capability objects are author declarations only and do not create SDK implementations.
