# Выполнение пунктов 1–7

Запрос пользователя 2026-11-04: выполнить пункты 1–7 параллельно субагентами за текущий заход. Пункт 8 (30 сценариев, device/stress acceptance) выполнять только после отдельной команды.

## Разделение работы

| Участок | Владение | Результат |
|---|---|---|
| Установка и recovery | app/ui/plugins, platform/management, platform/recovery, отдельные XML-ресурсы | импорт, review/consent, trust pins, activate/disable/resume, аварийное управление |
| Compatibility и данные | runtime/packages, DependencyResolver | bounded manifest, host policy, зависимости/profile, namespaces, snapshots/migrations/rollback |
| Durable tasks | runtime/tasks, app/platform/tasks, agent execution | dispatch/leases/fences/questions/cancel/recovery UNKNOWN и реальный product adapter |
| Builtin roles | app/platform/builtin, voice/AI provider implementations | публичные contracts/providers/clients и подключение реальных потребителей |
| Declarative UI / studio | protocol/ui, app/platform/presentation, app/ui/studio | registry renderer, действия ProtocolClient, preview/edit/build/install flow |
| Remote | protocol/remote, runtime/remote | pairing/trust/revoke/reconnect и ограниченный authenticated transport |
| SDK / CI / интеграция | Gradle/workflows/examples и ServiceLocator/PlatformHost/navigation | независимый SDK author build, host composition и проверки CI |

Задачи выдаются с непересекающимися файлами. Изменения существующего рабочего дерева сохраняются. Интеграция выполняется после получения конкретных API от исполнителей.

## Порядок

1. Зафиксировать контракты существующих API, запустить независимые участки.
2. Получить готовые реализации, подключить их к host composition и существующим пользовательским входам.
3. Проверить source/diff/resources/API wiring и устранить найденные несоответствия.
4. Подготовить независимую публикацию SDK и внешний проект; выполнить сборку через GitHub Actions (локальные компиляторы запрещены).
5. Сверить фактический результат по каждому пункту; документировать ограничения и результат CI.

## Критерии отчёта

Наличие исходников, подтверждённая компиляция и device acceptance — разные факты. Никакой пункт не объявляется полностью завершённым по наличию класса или успешному выходу отдельной команды. Внешние сервисы/build providers не считаются существующими без конкретного endpoint/provider. Пункт 8 не запускается в этом заходе.

## Сверка интеграции 2026-11-04

Закрываются конкретные разрывы композиции: remote catalog/import consent, PluginContext KV namespaces, selected background speech и agent strategy, package-local UI templates, tasks navigation. Состояние, ограничения и доказательства CI — в IMPLEMENTATION.md. Не смешивать подключение этих механизмов с полной миграцией всех voice/UI ролей или пунктом 8.
