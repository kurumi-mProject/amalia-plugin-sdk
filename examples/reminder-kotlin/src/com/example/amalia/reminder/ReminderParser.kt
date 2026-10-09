package com.example.amalia.reminder

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Разбор фразы напоминания.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЧТО УМЕЕТ И ЧЕГО НЕ УМЕЕТ
 * ════════════════════════════════════════════════════════════════════
 *
 * Умеет ровно два шаблона — относительный и абсолютный:
 *
 *   «напомни через 20 минут выключить чай»   → +20 минут, текст «выключить чай»
 *   «напомни через полчаса …»                → +30 минут
 *   «напомни в 18:30 позвонить маме»         → сегодня (или завтра) в 18:30
 *   «remind me in 10 minutes call mom»       → то же на английском
 *   «remind me at 18:30 call mom»
 *
 * НЕ умеет и не пытается: «после обеда», «вечером», «в конце дня».
 * Это распознаёт человек, а не регулярка — такие фразы должны уходить
 * языковой модели (`ParseOutcome.NotOurs`), а не превращаться в
 * угадывание. Плагин, который молча ставит напоминание на «примерно
 * вечер», хуже плагина, который честно не берётся.
 */
internal object ReminderParser {

    /** Что делать с фразой — решает вызывающий, тексты берёт из [Strings]. */
    sealed interface ParseOutcome {
        /** Разобрали: текст напоминания и момент срабатывания. */
        data class Ok(val text: String, val dueAtMillis: Long) : ParseOutcome

        /** Наш префикс, но время не распознано — просим повторить. */
        data object NoTime : ParseOutcome

        /** Не наша фраза — пусть отвечает модель. */
        data object NotOurs : ParseOutcome
    }

    private val PREFIXES = listOf(
        "напомни мне", "напомни", "remind me to", "remind me",
    )

    private val RELATIVE = Regex(
        """^через\s+(\d{1,3}|[а-яё]+)\s*(минут\w*|мин|час\w*|ч|секунд\w*|сек)\s*(.*)$""",
    )

    private val RELATIVE_EN = Regex(
        """^in\s+(\d{1,3}|[a-z]+)\s*(minutes?|mins?|hours?|hrs?|h|seconds?|secs?)\s*(.*)$""",
    )

    private val ABSOLUTE = Regex("""^в\s+(\d{1,2})[:.](\d{2})\s*(.*)$""")

    private val ABSOLUTE_EN = Regex("""^at\s+(\d{1,2})[:.](\d{2})\s*(.*)$""")

    /** Числа словами — то, как их реально произносят. */
    private val WORD_NUMBERS = mapOf(
        "одну" to 1, "один" to 1, "одна" to 1,
        "две" to 2, "два" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5,
        "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
        "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30,
        "сорок" to 40, "сорок пять" to 45, "шестьдесят" to 60,
        "ninety" to 90, "sixty" to 60, "forty" to 40, "thirty" to 30,
        "twenty" to 20, "fifteen" to 15, "twelve" to 12, "eleven" to 11,
        "ten" to 10, "nine" to 9, "eight" to 8, "seven" to 7,
        "six" to 6, "five" to 5, "four" to 4, "three" to 3, "two" to 2, "one" to 1,
    )

    /** «полчаса» — слишком частое, чтобы им пренебречь. */
    private val HALF_HOURS = listOf("полчаса", "пол часа", "half an hour", "half hour")

    /**
     * Разбирает фразу.
     *
     * @param nowMillis текущий момент — параметром, а не из часов внутри:
     *   так разбор детерминирован и его можно проверить в тесте.
     */
    fun parse(phrase: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ParseOutcome {
        val normalized = phrase.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', '!', '?', '…')
            .trim()

        val body = PREFIXES
            .firstOrNull { normalized.startsWith(it) }
            ?.let { normalized.removePrefix(it).trim() }
            ?.let { expandHalfHours(it) }
            ?: return ParseOutcome.NotOurs

        if (body.isEmpty()) return ParseOutcome.NoTime

        return parseRelative(body, nowMillis)
            ?: parseAbsolute(body, nowMillis, zone)
            ?: ParseOutcome.NoTime
    }

    private fun parseRelative(body: String, nowMillis: Long): ParseOutcome.Ok? {
        val match = RELATIVE.find(body) ?: RELATIVE_EN.find(body) ?: return null
        val (rawNumber, unit, rawText) = match.destructured

        val amount = amountOf(rawNumber, unit) ?: return null
        val unitMinutes = unitMinutes(unit) ?: return null

        val dueAt = nowMillis + amount * unitMinutes * 60_000L
        return ParseOutcome.Ok(text = rawText.trim(), dueAtMillis = dueAt)
    }

    private fun parseAbsolute(body: String, nowMillis: Long, zone: ZoneId): ParseOutcome.Ok? {
        val match = ABSOLUTE.find(body) ?: ABSOLUTE_EN.find(body) ?: return null
        val (rawHour, rawMinute, rawText) = match.destructured

        val hour = rawHour.toIntOrNull() ?: return null
        val minute = rawMinute.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        val target = now.with(LocalTime.of(hour, minute))
        // Время уже прошло — значит, речь о завтрашнем дне. Так поступает
        // человек: «напомни в 7 утра», сказанное вечером, — это завтра.
        val due = if (target.toInstant().toEpochMilli() <= nowMillis) {
            target.plusDays(1)
        } else {
            target
        }
        return ParseOutcome.Ok(text = rawText.trim(), dueAtMillis = due.toInstant().toEpochMilli())
    }

    /** «двадцать» → 20, «20» → 20, «полчаса» → 30 минут. */
    private fun amountOf(rawNumber: String, unit: String): Int? {
        val digits = rawNumber.toIntOrNull()
        if (digits != null) {
            // «полчаса» через цифру не пишут, но «через 0 минут» — пишут
            // случайно; ноль отсекаем, иначе напоминание сработает сразу.
            return digits.takeIf { it in 1..1440 }
        }
        return WORD_NUMBERS[rawNumber]
    }

    private fun unitMinutes(unit: String): Int? = when {
        unit.startsWith("мин") || unit.startsWith("min") -> 1
        unit.startsWith("час") || unit.startsWith("hour") || unit == "ч" || unit == "h" -> 60
        unit.startsWith("сек") || unit.startsWith("sec") -> 1
        else -> null
    }

    /** Приводит «полчаса» к обычному виду до основного разбора. */
    fun expandHalfHours(phrase: String): String {
        val lower = phrase.lowercase()
        val found = HALF_HOURS.firstOrNull { lower.contains(it) } ?: return phrase
        return phrase.replace(found, "30 минут", ignoreCase = true)
    }
}
