package com.example.amalia.reminder

import android.content.Context
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
 * Синхронные операции: файл маленький (десятки строк), читается мгновенно.
 * Вызовы идут из suspend-контекста хоста, который уже на IO-диспетчере.
 */
internal class ReminderStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /** Все напоминания, ближайшее — первым. */
    fun all(): List<Reminder> = runCatching {
        if (!file.isFile) return emptyList()
        file.readLines()
            .mapNotNull(::decode)
            .sortedBy { it.dueAtMillis }
    }.getOrDefault(emptyList())

    /** Добавляет напоминание и возвращает его с выданным id. */
    fun add(dueAtMillis: Long, text: String): Reminder {
        val existing = all()
        val id = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        val reminder = Reminder(id = id, dueAtMillis = dueAtMillis, text = text.trim())
        write(existing + reminder)
        return reminder
    }

    /** Убирает напоминание по id. Возвращает `true`, если что-то убрали. */
    fun remove(id: Long): Boolean {
        val existing = all()
        val left = existing.filterNot { it.id == id }
        if (left.size == existing.size) return false
        write(left)
        return true
    }

    /** Сдвигает напоминание на [minutes] вперёд. */
    fun snooze(id: Long, minutes: Int): Boolean {
        val existing = all()
        val target = existing.firstOrNull { it.id == id } ?: return false
        val updated = existing.map {
            if (it.id == id) it.copy(dueAtMillis = target.dueAtMillis + minutes * 60_000L) else it
        }
        write(updated)
        return true
    }

    /** Убирает все напоминания. Возвращает, сколько убрали. */
    fun clear(): Int {
        val count = all().size
        if (count > 0) write(emptyList())
        return count
    }

    private fun write(items: List<Reminder>) {
        runCatching {
            // Атомарная запись: сначала во временный файл, потом rename.
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
