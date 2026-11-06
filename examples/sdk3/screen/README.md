# screen — SDK facade reference

Registers a real declarative host-rendered screen. The JVM console records its document; Android navigation renders it using Amalia tokens.

From the repository root, after publishing the SDK Maven repository:

```bash
./tools/amalia dev examples/sdk3/screen \
  --config examples/sdk3/screen/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$PWD/platform/build/sdk-repository"
```

Paste these console commands (also in `commands.txt`):

```text
invoke presentation {}
```

`dev.json` deliberately grants only this in-memory fake-host session. It never authorizes the Android host.
No tokens or private keys belong in these fixtures. Storage is bounded, volatile and unencrypted.

Compile/package with the repository CLI and your Gradle installation/wrapper; see `docs/SDK_CLI.md`.
Capabilities require separate signed review and explicit host grants.
