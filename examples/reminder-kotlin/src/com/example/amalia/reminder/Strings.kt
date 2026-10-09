package com.example.amalia.reminder

import android.content.Context

/**
 * Тексты плагина — свои, в коде.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ПОЧЕМУ НЕ strings.xml
 * ════════════════════════════════════════════════════════════════════
 *
 * `.amaliaplug` **не может принести в приложение свои ресурсы**: они не
 * вливаются при установке, поэтому своего `R` у плагина нет. Значит, любой
 * свой текст плагин локализует сам — таблицей по тегу языка, как здесь.
 *
 * Язык берём из `Configuration` актуального контекста, а не из настроек
 * приложения: так текст совпадёт с тем, что видит пользователь.
 */
internal object Strings {

    /** Тексты одного языка. */
    open class Texts(
        val defaultText: String,
        val scheduled: (String) -> String,
        val noTime: String,
        val due: (String) -> String,
        val done: String,
        val snooze: String,
        val cleared: (Int) -> String,
        val listNearest: (count: Int, nearest: String?, remaining: String?) -> String,
    ) {
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

        open val minuteSuffix: String get() = "min"
        open val hourSuffix: String get() = "h"
    }

    private val RU = object : Texts(
        defaultText = "Напоминание",
        scheduled = { at -> "Запомнила. Напомню в $at." },
        noTime = "Не поняла время. Скажи, например: «напомни через 10 минут выключить чай».",
        due = { text -> "пора: $text" },
        done = "Готово",
        snooze = "Отложить",
        cleared = { count -> "Убрала напоминаний: $count." },
        listNearest = { count, nearest, remaining ->
            when {
                count == 0 -> "Напоминаний нет."
                nearest == null -> "Напоминаний: $count."
                else -> "Напоминаний: $count. Ближайшее: $nearest, " +
                    (remaining?.let { "осталось $it" } ?: "уже пора")
            }
        },
    ) {
        override val minuteSuffix: String get() = " мин"
        override val hourSuffix: String get() = " ч"
    }

    private val EN = object : Texts(
        defaultText = "Reminder",
        scheduled = { at -> "Noted. I'll remind you at $at." },
        noTime = "I didn't catch the time. Try: \"remind me in 10 minutes to take the tea off\".",
        due = { text -> "time: $text" },
        done = "Done",
        snooze = "Snooze",
        cleared = { count -> "Removed $count reminder(s)." },
        listNearest = { count, nearest, remaining ->
            when {
                count == 0 -> "No reminders."
                nearest == null -> "$count reminder(s)."
                else -> "$count reminder(s). Next: $nearest, " +
                    (remaining?.let { "$it left" } ?: "already due")
            }
        },
    )

    /** Тексты под текущий язык устройства. */
    fun forContext(context: Context): Texts {
        val tag = context.resources.configuration.locales[0].language.lowercase()
        return if (tag == "ru") RU else EN
    }
}
