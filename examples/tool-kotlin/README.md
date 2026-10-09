# Example: a tool the model can call (Kotlin)

```
tool-kotlin/
├── plugin.json
└── src/com/example/amalia/counter/UnitsPlugin.kt
```

## What it does

Registers one tool, `convert_units`, which converts km↔mi, kg↔lb and °C↔°F.
Ask Amalia «сколько это в милях — 12 километров» and the model calls the tool
instead of doing the arithmetic itself.

## Why tools exist

Language models are fluent and bad at arithmetic. Anything that must be *exact*
should be computed by code, with the model only deciding **when** to call it and
**how** to phrase the result.

## Anatomy of a tool

```kotlin
AmaliaTool(
    definition = ToolDefinition(
        name = "convert_units",              // snake_case, unique across all plugins
        description = "…",                    // the ONLY thing the model reads to decide
        parameters = listOf(                  // becomes a JSON Schema
            ToolParameter("value", ToolParameter.JsonType.NUMBER, "…"),
            ToolParameter("from",  ToolParameter.JsonType.STRING,  "…",
                          enumValues = listOf("km", "mi")),
        ),
    ),
    handler = { args -> ToolOutcome.json("value" to 19.3) },
)
```

**The description is the interface.** The model does not read your code; it reads
`description` and parameter names. Be concrete about what the tool does *and what
it does not do*.

## Returning a result

| Outcome | When | Effect |
|---|---|---|
| `ToolOutcome.json("key" to value)` | success with data | model reads the JSON and answers |
| `ToolOutcome.done()` | success, nothing to say | `{"status":"ok"}` |
| `ToolOutcome.failed("reason")` | cannot do it | model explains the reason to the user |

Never fake success. Returning `{"value": 0}` for an impossible conversion makes
the model confidently tell the user the answer is zero. `failed(...)` with a
human-readable reason produces an honest sentence instead.

## Rules

- Names must be unique across **all** enabled plugins. The registry keeps the
  first and silently drops the rest — a collision makes your tool invisible.
- Arguments arrive as `ToolArguments`, which coerces the model's type lies
  (`"30"` → `30`, `"true"` → `true`). Use `string/int/double/bool` accessors
  rather than casting.
- Keep handlers fast. If you need I/O, it is fine — the handler is `suspend` —
  but return quickly, because the user is waiting in silence.

## Build and package

```bash
./gradlew jar
$ANDROID_HOME/build-tools/34.0.0/d8 --min-api 26 --output out/ build/libs/units.jar
cp out/classes.dex .
zip -r ../units.amaliaplug plugin.json classes.dex
```

See [docs/packaging.md](../../docs/packaging.md).
