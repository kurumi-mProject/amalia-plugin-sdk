# Amalia SDK3 — Guía breve

SDK independiente de Kotlin/JVM para plugins del host Android de Amalia. SDK3 es una fachada de autor sobre el runtime de protocolo existente, no un nuevo cargador ni una versión estable de ABI 3.0.

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## Primeros pasos

Se requieren JDK 17, Gradle 9.7.1 y Python 3. Publicar las bibliotecas JVM y compilar JAR no requiere Android SDK ni código de la aplicación. El empaquetado DEX firmado necesita D8 de Android build-tools y OpenSSL.

Desde la raíz de este repositorio:

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

El repositorio Maven local está en `platform/build/sdk-repository`; no se presupone un servidor Maven público ni `mavenLocal()`. Copia el directorio completo y usa una ruta absoluta. La CLI usa el wrapper del proyecto o `gradle` del PATH; los proyectos nuevos no incluyen wrapper.

## Siguientes pasos y límites

La guía CLI describe `test`, `dev`, `package` y las opciones de firma. `amalia.json` es el único manifiesto de autor; `GeneratedManifest` y `platform.json` se generan. Declarar capacidades no concede permisos.

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

Los paquetes firmados con P-256 contienen `platform.json`, `package.sig`, `classes.dex`. La confianza en el editor, el consentimiento de activación y los permisos de invocación son pasos separados. Los plugins son código de confianza dentro del proceso del host, no un sandbox. JVM dev utiliza un host simulado y fixtures de red, no Android ni HTTP real.

No hay recuperación tras la muerte del proceso, vídeo/HLS/streaming en vivo, OAuth ni ocho integraciones completas de producción. Telegram, hogar inteligente e IA local siguen siendo borradores o intenciones explícitas. Validar y compilar no demuestra funcionamiento en un dispositivo.


[MIT — LICENSE](LICENSE)
