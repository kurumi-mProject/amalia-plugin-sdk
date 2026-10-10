package com.example.amalia.reminder

import com.my.amali.plugin.ActionSpec
import com.my.amali.plugin.AmaliaPlugin
import com.my.amali.plugin.BackgroundContributor
import com.my.amali.plugin.BackgroundState
import com.my.amali.plugin.CapabilityContributor
import com.my.amali.plugin.CommandContributor
import com.my.amali.plugin.CommandResult
import com.my.amali.plugin.NotificationActionHandler
import com.my.amali.plugin.NotificationContributor
import com.my.amali.plugin.NotificationHost
import com.my.amali.plugin.NotificationSpec
import com.my.amali.plugin.PluginCategory
import com.my.amali.plugin.PluginContext
import com.my.amali.plugin.PluginMetadata
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Voice Reminders — голосовые напоминания.
 *
 * «напомни через 20 минут выключить чай» → через 20 минут плагин поднимет
 * напоминание в постоянном уведомлении, с кнопками «Готово» и «Отложить».
 *
 * ════════════════════════════════════════════════════════════════════
 *  КАК ЭТО РАБОТАЕТ БЕЗ ALARMMANAGER
 * ════════════════════════════════════════════════════════════════════
 *
 * Напрашивается `AlarmManager` — и это была бы ошибка. Точный будильник
 * требует разрешения `SCHEDULE_EXACT_ALARM`, которого плагин получить не
 * может (манифест приложения плагину недоступен), а `BroadcastReceiver`
 * для доставки нужно объявить в манифесте — тоже недоступно.
 *
 * Зато есть `BackgroundContributor`: пока он отвечает `Active`, **хост**
 * держит foreground-сервис и опрашивает плагин. То есть процесс жив, и
 * плагину достаточно уметь ответить на вопрос «что у меня сейчас?».
 * Никаких разрешений, никакого будильника, никаких манифестов — вся
 * механика выживания процесса уже в ядре.
 *
 * Цена — точность около периода опроса (2 с), что для напоминания
 * человеку не имеет значения.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЧЕГО ПЛАГИН НЕ МОЖЕТ (и это честно)
 * ════════════════════════════════════════════════════════════════════
 *
 * 1. **Произнести напоминание голосом.** В `HostServices` есть
 *    `stopSpeaking()` и `canNotify()` — но нет `speak(text)`. Плагин может
 *    показать уведомление, а не заговорить. Это первое, что стоит добавить
 *    в SDK: `HostServices.speak(text)` сделал бы голосовые напоминания
 *    настоящими.
 * 2. **Добавить свои строки ресурсов.** Поэтому подписи кнопок и текст
 *    приходят готовыми строками (`ActionSpec.label`, `NotificationSpec.text`),
 *    а не идентификаторами ресурсов.
 */
class ReminderPlugin :
    AmaliaPlugin,
    CommandContributor,
    BackgroundContributor,
    NotificationContributor,
    NotificationActionHandler,
    CapabilityContributor {

    override val metadata = PluginMetadata(
        id = "com.example.reminder",
        name = "Voice Reminders",
        version = "1.0.0",
        author = "example",
        description = "Голосовые напоминания: «напомни через 20 минут выключить чай»",
        category = PluginCategory.GENERAL,
        iconName = "alert",
    )

    private var store: ReminderStore? = null

    /**
     * Что просим у пользователя.
     *
     * Своего разрешения на будильник (точный) здесь нет намеренно: плагин
     * его не использует. `system.background` — это право жить в фоне, без
     * которого напоминание не сработает; уведомления — чтобы показать его.
     */
    override fun requiredCapabilities(): List<String> = listOf(
        "system.background",
        "notifications.post",
        "notifications.actions",
    )

    // ── Жизненный цикл ───────────────────────────────────────────────

    override fun onLoaded(context: PluginContext) {
        // Контекст приложения, а не активити: плагин живёт дольше экрана,
        // и держать активити в поле — это утечка по определению.
        storeContext = context.appContext
        store = ReminderStore(context.appContext)
    }

    override fun onUnloaded() {
        store = null
        storeContext = null
    }

    // ── Команды ──────────────────────────────────────────────────────

    override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
        val texts = Strings.forContext(context.appContext)
        val lower = phrase.trim().lowercase()

        // Список и очистка — простые точные фразы.
        if (lower in LIST_PHRASES) {
            val all = storeOf(context.appContext).all()
            val nearest = all.firstOrNull()
            val remaining = nearest?.let {
                texts.remainingIn(it.dueAtMillis - System.currentTimeMillis())
            }
            return CommandResult.Handled(
                spoken = texts.listNearest(all.size, nearest?.text?.ifBlank { null }, remaining),
            )
        }
        if (lower in CLEAR_PHRASES) {
            val removed = storeOf(context.appContext).clear()
            return CommandResult.Handled(
                spoken = if (removed == 0) texts.listNearest(0, null, null) else texts.cleared(removed),
            )
        }

        // Основное: «напомни …».
        val outcome = ReminderParser.parse(phrase, System.currentTimeMillis(), ZoneId.systemDefault())
        return when (outcome) {
            is ReminderParser.ParseOutcome.NotOurs -> CommandResult.NotMine

            is ReminderParser.ParseOutcome.NoTime ->
                // Префикс наш, а время не разобрали: просим повторить, а не
                // угадываем. Отдать фразу модели здесь нельзя — она поставит
                // «напоминание» без времени, и пользователь ничего не получит.
                CommandResult.Handled(spoken = texts.noTime)

            is ReminderParser.ParseOutcome.Ok -> {
                val reminder = storeOf(context.appContext).add(
                    dueAtMillis = outcome.dueAtMillis,
                    text = outcome.text.ifBlank { texts.defaultText },
                )
                CommandResult.Handled(spoken = texts.scheduled(formatClock(reminder.dueAtMillis)))
            }
        }
    }

    // ── Фон ──────────────────────────────────────────────────────────

    /**
     * Опрашивается хостом каждые две секунды.
     *
     * Пока есть напоминания — `Active`: хост держит foreground-сервис,
     * процесс не выгружается, напоминание не теряется. Когда список пуст —
     * `Idle`, и сервис гасится сам: лишнего уведомления не остаётся.
     */
    override suspend fun state(): BackgroundState {
        val context = storeContext ?: return BackgroundState.Idle
        val nearest = storeOf(context).all().firstOrNull() ?: return BackgroundState.Idle
        val texts = Strings.forContext(context)
        val left = nearest.dueAtMillis - System.currentTimeMillis()

        return BackgroundState.Active(
            titleRes = 0,
            textRes = 0,
            // Готовые строки, а не ресурсы: остаток времени («17 мин»)
            // идентификатором ресурса не выражается.
            title = nearest.text.ifBlank { texts.defaultText },
            text = if (left <= 0) texts.due(nearest.text.ifBlank { texts.defaultText })
            else texts.remainingIn(left),
            actions = actions(texts),
        )
    }

    // ── Уведомление ──────────────────────────────────────────────────

    override suspend fun post(host: NotificationHost) {
        val context = storeContext ?: return
        val nearest = storeOf(context).all().firstOrNull()
        if (nearest == null) {
            host.cancel(ID_REMINDER)
            return
        }
        val texts = Strings.forContext(context)
        val left = nearest.dueAtMillis - System.currentTimeMillis()

        host.notify(
            ID_REMINDER,
            NotificationSpec(
                titleRes = 0,
                textRes = 0,
                title = nearest.text.ifBlank { texts.defaultText },
                text = if (left <= 0) {
                    texts.due(nearest.text.ifBlank { texts.defaultText })
                } else {
                    texts.remainingIn(left)
                },
                onGoing = true,
                actions = actions(texts),
            ),
        )
    }

    /**
     * Кнопки уведомления.
     *
     * `id` — локальные; хост склеит их с именем плагина, когда покажет
     * уведомление, и вернёт нажатие ровно сюда. Поэтому «done» здесь не
     * конфликтует с «done» чужого плагина.
     */
    private fun actions(texts: Strings.Texts): List<ActionSpec> = listOf(
        ActionSpec(
            id = ACTION_DONE,
            iconRes = android.R.drawable.checkbox_on_background,
            titleRes = 0,
            label = texts.done,
        ),
        ActionSpec(
            id = ACTION_SNOOZE,
            iconRes = android.R.drawable.ic_menu_recent_history,
            titleRes = 0,
            label = texts.snooze,
        ),
    )

    // ── Нажатия кнопок ───────────────────────────────────────────────

    /**
     * Вызывается и когда приложение уже выгружено: хост поднимает процесс
     * и приходит сюда. Поэтому не полагаемся ни на что, кроме файла.
     */
    override suspend fun onAction(actionId: String) {
        val context = storeContext ?: return
        val store = storeOf(context)
        val nearest = store.all().firstOrNull() ?: return

        when (actionId) {
            ACTION_DONE -> store.remove(nearest.id)
            ACTION_SNOOZE -> store.snooze(nearest.id, SNOOZE_MINUTES)
        }
    }

    // ── Вспомогательное ──────────────────────────────────────────────

    /**
     * Контекст для фоновых ответов.
     *
     * `state()` и `onAction()` вызываются вне композиции, поэтому держим
     * контекст, полученный в [onLoaded]. Это `applicationContext` — держать
     * его безопасно, утечки активити здесь нет по определению.
     */
    private var storeContext: android.content.Context? = null

    private fun storeOf(context: android.content.Context): ReminderStore =
        store ?: ReminderStore(context).also { store = it }

    /** «18:30» — по-человечески, для ответа голосом. */
    private fun formatClock(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private companion object {
        const val ID_REMINDER = "reminder"
        const val ACTION_DONE = "done"
        const val ACTION_SNOOZE = "snooze"
        const val SNOOZE_MINUTES = 10

        val LIST_PHRASES = setOf(
            "мои напоминания", "напоминания", "что я просил напомнить",
            "my reminders", "reminders",
        )
        val CLEAR_PHRASES = setOf(
            "убери напоминания", "удали напоминания", "отмени напоминания",
            "clear reminders", "delete reminders",
        )
    }
}
