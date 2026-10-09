package com.example.amalia.quicknotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Хранилище заметок плагина.
 *
 * Заметки лежат одной строкой каждая в `filesDir/quicknotes.txt`. Плагин
 * приносит своё состояние сам — хост не обязан давать API под каждую
 * задачу, а простое файловое хранилище переживает и перезапуск, и
 * обновление приложения.
 *
 * Ввод-вывод — на [Dispatchers.IO]: `onPhrase` вызывается из корутины
 * конвейера, и блокировать её файловой записью нельзя.
 */
class NotesStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /** Дописывает заметку; возвращает `true`, если запись прошла. */
    suspend fun append(text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Новая строка, а не перезапись: заметки — история, а не слот.
            file.appendText(text.trim() + "\n")
            true
        }.getOrDefault(false)
    }

    /** Последняя записанная заметка или `null`, если их нет. */
    suspend fun last(): String? = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.isFile }
                ?.readLines()
                ?.lastOrNull { it.isNotBlank() }
                ?.trim()
        }.getOrNull()
    }

    private companion object {
        const val FILE_NAME = "quicknotes.txt"
    }
}
