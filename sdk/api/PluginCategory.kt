package com.my.amali.plugin

/**
 * Категория плагина — определяет, в какой раздел «магазина плагинов»
 * и в какую группу реестра попадает его вклад.
 *
 * @property id машиночитаемое имя для манифеста `plugin.json`.
 */
enum class PluginCategory(val id: String) {
    /** Визуальные темы: палитры, циркадные лестницы, типографика, формы. */
    THEME("theme"),

    /** Движки конвейера: распознавание речи, мозг, синтез голоса. */
    ENGINE("engine"),

    /** Голосовые инструменты — то, что Амалия умеет делать руками. */
    TOOLS("tools"),

    /** Экраны и вкладки навигации. */
    SCREEN("screen"),

    /** Визуальные виджеты ассистента: волна, лампа, приветствие. */
    WIDGET("widget"),

    /** Вклад в системный промпт — характер и правила поведения. */
    PROMPT("prompt"),

    /** Приветственные и разговорные фразы. */
    PHRASES("phrases"),

    /** Системные команды устройства. */
    COMMAND("command"),

    /** Всё прочее. */
    GENERAL("general");

    companion object {
        /** Разбирает значение из манифеста; неизвестное — [GENERAL]. */
        fun fromId(value: String?): PluginCategory =
            entries.firstOrNull { it.id == value || it.name.equals(value, ignoreCase = true) }
                ?: GENERAL
    }
}
