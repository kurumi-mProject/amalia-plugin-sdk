# smarthome — SDK facade reference

Persist a bounded light brightness intent for a named room. No device commands or network mutations are issued.

From the repository root, after publishing the SDK Maven repository:

```bash
./tools/amalia dev examples/sdk3/smarthome \
  --config examples/sdk3/smarthome/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$PWD/platform/build/sdk-repository"
```

Paste these console commands (also in `commands.txt`):

```text
invoke light {"room": "study", "brightness": "40"}
```

`dev.json` deliberately grants only this in-memory fake-host session. It never authorizes the Android host.
No tokens or private keys belong in these fixtures. Storage is bounded, volatile and unencrypted.

Compile/package with the repository CLI and your Gradle installation/wrapper; see `docs/SDK_CLI.md`.
Optional capability objects are author declarations only and do not create SDK implementations.
