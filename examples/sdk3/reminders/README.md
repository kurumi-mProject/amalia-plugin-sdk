# reminders — SDK facade reference

Schedules an inexact one-shot operation through the host scheduler. Delivery requires the same live authorized activation; no process resurrection is promised.

From the repository root, after publishing the SDK Maven repository:

```bash
./tools/amalia dev examples/sdk3/reminders \
  --config examples/sdk3/reminders/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$PWD/platform/build/sdk-repository"
```

Paste these console commands (also in `commands.txt`):

```text
invoke prepare {"text": "Stretch", "at": "2030-01-01T12:00:00Z"}
invoke pending {}
```

`dev.json` deliberately grants only this in-memory fake-host session. It never authorizes the Android host.
No tokens or private keys belong in these fixtures. Storage is bounded, volatile and unencrypted.

Compile/package with the repository CLI and your Gradle installation/wrapper; see `docs/SDK_CLI.md`.
Capabilities require separate signed review and explicit host grants.
