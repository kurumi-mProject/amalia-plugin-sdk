# music — SDK facade reference

Host-owned direct audio playback through MediaApi. The fake console records state without sound; Android uses MediaSession and a foreground service.

From the repository root, after publishing the SDK Maven repository:

```bash
./tools/amalia dev examples/sdk3/music \
  --config examples/sdk3/music/dev.json \
  --gradle-arg=-PamaliaSdkRepository="$PWD/platform/build/sdk-repository"
```

Paste these console commands (also in `commands.txt`):

```text
invoke select {"url": "https://archive.org/download/testmp3testfile/mpthreetest.mp3", "title": "Test track"}
```

`dev.json` deliberately grants only this in-memory fake-host session. It never authorizes the Android host.
No tokens or private keys belong in these fixtures. Storage is bounded, volatile and unencrypted.

Compile/package with the repository CLI and your Gradle installation/wrapper; see `docs/SDK_CLI.md`.
Capabilities require separate signed review and explicit host grants.
