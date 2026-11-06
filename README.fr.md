# Amalia SDK3 — Guide rapide

SDK Kotlin/JVM autonome pour les plugins de l’hôte Android Amalia. SDK3 est une façade de création sur le runtime de protocole existant, ni un nouveau chargeur ni une version ABI 3.0 stable.

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## Premiers pas

Prérequis : JDK 17, Gradle 9.7.1 et Python 3. La publication JVM et la compilation JAR ne nécessitent ni Android SDK ni sources de l’application. Les paquets DEX signés nécessitent D8 des Android build-tools et OpenSSL.

Depuis la racine de ce dépôt :

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

Le dépôt Maven local se trouve dans `platform/build/sdk-repository` ; aucun serveur Maven public ni `mavenLocal()` n’est supposé. Copiez le répertoire entier et indiquez un chemin absolu. La CLI utilise le wrapper du projet ou `gradle` du PATH ; les nouveaux projets n’incluent pas de wrapper.

## Suite et limites

Le guide CLI décrit `test`, `dev`, `package` et les options de signature. `amalia.json` est le seul manifeste auteur ; `GeneratedManifest` et `platform.json` sont générés. Déclarer une capacité n’accorde aucun droit.

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

Les paquets signés P-256 contiennent `platform.json`, `package.sig`, `classes.dex`. La confiance envers l’éditeur, le consentement d’activation et les droits d’invocation sont distincts. Les plugins sont du code de confiance dans le processus hôte, sans sandbox. JVM dev utilise un faux hôte et des fixtures réseau, pas Android ni du HTTP réel.

Pas de reprise après arrêt du processus, vidéo/HLS/flux en direct, OAuth ni huit intégrations de production complètes. Telegram, maison connectée et IA locale restent explicitement des brouillons ou intentions. Validation statique et compilation ne prouvent pas le fonctionnement sur appareil.


[MIT — LICENSE](LICENSE)
