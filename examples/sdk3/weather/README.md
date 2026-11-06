# weather — SDK facade reference

Fetch a Berlin forecast from an exact declared HTTPS origin; fake response supplied for offline use.

From the repository root, after publishing the SDK Maven repository:

```bash
./tools/amalia dev examples/sdk3/weather \
  --config examples/sdk3/weather/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$PWD/platform/build/sdk-repository"
```

Paste these console commands (also in `commands.txt`):

```text
invoke forecast {}
```

`dev.json` deliberately grants only this in-memory fake-host session. It never authorizes the Android host.
No tokens or private keys belong in these fixtures. Storage is bounded, volatile and unencrypted.

Compile/package with the repository CLI and your Gradle installation/wrapper; see `docs/SDK_CLI.md`.
Optional capability objects are author declarations only and do not create SDK implementations.
