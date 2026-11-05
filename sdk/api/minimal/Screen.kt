package com.my.amali.plugin

import androidx.compose.runtime.Composable

/**
 * Минимальные контракты экранов плагинов.
 *
 * Три объявления — [ScreenContributor], [ScreenSpec], [ScreenHost] —
 * скопированы дословно из `ExtensionPoints.kt` хоста (строки 240–300).
 * Аннотация [Composable] берётся из заглушки
 * `androidx.compose.runtime.Composable` (см. `ComposeStubs.kt`),
 * чтобы плагин собирался без Android-зависимостей Compose.
 *
 * @see ScreenSpec
 */

/**
 * Вклад экранов, которые расширение добавляет в приложение.
 *
 * @see ScreenSpec
 */
interface ScreenContributor {
    /** Экраны, которые расширение добавляет в приложение. */
    fun screens(): List<ScreenSpec>
}

/**
 * Экран, приносимый расширением.
 *
 * @property route уникальный маршрут (`amalia://screen/my_player`).
 * @property titleRes заголовок для шапки и пункта навигации.
 * @property iconName необязательное имя иконки из векторного набора хоста
 *   (`voice`, `wave`, `lamp`, …) для пункта в панели навигации; пусто —
 *   иконка экрана по умолчанию.
 * @property content тело экрана. Получает [ScreenHost] — узкий фасад
 *   для навигации и доступа к среде; прямого доступа к NavController
 *   у расширения нет, чтобы оно не ломало стек.
 * @property inNavigation показывать ли пункт в основной навигации.
 * @property title Готовый заголовок; перекрывает [titleRes], в том числе при titleRes = 0.
 */
data class ScreenSpec(
    val route: String,
    val titleRes: Int,
    val inNavigation: Boolean = false,
    val iconName: String = "",
    val content: @Composable (ScreenHost) -> Unit,
    val title: String? = null,
)

/**
 * Фасад, который хост выдаёт экрану расширения.
 *
 * Ровно то, что экрану позволено: уйти назад, открыть другой экран по
 * маршруту. Никаких ссылок на NavController и Activity — расширение
 * не должно уметь больше, чем объявлено.
 */
interface ScreenHost {
    /** Вернуться назад по стеку. */
    fun goBack()

    /** Перейти на маршрут ядра или другого расширения. */
    fun navigate(route: String)
}
