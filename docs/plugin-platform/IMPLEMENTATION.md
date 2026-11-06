# Статус реализации платформы

Дата сверки: 2026-11-04. Ветка: `platform/open-contract-foundation`.

Пункты 1–7 имеют конкретную реализацию и host-интеграцию, описанную ниже. Это не подтверждение полной миграции приложения или device acceptance. Пункт 8 (30 независимых сценариев, device/stress) выполняется только по отдельной команде; покрытие 90–95% не измерено.

## Реализация пунктов 1–7

| Пункт | Реализовано и подключено | Граница |
|---|---|---|
| 1. Установка/recovery | SAF ZIP import, pins, immutable review/consent, activate/disable/resume/rollback; отдельный native recovery с диагностикой и отключением пакетов | Recovery не исполняет пакет и не делает неподтверждённый rollback данных; восстановление версии рассматривается обычным installer |
| 2. Compatibility/данные | Manifest protocol/runtime/ABI, dependency resolver/profile, KV data/settings, declarative migrations, snapshot, transactional recovery/rollback | `PluginContext.storage` выдаётся только точной installer identity; старое поколение теряет доступ до миграции; это bounded KV, не произвольная файловая система |
| 3. Durable tasks | TaskServiceProvider → DurableTaskService → реальный AgentLoopRunner; журнал/CAS/leases/fences, continuation consent, cancel/pause/resume, UNKNOWN; экран задач и navigation | Нет автоматического повторения неизвестного эффекта; старые executor generations не мигрируют автоматически; не distributed scheduler |
| 4. Builtin roles | Выбранные STT/LLM/TTS/tools в основном оркестраторе; общий выбранный TTS для AgentVoice; persisted exact consent; выбранная agent strategy поверх настоящего LlmBrain с бинарным screenshot ResourceRef | Wake, VAD/acquisition, playback/turn manager и весь стандартный UI ещё не вынесены в отдельные заменяемые роли; agent strategy не равна whole agent runtime |
| 5. Declarative UI/Studio | Registry renderer, caller-scoped actions, dynamic routes/withdrawal, editor/preview/save/local declaration build/stage/install consent; package-local `components.json` и импорт композиции в Studio | Композиция из существующих примитивов; cross-package/native Compose renderer и внешний Kotlin build provider отсутствуют |
| 6. Remote | Mutual TLS, certificate provisioning/pairing/revoke, явное connect/reconnect, UNKNOWN; настоящий каталог tools, reviewed export/import, CoreRuntime adapter, one-call input review/consent и withdrawal | Immediate JSON operations; без remote streams/tasks/resource transfer, discovery, relay, auto retry/reconnect; approvals памяти сбрасываются после перезапуска |
| 7. SDK/CI | Независимые protocol/runtime Maven artifacts и author project вне checkout; ServiceLocator/PlatformHost/navigation wiring | SDK 0.2.0-SNAPSHOT и DEX ABI `amalia-sdk-0.2-…` требуют пересборки внешних DEX; публичный Maven server не заявлен |

## Проверка

Локальные компиляторы/APK-сборки не запускались: сборка разрешена только через GitHub Actions. Новые тесты не добавлялись.

- `e20a5d8`: [Platform verification](https://github.com/kurumi-mProject/amalia-private/actions/runs/41915855686) — success, включая SDK publication и независимый author compile.
- `e2e710c`: [Build Amalia](https://github.com/kurumi-mProject/amalia-private/actions/runs/41920204986) — Debug и Release success; исправлен ресурс Studio, ранее отсутствовавший в committed snapshot.
- Текущие изменения: XML и одинаковые ключи во всех девяти локалях, Kotlin string references и `git diff --check` проверяются перед отправкой. CI нового среза фиксируется отдельно после push.
- Устройства, реальный TLS между телефонами, внешний DEX replacement и crash/rollback acceptance не проверены.

## Остаток большой цели

Отдельно от подключения механизмов 1–7 остаётся полная миграция voice/session, wake/VAD, playback и стандартного UI/router, cross-package UI components, внешние build providers и distributed resource/task execution. Эти возможности нельзя считать готовыми по наличию базовых API. Приёмка и целевое покрытие из [09-MIGRATION-AND-ACCEPTANCE.md](09-MIGRATION-AND-ACCEPTANCE.md) не пройдены.

## Пользовательский поток

Импорт и выбор ролей открываются из настроек плагинов. Оттуда доступны Studio, remote management и durable tasks. Новый пакет не получает grants автоматически; выбор роли и remote export/import требуют отдельного согласия. После вводных слайдов разрешения остаются необязательными; справка доступна из настроек.
