package com.example.amalia.quicknotes

import com.my.amali.plugin.AmaliaPlugin
import com.my.amali.plugin.CapabilityContributor
import com.my.amali.plugin.CommandContributor
import com.my.amali.plugin.CommandResult
import com.my.amali.plugin.PluginCategory
import com.my.amali.plugin.PluginContext
import com.my.amali.plugin.PluginMetadata

/**
 * Quick Notes — заметка голосом, без обращения к модели.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЧТО ЭТОТ ПРИМЕР ПОКАЗЫВАЕТ
 * ════════════════════════════════════════════════════════════════════
 *
 * 1. `CommandContributor` — фраза обрабатывается локально и мгновенно.
 *    «Запиши мысль купить хлеб» не должна ждать сеть: пока она дойдёт до
 *    LLM и вернётся, пользователь забудет, что хотел записать.
 * 2. **Узкий перехват.** Мы ловим только два точных префикса. Всё
 *    остальное уходит модели — ассистент остаётся основным мозгом.
 * 3. **Своё хранилище.** Заметки лежат в `filesDir` приложения. Плагин
 *    приносит своё состояние сам; хост не обязан давать API для каждого.
 */
class QuickNotesPlugin : AmaliaPlugin, CommandContributor, CapabilityContributor {

    override val metadata = PluginMetadata(
        id = "com.example.quicknotes",
        name = "Quick Notes",
        version = "1.0.0",
        author = "example",
        description = "Заметка голосом: «запиши мысль …» — без обращения к модели",
        category = PluginCategory.COMMAND,
        iconName = "copy",
    )

    /** Заметки — обычные файлы, ничего сверх разрешений не требуется. */
    override fun requiredCapabilities(): List<String> = listOf("files.read")

    override suspend fun onPhrase(phrase: String, context: PluginContext): CommandResult {
        val normalized = normalize(phrase)

        // ── «запиши мысль …» / «запомни, что …» ──────────────────────────
        val body = WRITE_PREFIXES
            .firstNotNullOfOrNull { prefix ->
                normalized.takeIf { it.startsWith(prefix) }
                    ?.removePrefix(prefix)
                    ?.trim()
            }

        if (body != null) {
            if (body.isEmpty()) {
                // Префикс без содержания: честно просим повторить, а не
                // записываем пустую заметку. Ответ озвучивается.
                return CommandResult.Handled(spoken = "И что записать?")
            }
            val saved = runCatching {
                NotesStore(context.appContext).append(body)
            }.isSuccess
            return CommandResult.Handled(
                spoken = if (saved) "Записала" else "Не смогла записать заметку",
            )
        }

        // ── «мои заметки» / «что я записал» ──────────────────────────────
        if (normalized in READ_PHRASES) {
            val last = runCatching { NotesStore(context.appContext).last() }.getOrNull()
            return CommandResult.Handled(
                spoken = last?.let { "Последняя заметка: $it" } ?: "Заметок пока нет",
            )
        }

        // Не наше — пусть отвечает языковая модель.
        return CommandResult.NotMine
    }

    /**
     * Приводит фразу к сравнимому виду.
     *
     * Распознаватель возвращает точку, заглавные буквы и лишние пробелы —
     * «Запиши мысль: купить хлеб.» и «запиши мысль купить хлеб» обязаны
     * дать одно и то же.
     */
    private fun normalize(phrase: String): String =
        phrase.trim().lowercase().replace(TRAILING_PUNCTUATION, "").trim()

    private companion object {
        /**
         * Префиксы, которые мы перехватываем.
         *
         * Их намеренно два и оба точные. Добавляя «запиши», «создай» и
         * подобное, легко получить перехват обычной просьбы — а каждая
         * такая фраза отнимает у пользователя ответ модели.
         */
        val WRITE_PREFIXES = listOf("запиши мысль", "запомни что")

        val READ_PHRASES = setOf("мои заметки", "что я записал", "последняя заметка")

        /** Точки и запятые в конце — от распознавателя, а не от человека. */
        val TRAILING_PUNCTUATION = Regex("[.,!?…]+$")
    }
}
