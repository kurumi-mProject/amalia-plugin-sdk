# Amalia Plugin SDK

**Amalia** ist ein Sprachassistent für Android (Kotlin, Jetpack Compose). Der Kern
ist bewusst klein: Spracherkennung, ein Sprachmodell, eine Stimme — das ist das
Produkt. Alles andere ist ein **Plugin**: Themes, Engines, Gerätewerkzeuge,
Sprachbefehle, Bildschirme, Benachrichtigungen, Hintergrundfunktionen,
Begrüßungsphrasen und Charakterzüge.

Dieses Repository ist das SDK: Dokumentation, Paketformat, Erweiterungsverträge
und lauffähige Beispiele.

> **In anderen Sprachen:**
> [English](README.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
> [Español](README.es.md) · [日本語](README.ja.md) · [Français](README.fr.md) ·
> [हिन्दी](README.hi.md) · [العربية](README.ar.md)

---

## Was ein Plugin kann

| Beitrag | Schnittstelle | Beispiel |
|---|---|---|
| Themes (Hintergrund, Akzente, zirkadiane Leiter) | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| Engines: Erkennung, LLM, Sprachausgabe | `EngineContributor` | — |
| Werkzeuge, die das Modell aufrufen kann | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| Begrüßungsphrasen | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| Charakterblöcke im System-Prompt | `PromptContributor` | — |
| Benötigte Fähigkeiten anmelden | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| Befehle **vor** dem Modell | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| Eigener Bildschirm in der App | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Benachrichtigungen mit Schaltflächen | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| Dauerhafter Hintergrundzustand (Vordergrunddienst) | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| Anderes Audio dämpfen, während Amalia spricht | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Ein Element des Startbildschirms ersetzen | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

Vollständige Referenz: **[docs/extension-points.md](docs/extension-points.md)**

---

## Zwei Arten von Plugins

**1. Deklarativ — ganz ohne Code.** Nur eine `plugin.json` mit Themes und
Phrasen. Das deckt die meisten „soll anders aussehen"-Wünsche ab.

**2. Code-Plugin.** Eine `plugin.json` plus `classes.dex` mit einer
Kotlin/Java-Klasse, die `AmaliaPlugin` und die benötigten Beiträge implementiert.

Beides wird als eine **`.amaliaplug`**-Datei ausgeliefert — ein Zip:

```
mein-plugin.amaliaplug
├── plugin.json     ← Pflicht
├── classes.dex     ← optional (nur Code-Plugins)
└── ...             ← eigene Ressourcen, die du selbst liest
```

---

## Schnellstart — ein Theme in fünf Minuten

1. `plugin.json` anlegen:

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "Warmes Glas im Abendlicht",
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
  "phrases": { "de": ["Sonnenuntergang ist an."], "en": ["Sunset is on."] }
}
```

2. Packen (`plugin.json` muss im **Wurzelverzeichnis** des Archivs liegen):

```bash
cd mein-plugin
zip -r ../sunset.amaliaplug plugin.json
```

3. In der App: **Einstellungen → Plugins → Aus Datei installieren** und die
   `.amaliaplug` wählen. Oder hosten und in einen Katalog aufnehmen
   ([docs/catalog.md](docs/catalog.md)).

Ausführlich: **[docs/quickstart.md](docs/quickstart.md)**

---

## Karte des Repositories

| Pfad | Inhalt |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | Von null zum installierten Plugin |
| [`docs/manifest.md`](docs/manifest.md) | Alle Felder der `plugin.json` |
| [`docs/themes.md`](docs/themes.md) | Deklarative Themes und Paletten |
| [`docs/packaging.md`](docs/packaging.md) | `classes.dex` bauen, packen, prüfen |
| [`docs/extension-points.md`](docs/extension-points.md) | Alle Beitragsschnittstellen |
| [`docs/capabilities.md`](docs/capabilities.md) | Fähigkeiten, Sonderzugriffe, Berechtigungen |
| [`docs/localization.md`](docs/localization.md) | Mehrsprachige Plugins |
| [`docs/catalog.md`](docs/catalog.md) | Store-Protokoll und Veröffentlichung |
| [`docs/security.md`](docs/security.md) | Vertrauensmodell — vor dem Release lesen |
| [`examples/`](examples) | Beispiele von trivial bis vollständig |
| [`sdk/api/`](sdk/api) | Referenzkopien der Host-Verträge |
| [`tools/`](tools) | Validator und Packer |

---

## Beispiele

| Beispiel | Art | Zeigt |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | deklarativ | vollständiges Theme: Vorschau, Leiter, Motiv, Phrasen |
| [`phrases-localized`](examples/phrases-localized) | deklarativ | Begrüßungen in neun Sprachen und Tag-Fallback |
| [`reminder-kotlin`](examples/reminder-kotlin) | Code | **eine neue Fähigkeit**: Spracherinnerungen, ohne UI und Berechtigungen |
| [`command-kotlin`](examples/command-kotlin) | Code | Befehl, der vor dem Modell greift |
| [`tool-kotlin`](examples/tool-kotlin) | Code | ein Werkzeug, das das Modell aufruft |
| [`notification-kotlin`](examples/notification-kotlin) | Code | Benachrichtigung mit Schaltflächen + Hintergrundzustand |
| [`store-catalog.json`](examples/store-catalog.json) | Daten | minimaler Store-Katalog |

```bash
# Manifest oder fertiges Paket prüfen
python3 tools/validate.py examples/reminder-kotlin/plugin.json

# Packen und Katalogeintrag ausgeben (mit sha256 und Größe)
tools/pack.sh examples/reminder-kotlin
```

---

## Sprachen

Amalia erscheint in neun Sprachen: Russisch (Standard), Englisch, Japanisch,
Deutsch, Spanisch, Französisch, Chinesisch, Hindi, Arabisch.

Ein Plugin **muss** nicht übersetzt sein, aber alles, was Nutzer lesen, sollte es
sein. Die Schlüssel in `phrases` sind Sprach-Tags; `en-US` fällt auf `en` zurück.
Siehe **[docs/localization.md](docs/localization.md)**.

---

## Installation ohne Store

Amalia versteht Deep-Links, ein Plugin lässt sich also per URL teilen:

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

Die App öffnet sich, findet den Eintrag im Katalog und zeigt denselben
Glas-Bestätigungsdialog wie im Store.

---

## Stand und ehrliche Einschränkungen

- **Noch kein veröffentlichtes Gradle-Artefakt.** Die Verträge liegen in der
  Host-App; in [`sdk/api/`](sdk/api) liegen Referenzkopien zum Kompilieren. Ein
  eigenständiges `:plugin-api` ist geplant.
- **Code-Plugins sind vertrauenswürdiger Code.** Es gibt keine Sandbox: ein
  Plugin läuft im App-Prozess. Installiere nur Plugins vertrauter Autoren.
- **Noch keine Paketsignatur.** Integrität prüft eine optionale `sha256`-Summe
  aus dem Katalog, keine Signatur.
- **Die visuelle Ebene ist nicht frei.** Ein Plugin wählt ein Icon aus dem Set des
  Hosts (`iconName`) und darf einen **Slot** des Startbildschirms belegen — frei
  zeichnen darf es nicht: „Glas, Licht, Luft" ist das Produkt.
- **Manches ist unmöglich**: eigene String-Ressourcen mitbringen, einen
  `BroadcastReceiver` im Manifest registrieren, exakte Alarme anfordern. Jedes
  Beispiel erklärt den Ersatz.

---

## Lizenz

MIT — siehe [LICENSE](LICENSE).
