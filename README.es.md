# Amalia Plugin SDK

**Amalia** es un asistente de voz para Android (Kotlin, Jetpack Compose). Su núcleo
es deliberadamente pequeño: reconocimiento de voz, un modelo de lenguaje y una voz
— eso es el producto. Todo lo demás es un **plugin**: temas, elección de motores,
herramientas del dispositivo, comandos de voz, pantallas, notificaciones, funciones
en segundo plano, frases de saludo y rasgos de carácter.

Este repositorio es el SDK: documentación, formato del paquete, contratos de
extensión y ejemplos ejecutables.

> **En otros idiomas:**
> [English](README.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
> [日本語](README.ja.md) · [Deutsch](README.de.md) · [Français](README.fr.md) ·
> [हिन्दी](README.hi.md) · [العربية](README.ar.md)

---

## Qué puede hacer un plugin

| Contribución | Interfaz | Ejemplo |
|---|---|---|
| Temas (fondo, acentos, escala circadiana) | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| Motores: reconocimiento, LLM, síntesis | `EngineContributor` | — |
| Herramientas que el modelo puede llamar | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| Frases de saludo | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| Bloques de carácter en el prompt | `PromptContributor` | — |
| Declarar las capacidades necesarias | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| Comandos gestionados **antes** del modelo | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| Tu propia pantalla dentro de la app | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Notificaciones con botones | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| Estado en segundo plano (servicio en primer plano) | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| Silenciar otro audio mientras Amalia habla | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Sustituir un elemento visual de la pantalla principal | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

Referencia completa: **[docs/extension-points.md](docs/extension-points.md)**

---

## Dos tipos de plugins

**1. Declarativo — sin código.** Solo un `plugin.json` con temas y frases. Cubre
la mayoría de las peticiones de «que se vea distinto».

**2. Plugin de código.** Un `plugin.json` más un `classes.dex` con una clase
Kotlin/Java que implementa `AmaliaPlugin` y las contribuciones que necesite.

Ambos se distribuyen como un único archivo **`.amaliaplug`** — un zip:

```
mi-plugin.amaliaplug
├── plugin.json     ← obligatorio
├── classes.dex     ← opcional (solo plugins de código)
└── ...             ← tus propios recursos, los lees tú
```

---

## Inicio rápido — un tema en cinco minutos

1. Crea `plugin.json`:

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "Vidrio cálido al atardecer",
  "iconName": "palette",
  "minHostVersion": "2.0.0",
  "themes": [
    {
      "id": "com.example.theme.sunset.dusk",
      "name": "Sunset Dusk",
      "staticDark": {
        "name": "Sunset",
        "stops": [
          { "color": "#1A0F14", "position": 0.0 },
          { "color": "#2A1418", "position": 0.55 },
          { "color": "#120A0D", "position": 1.0 }
        ],
        "isDark": true,
        "cct": 2600,
        "auroras": ["#FF7A59", "#FF3D7F"],
        "motif": "FIREFLY"
      }
    }
  ],
  "phrases": { "es": ["Atardecer activado."], "en": ["Sunset is on."] }
}
```

2. Empaqueta (`plugin.json` debe estar en la **raíz** del archivo):

```bash
cd mi-plugin
zip -r ../sunset.amaliaplug plugin.json
```

3. En la app: **Ajustes → Plugins → Instalar desde archivo** y elige el
   `.amaliaplug`. O súbelo a un servidor y publícalo en un catálogo
   ([docs/catalog.md](docs/catalog.md)).

Guía completa: **[docs/quickstart.md](docs/quickstart.md)**

---

## Mapa del repositorio

| Ruta | Contenido |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | De cero a un plugin instalado |
| [`docs/manifest.md`](docs/manifest.md) | Todos los campos de `plugin.json` |
| [`docs/themes.md`](docs/themes.md) | Temas declarativos y paletas |
| [`docs/packaging.md`](docs/packaging.md) | Compilar `classes.dex`, empaquetar, verificar |
| [`docs/extension-points.md`](docs/extension-points.md) | Todas las interfaces de contribución |
| [`docs/capabilities.md`](docs/capabilities.md) | Capacidades, accesos especiales, permisos |
| [`docs/localization.md`](docs/localization.md) | Plugins multilingües |
| [`docs/catalog.md`](docs/catalog.md) | Protocolo de la tienda y publicación |
| [`docs/security.md`](docs/security.md) | Modelo de confianza — léelo antes de publicar |
| [`examples/`](examples) | Ejemplos: de lo trivial a una función completa |
| [`sdk/api/`](sdk/api) | Copias de referencia de los contratos del host |
| [`tools/`](tools) | Validador y empaquetador |

---

## Ejemplos

| Ejemplo | Tipo | Qué muestra |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | declarativo | tema completo: vista previa, escala, motivo, frases |
| [`phrases-localized`](examples/phrases-localized) | declarativo | saludos en nueve idiomas y respaldo por etiqueta |
| [`reminder-kotlin`](examples/reminder-kotlin) | código | **una capacidad nueva**: recordatorios por voz, sin UI ni permisos |
| [`command-kotlin`](examples/command-kotlin) | código | comando gestionado antes del modelo |
| [`tool-kotlin`](examples/tool-kotlin) | código | una herramienta que el modelo puede llamar |
| [`notification-kotlin`](examples/notification-kotlin) | código | notificación con botones + estado en segundo plano |
| [`store-catalog.json`](examples/store-catalog.json) | datos | catálogo mínimo para la tienda |

```bash
# validar un manifiesto o un paquete ya construido
python3 tools/validate.py examples/reminder-kotlin/plugin.json

# empaquetar e imprimir la entrada del catálogo (con sha256 y tamaño)
tools/pack.sh examples/reminder-kotlin
```

---

## Idiomas

Amalia está disponible en nueve idiomas: ruso (por defecto), inglés, japonés,
alemán, español, francés, chino, hindi y árabe.

Un plugin **no está obligado** a traducirse, pero todo lo que lee el usuario
debería estarlo. Las claves de `phrases` son etiquetas de idioma; `en-US` recurre
a `en`. Ver **[docs/localization.md](docs/localization.md)**.

---

## Instalar sin la tienda

Amalia entiende enlaces directos, así que puedes compartir un plugin con una URL:

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

La app se abre, encuentra la entrada en su catálogo y muestra el mismo diálogo de
confirmación de cristal que en la tienda.

---

## Estado y límites honestos

- **Aún no hay artefacto de Gradle publicado.** Los contratos viven dentro de la
  app; en [`sdk/api/`](sdk/api) hay copias de referencia contra las que compilar.
  Un `:plugin-api` independiente está planeado.
- **Un plugin de código es código de confianza.** No hay sandbox: se ejecuta en el
  proceso de la app. Instala solo plugins de autores en los que confíes.
- **Aún no hay firma de paquetes.** La integridad se comprueba con un `sha256`
  opcional del catálogo, no con una firma.
- **La capa visual no es libre.** Un plugin elige un icono del conjunto del host
  (`iconName`) y puede ocupar un **hueco** de la pantalla principal, pero no
  dibujar a su antojo: «cristal, luz, aire» es el producto.
- **Hay cosas imposibles**: añadir recursos de texto propios, registrar un
  `BroadcastReceiver` en el manifiesto o pedir alarmas exactas. Cada ejemplo
  explica con qué se sustituyen.

---

## Licencia

MIT — ver [LICENSE](LICENSE).
