package com.example.amalia.pomodoro

import com.my.amali.R
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

/**
 * Pomodoro — таймер 25/5 с кнопками прямо в уведомлении.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЧТО ЭТОТ ПРИМЕР ПОКАЗЫВАЕТ
 * ════════════════════════════════════════════════════════════════════
 *
 * Четыре контракта в одном плагине — так выглядит настоящая фича:
 *
 *  • `CommandContributor` — «начни помодоро» срабатывает мгновенно;
 *  • `BackgroundContributor` — пока таймер идёт, хост держит
 *    foreground-сервис, иначе Android убьёт процесс и отсчёт умрёт;
 *  • `NotificationContributor` — постоянное уведомление с кнопками;
 *  • `NotificationActionHandler` — нажатия приходят обратно сюда, даже
 *    если процесс был пересоздан.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ПОЧЕМУ ПОДПИСИ КНОПОК ВЗЯТЫ У ХОСТА
 * ════════════════════════════════════════════════════════════════════
 *
 * `ActionSpec.titleRes` — идентификатор ресурса **хоста**: `.amaliaplug`
 * не может принести свои строки (ресурсы не вливаются в приложение).
 * Поэтому кнопки переиспользуют ближайшие по смыслу строки Амалии —
 * «Пропустить», «Закрыть» — и они уже переведены на девять языков.
 *
 * Своя подпись сегодня невозможна — это признанное ограничение API;
 * см. README рядом.
 */
class PomodoroPlugin :
    AmaliaPlugin,
    CommandContributor,
    BackgroundContributor,
    NotificationContributor,
    NotificationActionHandler,
    CapabilityContributor {

    override val metadata = PluginMetadata(
        id = "com.example.pomodoro",
        name = "Pomodoro",
        version = "1.0.0",
        author = "example",
        description = "Таймер 25/5 с кнопками прямо в уведомлении",
        category = PluginCategory.GENERAL,
        iconName = "history",
    )

    /** Таймер живёт столько же, сколько плагин: состояние — его. */
    private val timer = PomodoroTimer()

    override fun requiredCapabilities(): List<String> = listOf(
        "notifications.post",
        "notifications.actions",
        "system.background",
    )

    // ── Жизненный цикл ───────────────────────────────────────────────

    override fun onLoaded(context: PluginContext) {
        // Дорогую работу — в корутину: onLoaded вызывается на главном
        // потоке, и тормозить запуск приложения нельзя.
        timer.startTicking(context)
    }

    override fun onDisabled() {
        timer.stop()
    }

    override fun onUnloaded() {
        timer.stop()
    }

    // ── Команды ──────────────────────────────────────────────────────

    override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
        val normalized = phrase.trim().lowercase().trimEnd('.', '!', '…')
        return when (normalized) {
            "начни помодоро", "старт помодоро" -> {
                timer.start()
                CommandResult.Handled(spoken = "Работаем 25 минут")
            }
            "стоп помодоро", "останови помодоро" -> {
                timer.stop()
                CommandResult.Handled(spoken = "Таймер снят")
            }
            else -> CommandResult.NotMine
        }
    }

    // ── Фоновое состояние ────────────────────────────────────────────

    /**
     * Опрашивается хостом. Пока таймер идёт — `Active`, и хост держит
     * foreground-сервис, поэтому отсчёт переживает уход приложения в фон
     * и не «замирает» вместе с процессом.
     */
    override suspend fun state(): BackgroundState =
        if (timer.isRunning()) {
            BackgroundState.Active(
                titleRes = R.string.app_name,
                textRes = R.string.common_loading,
                actions = notificationActions(),
            )
        } else {
            BackgroundState.Idle
        }

    // ── Уведомление ──────────────────────────────────────────────────

    override suspend fun post(host: NotificationHost) {
        if (!timer.isRunning()) {
            host.cancel(ID_TIMER)
            return
        }
        host.notify(
            ID_TIMER,
            NotificationSpec(
                titleRes = R.string.app_name,
                textRes = R.string.common_loading,
                onGoing = true,
                actions = notificationActions(),
            ),
        )
    }

    /**
     * Кнопки уведомления.
     *
     * Идентификаторы («toggle», «skip») — **локальные**: хост добавит к ним
     * имя плагина, когда покажет уведомление, и по этой склейке вернёт
     * нажатие ровно сюда. Два плагина вправе назвать кнопку одинаково.
     */
    private fun notificationActions(): List<ActionSpec> = listOf(
        ActionSpec(
            id = ACTION_TOGGLE,
            iconRes = android.R.drawable.ic_media_pause,
            titleRes = R.string.common_close,
        ),
        ActionSpec(
            id = ACTION_SKIP,
            iconRes = android.R.drawable.ic_media_next,
            titleRes = R.string.onboarding_skip,
        ),
    )

    // ── Нажатия кнопок ───────────────────────────────────────────────

    /**
     * Вызывается при нажатии кнопки — в том числе после того, как процесс
     * был выгружен системой: хост поднимает приложение и приходит сюда.
     */
    override suspend fun onAction(actionId: String) {
        when (actionId) {
            ACTION_TOGGLE -> timer.toggle()
            ACTION_SKIP -> timer.skipPhase()
        }
    }

    private companion object {
        const val ID_TIMER = "pomodoro"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_SKIP = "skip"
    }
}
