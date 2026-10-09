package com.example.amalia.counter

import com.my.amali.data.ai.AmaliaTool
import com.my.amali.data.ai.ToolArguments
import com.my.amali.data.ai.ToolDefinition
import com.my.amali.data.ai.ToolOutcome
import com.my.amali.data.ai.ToolParameter
import com.my.amali.plugin.AmaliaPlugin
import com.my.amali.plugin.PluginCategory
import com.my.amali.plugin.PluginContext
import com.my.amali.plugin.PluginMetadata
import com.my.amali.plugin.ToolContributor
import java.util.Locale

/**
 * Unit Converter — инструмент, который модель вызывает сама.
 *
 * ════════════════════════════════════════════════════════════════════
 *  ЗАЧЕМ ИНСТРУМЕНТ, ЕСЛИ ЕСТЬ МОДЕЛЬ
 * ════════════════════════════════════════════════════════════════════
 *
 * Языковые модели врут в арифметике: 72 °F они уверенно переведут в
 * «22,4 °C» с третьим знаком, который придумали сами. Всё, что обязано
 * быть **точно** — считает код, а модель лишь решает, когда его позвать
 * и как пересказать результат.
 *
 * Инструмент — это имя, схема аргументов и обработчик. Модель видит
 * описание и решает вызвать; хост передаёт сюда аргументы; возвращённый
 * JSON уходит модели как результат вызова.
 */
class UnitsPlugin : AmaliaPlugin, ToolContributor {

    override val metadata = PluginMetadata(
        id = "com.example.units",
        name = "Unit Converter",
        version = "1.0.0",
        author = "example",
        description = "Точный перевод единиц: км/мили, кг/фунты, °C/°F",
        category = PluginCategory.TOOLS,
        iconName = "tools",
    )

    override fun tools(context: PluginContext): List<AmaliaTool> = listOf(convertUnits())

    private fun convertUnits() = AmaliaTool(
        definition = ToolDefinition(
            name = "convert_units",
            // Описание — единственное, по чему модель решает, звать ли
            // инструмент. Оно должно быть конкретным: что умеет, чего нет.
            description = "Переводит значение между единицами: " +
                "km/mi, kg/lb, c/f. Только эти шесть единиц.",
            parameters = listOf(
                ToolParameter(
                    name = "value",
                    type = ToolParameter.JsonType.NUMBER,
                    description = "Число для перевода",
                ),
                ToolParameter(
                    name = "from",
                    type = ToolParameter.JsonType.STRING,
                    description = "Исходная единица",
                    enumValues = listOf("km", "mi", "kg", "lb", "c", "f"),
                ),
                ToolParameter(
                    name = "to",
                    type = ToolParameter.JsonType.STRING,
                    description = "Целевая единица",
                    enumValues = listOf("km", "mi", "kg", "lb", "c", "f"),
                ),
            ),
        ),
        handler = { args -> convert(args) },
    )

    private fun convert(args: ToolArguments): ToolOutcome {
        val value = args.double("value")
        val from = args.string("from").lowercase(Locale.ROOT)
        val to = args.string("to").lowercase(Locale.ROOT)

        val result = when {
            from == to -> value
            // Длина
            from == "km" && to == "mi" -> value * 0.621371
            from == "mi" && to == "km" -> value / 0.621371
            // Масса
            from == "kg" && to == "lb" -> value * 2.20462
            from == "lb" && to == "kg" -> value / 2.20462
            // Температура
            from == "c" && to == "f" -> value * 9 / 5 + 32
            from == "f" && to == "c" -> (value - 32) * 5 / 9
            else -> null
        }

        // Отказ с понятной причиной: модель перескажет её пользователю.
        // Молчаливое `{"value":0}` модель истолкует как реальный ноль.
        if (result == null) {
            return ToolOutcome.failed("Не умею переводить $from → $to")
        }

        val rounded = Math.round(result * 1000) / 1000.0
        return ToolOutcome.json(
            "value" to rounded,
            "unit" to to,
            // Число, а не строка: модель покажет его без «умных» правок.
            "display" to "$rounded $to",
        )
    }
}
