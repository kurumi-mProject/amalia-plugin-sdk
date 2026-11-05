package androidx.compose.runtime

/**
 * Заглушка аннотации [Composable] для минимальных контрактов.
 *
 * Минимальные контракты в `sdk/api/minimal` позволяют собрать плагин
 * без Android-зависимостей Compose: достаточно этой аннотации, чтобы
 * сигнатуры экранов совпадали с хостом. Реальная аннотация из
 * `androidx.compose.runtime` подменяет заглушку на classpath хоста.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Composable
