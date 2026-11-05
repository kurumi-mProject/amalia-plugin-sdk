package com.my.amali.plugin

/**
 * Вклад собственного экрана.
 *
 * Расширение отдаёт composable и декларацию навигации. Хост добавляет
 * экран в NavHost и (если попросит расширение) — пункт в навигацию.
 * Навигационный граф остаётся в ядре: расширение объявляет только
 * «куда пришли» и «куда уйти», а не переписывает маршруты.
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
 */
data class ScreenSpec(
    val route: String,
    val titleRes: Int,
    val inNavigation: Boolean = false,
    val iconName: String = "",
    val content: @androidx.compose.runtime.Composable (ScreenHost) -> Unit,
    val title: String? = null,
)

/**
 * Фасад, который хост выдаёт экрану расширения.
 *
 * Ровно то, что экрану позволено: уйти назад, открыть другой экран по
 * маршруту, показать уведомление. Никаких ссылок на NavController и
 * Activity — расширение не должно уметь больше, чем объявлено.
 */
interface ScreenHost {
    /** Вернуться назад по стеку. */
    fun goBack()

    /** Перейти на маршрут ядра или другого расширения. */
    fun navigate(route: String)
}

