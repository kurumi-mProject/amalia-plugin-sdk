# Amalia SDK3 — Kurze Einführung

Eigenständiges Kotlin/JVM-SDK für Plugins des Amalia-Android-Hosts. SDK3 ist eine Autoren-Fassade über der bestehenden Protokoll-Laufzeit, kein neuer Loader und keine stabile ABI-Version 3.0.

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## Erste Schritte

Erforderlich: JDK 17, Gradle 9.7.1 und Python 3. JVM-Veröffentlichung und JAR-Kompilierung benötigen weder Android SDK noch App-Quellcode. Für signierte DEX-Pakete sind D8 aus den Android build-tools und OpenSSL nötig.

Im Stammverzeichnis dieses Repositorys:

```bash
gradle -p platform \
  :protocol:publishSdkPublicationToSdkDistributionRepository \
  :runtime:publishSdkPublicationToSdkDistributionRepository
export AMALIA_SDK_REPOSITORY="$PWD/platform/build/sdk-repository"
./tools/amalia create plugin weather
./tools/amalia add network weather --domain api.example.com
./tools/amalia validate weather
gradle -p weather jar -PamaliaSdkRepository="$AMALIA_SDK_REPOSITORY"
```

Das lokale Maven-Repository liegt unter `platform/build/sdk-repository`; weder ein öffentlicher Maven-Dienst noch `mavenLocal()` werden vorausgesetzt. Beim Kopieren das ganze Verzeichnis übernehmen und einen absoluten Pfad angeben. Die CLI nutzt den Projekt-Wrapper oder `gradle` im PATH; neue Projekte enthalten keinen Wrapper.

## Weitere Schritte und Grenzen

Die Befehle `test`, `dev`, `package` und Signaturoptionen stehen im CLI-Leitfaden. `amalia.json` ist das einzige Autorenmanifest; `GeneratedManifest` und `platform.json` werden generiert. Capability-Deklarationen erteilen keine Berechtigungen.

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

P-256-signierte Pakete enthalten `platform.json`, `package.sig`, `classes.dex`. Herausgebervertrauen, Aktivierungszustimmung und Aufrufberechtigungen sind separate Schritte. Plugins sind vertrauenswürdiger Code im Host-Prozess, keine Sandbox. JVM dev nutzt einen Fake-Host mit Netzwerk-Fixtures, nicht Android oder echtes HTTP.

Keine Wiederherstellung nach Prozessende, kein Video/HLS/Live-Streaming, kein OAuth und nicht acht fertige Produktionsintegrationen. Telegram, Smart Home und lokale KI bleiben ausdrücklich Entwürfe bzw. Absichten. Statische Prüfung und Kompilierung beweisen keine Gerätefunktion.


[MIT — LICENSE](LICENSE)
