# Independent plugin author project

This build depends on the Maven artifact `org.amalia.platform:amalia-protocol` only.
It does not include app/platform source projects or require an Android SDK to compile
its JAR. `ClockPlugin` is a public zero-argument entrypoint implementing a new contract.
Its publisher must be `org.example.independent`, matching its contract namespace.

The host repository produces a distributable Maven repository:

```sh
./gradlew -p platform :protocol:publishSdkPublicationToSdkDistributionRepository :runtime:publishSdkPublicationToSdkDistributionRepository
```

Download/extract the `amalia-sdk-repository` CI artifact, copy this project anywhere,
and build with your Gradle 9.7.1 installation:

```sh
gradle -p /path/to/copied-project jar -PamaliaSdkRepository=/path/to/sdk-repository -PamaliaSdkVersion=0.2.0-SNAPSHOT
```

CI performs this compile from a temporary copy outside the application checkout. No
publication to a public Maven service is implied; the repository artifact includes
POMs, JARs and sources and can be hosted on your chosen Maven server.

For the current Android single-DEX loader, use Android SDK D8 with the host's exact
protocol/Kotlin/coroutine libraries as classpath, not program inputs. For example,
from the repository root after compiling this project:

```sh
mkdir -p examples/independent-plugin/build/dex
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 26 --output examples/independent-plugin/build/dex examples/independent-plugin/build/libs/independent-amalia-plugin.jar
```

The build-tools path is an example: select the D8 version installed with your SDK.
Provide additional `--classpath` entries if your D8 version needs SDK/shared-library
resolution. DEX 035/037, a single classes.dex and no bundled shared API definitions
are required by the current loader.

Create your P-256 publisher key, keep the private key outside the package, and export
its public SPKI DER for explicit host trust provisioning. Package the DEX using:

```sh
python3 tools/PackagePlatformPlugin.py --dex examples/independent-plugin/build/dex/classes.dex --package-id org.example.independent.clock --publisher org.example.independent --version 1.0.0 --entrypoint org.example.independent.ClockPlugin --key-id author-1 --offers examples/independent-plugin/offers.json --private-key /private/author.pem --output clock.amaliaplug
```

The tool signs exact manifest bytes with OpenSSL and inventories the DEX. Host
verification enforces the P-256 pin. Import, activation approval, provider selection
and operation grants are separate actions. Clock calls have no default permissions.
No device installation or scenario acceptance is claimed by successful JAR compilation.
