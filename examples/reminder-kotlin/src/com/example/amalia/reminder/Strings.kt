package com.example.amalia.reminder

import android.content.Context

/**
 * Тексты плагина — свои, в коде.
 *
 * `.amaliaplug` не может принести свои ресурсы, поэтому локализуем сами
 * по тегу языка из конфигурации контекста.
 */
internal object Strings {

    /** Тексты одного языка. */
    abstract class Texts {
        abstract val defaultText: String
        abstract val noTime: String
        abstract val done: String
        abstract val snooze: String

        abstract fun scheduled(at: String): String
        abstract fun due(text: String): String
        abstract fun cleared(count: Int): String
        abstract fun listNearest(count: Int, nearest: String?, remaining: String?): String

        open val minuteSuffix: String get() = "min"
        open val hourSuffix: String get() = "h"

        /** «17 мин», «1 ч 5 мин» — человеческий остаток времени. */
        fun remainingIn(millisLeft: Long): String {
            val totalMinutes = (millisLeft / 60_000L).coerceAtLeast(0)
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return when {
                hours > 0 && minutes > 0 -> "$hours$hourSuffix $minutes$minuteSuffix"
                hours > 0 -> "$hours$hourSuffix"
                else -> "$minutes$minuteSuffix"
            }
        }
    }

    private val RU = object : Texts() {
        override val defaultText = "Напоминание"
        override val noTime = "Не поняла время. Скажи, например: «напомни через 10 минут выключить чай»."
        override val done = "Готово"
        override val snooze = "Отложить"
        override val minuteSuffix = " мин"
        override val hourSuffix = " ч"

        override fun scheduled(at: String) = "Запомнила. Напомню в $at."
        override fun due(text: String) = "пора: $text"
        override fun cleared(count: Int) = "Убрала напоминаний: $count."
        override fun listNearest(count: Int, nearest: String?, remaining: String?) = when {
            count == 0 -> "Напоминаний нет."
            nearest == null -> "Напоминаний: $count."
            else -> "Напоминаний: $count. Ближайшее: $nearest, " +
                (remaining?.let { "осталось $it" } ?: "уже пора")
        }
    }

    private val EN = object : Texts() {
        override val defaultText = "Reminder"
        override val noTime = "I didn't catch the time. Try: \"remind me in 10 minutes to take the tea off\"."
        override val done = "Done"
        override val snooze = "Snooze"

        override fun scheduled(at: String) = "Noted. I'll remind you at $at."
        override fun due(text: String) = "time: $text"
        override fun cleared(count: Int) = "Removed $count reminder(s)."
        override fun listNearest(count: Int, nearest: String?, remaining: String?) = when {
            count == 0 -> "No reminders."
            nearest == null -> "$count reminder(s)."
            else -> "$count reminder(s). Next: $nearest, " +
                (remaining?.let { "$it left" } ?: "already due")
        }
    }

    /** Тексты под текущий язык устройства. */
    fun forContext(context: Context): Texts {
        val tag = context.resources.configuration.locales[0].language.lowercase()
        return if (tag == "ru") RU else EN
    }
}
