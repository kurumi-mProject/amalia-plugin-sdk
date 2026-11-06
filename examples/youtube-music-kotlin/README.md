# YouTube Music — плагин Амалии

Legacy `.amaliaplug` для Amalia host API 2.1.0:

- ищет первое видео по фразе через NewPipeExtractor;
- выбирает аудиопоток с максимальным bitrate;
- проигрывает его через Media3 ExoPlayer;
- сообщает хосту `MEDIA_PLAYBACK`, чтобы общий foreground service продолжал работу;
- публикует уведомление с play/pause/stop;
- приглушает собственный плеер во время речи Амалии;
- поддерживает команды `включи <запрос>`, `поставь <запрос>`, `пауза`, `продолжи`, `останови музыку` и `что играет`.

## Сборка

Сборка плагина требует JDK 17, Gradle и Android SDK platform 35/build-tools 35.0.0:

```bash
export ANDROID_HOME=/path/to/android-sdk
export GRADLE_BIN=/path/to/gradle
./pack.sh
```

Результат: `dist/youtube-music.amaliaplug`.

APK Амалии здесь не собирается. Перед публикацией нужно проверить пакет на устройстве: поиск, 403/429 YouTube, блокировку экрана, аудиофокус, речь ассистента, отключение/выгрузку плагина и истечение URL потока.

## Важно

Плагин использует legacy API (`plugin.json`). Новый protocol-плагин не получает Android `Context` и не может использовать AndroidX Media3 через текущий загрузчик. ZIP-плагин также не добавляет Android-манифест-компоненты; фоновая работа выполняется через существующий сервис хоста и `BackgroundContributor`.
