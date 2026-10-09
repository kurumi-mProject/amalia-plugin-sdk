package com.my.amali.plugin

/**
 * Паспорт плагина: то, что пользователь видит в списке до включения,
 * и то, по чему ядро различает плагины между собой.
 *
 * @property id уникальный идентификатор, обычно обратный домен:
 *   `com.mirabel.theme.neon`. По нему хранится состояние вкл/выкл,
 *   поэтому смена id = «новый плагин» для установленных копий.
 * @property name отображаемое имя.
 * @property version версия плагина в формате SemVer.
 * @property author автор для карточки в магазине плагинов.
 * @property description короткое описание.
 * @property category категория — определяет, какие вклады плагин несёт.
 * @property minHostVersion минимальная версия хоста (Амалии), с которой
 *   плагин совместим. Хост ниже требуемой откажется включать плагин.
 * @property iconName необязательное имя иконки из **векторного набора
 *   хоста** (`voice`, `wave`, `mic`, `history`, `lamp`, …). Пусто — иконку
 *   выбирает категория. Плагин не приносит «картинку» и не рисует свою:
 *   он лишь называет одну из готовых иконок, чтобы строка осталась в единой
 *   визуальной системе (см. `PluginRows.kt`).
 *
 * Иконка-эмодзи у паспорта отсутствует намеренно: её рисует хост единым
 * векторным набором. Эмодзи как иконки в приложении запрещены
 * дизайн-правилами проекта, а свободное строковое поле быстро превращалось
 * в мёртвый атрибут. [iconName] — не «своя картинка», а выбор из набора.
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val category: PluginCategory = PluginCategory.GENERAL,
    val minHostVersion: String = HOST_VERSION,
    val iconName: String = "",
) {
    init {
        require(id.isNotBlank()) { "Plugin id must not be blank" }
        require(name.isNotBlank()) { "Plugin name must not be blank" }
        require(version.isNotBlank()) { "Plugin version must not be blank" }
    }

    companion object {
        /** Версия хоста, на которую собраны встроенные плагины. */
        const val HOST_VERSION: String = "2.0.0"
    }
}
