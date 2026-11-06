# Amalia SDK3 — Краткое руководство

Самостоятельный Kotlin/JVM SDK для плагинов Android-хоста Amalia. SDK3 — удобный API поверх существующего protocol runtime, а не новый загрузчик или стабильный релиз ABI 3.0.

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## Начало работы

Нужны JDK 17, Gradle 9.7.1 и Python 3. Публикация JVM-библиотек и компиляция JAR не требуют Android SDK или исходников приложения. Для подписанной DEX-упаковки нужны D8 из Android build-tools и OpenSSL.

Из корня этого репозитория выполните:

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

Используется локальный Maven-репозиторий `platform/build/sdk-repository`, а не публичный Maven или `mavenLocal()`. При переносе скопируйте весь каталог и укажите абсолютный путь. CLI использует wrapper проекта, если он есть, иначе `gradle` из PATH; новый проект wrapper не содержит.

## Дальнейшие шаги и ограничения

Команды `test`, `dev`, `package` и параметры подписи описаны в руководстве CLI. `amalia.json` — единственный авторский манифест; `GeneratedManifest` и `platform.json` генерируются. Декларации capabilities не выдают разрешения.

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

Пакеты подписаны P-256 и содержат `platform.json`, `package.sig`, `classes.dex`. Доверие к издателю, согласие на активацию и invocation grants настраиваются отдельно. Плагин — доверенный код в процессе хоста, не sandbox. JVM dev использует fake host и сетевые fixtures, не Android или реальный HTTP.

Нет восстановления после смерти процесса, видео/HLS/live streaming, OAuth или всех восьми готовых production-интеграций. Telegram, smart home и local AI — явно обозначенные черновики/намерения. Статическая проверка и сборка не доказывают работоспособность на устройстве.


[MIT — LICENSE](LICENSE)
