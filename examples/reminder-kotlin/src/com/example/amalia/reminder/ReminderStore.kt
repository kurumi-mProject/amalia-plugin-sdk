package com.example.amalia.reminder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Одно напоминание. */
internal data class Reminder(
    val id: Long,
    val dueAtMillis: Long,
    /** Что сказать в момент срабатывания. Пусто — скажем «Напоминание». */
    val text: String,
)

/**
 * Хранилище напоминаний плагина.
 *
 * Формат — одна строка на напоминание: `id|dueAt|text`. Текст идёт
 * последним и разбирается с ограничением в три части, поэтому вертикальная
 * черта внутри самой заметки («купить хлеб | молоко») ничего не ломает.
 *
 * Файл, а не база данных: напоминаний десятки, а не тысячи, и плагин не
 * должен тянуть зависимость ради трёх полей. Хранилище переживает
 * перезапуск процесса — именно поэтому напоминание не теряется, когда
 * Android выгружает приложение.
 */
internal class ReminderStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /** Все напоминания, ближайшее — первым. */
    suspend fun all(): List<Reminder> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.isFile) return@runCatching emptyList()
            file.readLines()
                .mapNotNull(::decode)
                .sortedBy { it.dueAtMillis }
        }.getOrDefault(emptyList())
    }

    /** Добавляет напоминание и возвращает его с выданным id. */
    suspend fun add(dueAtMillis: Long, text: String): Reminder = withContext(Dispatchers.IO) {
        val existing = all()
        // Простой монотонный id: он нужен только чтобы различать записи
        // внутри одного файла, а не чтобы быть глобально уникальным.
        val id = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        val reminder = Reminder(id = id, dueAtMillis = dueAtMillis, text = text.trim())
        write(existing + reminder)
        reminder
    }

    /** Убирает напоминание по id. Возвращает `true`, если что-то убрали. */
    suspend fun remove(id: Long): Boolean = withContext(Dispatchers.IO) {
        val existing = all()
        val left = existing.filterNot { it.id == id }
        if (left.size == existing.size) return@withContext false
        write(left)
        true
    }

    /** Сдвигает напоминание на [minutes] вперёд. */
    suspend fun snooze(id: Long, minutes: Int): Boolean = withContext(Dispatchers.IO) {
        val existing = all()
        val target = existing.firstOrNull { it.id == id } ?: return@withContext false
        val updated = existing.map {
            if (it.id == id) it.copy(dueAtMillis = target.dueAtMillis + minutes * 60_000L) else it
        }
        write(updated)
        true
    }

    /** Убирает все напоминания. Возвращает, сколько убрали. */
    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        val count = all().size
        if (count > 0) write(emptyList())
        count
    }

    private fun write(items: List<Reminder>) {
        runCatching {
            // Пишем атомарно: сначала во временный файл, потом переименовываем.
            // Иначе выгрузка процесса в момент записи оставит обрезанный файл,
            // и все напоминания исчезнут разом.
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(items.joinToString(separator = "\n", transform = ::encode))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun encode(reminder: Reminder): String =
        "${reminder.id}|${reminder.dueAtMillis}|${reminder.text}"

    private fun decode(line: String): Reminder? {
        val parts = line.split('|', limit = 3)
        if (parts.size < 3) return null
        val id = parts[0].toLongOrNull() ?: return null
        val dueAt = parts[1].toLongOrNull() ?: return null
        return Reminder(id = id, dueAtMillis = dueAt, text = parts[2])
    }

    private companion object {
        const val FILE_NAME = "reminders.txt"
    }
}
