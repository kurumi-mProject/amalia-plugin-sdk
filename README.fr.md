# Amalia Plugin SDK

**Amalia** est un assistant vocal pour Android (Kotlin, Jetpack Compose). Son cœur
est volontairement petit : reconnaissance vocale, modèle de langage, voix — c'est
le produit. Tout le reste est un **plugin** : thèmes, choix des moteurs, outils de
l'appareil, commandes vocales, écrans, notifications, fonctions d'arrière-plan,
phrases d'accueil, traits de caractère.

Ce dépôt est le SDK : documentation, format du paquet, contrats d'extension et
exemples exécutables.

> **Dans d'autres langues :**
> [English](README.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
> [Español](README.es.md) · [日本語](README.ja.md) · [Deutsch](README.de.md) ·
> [हिन्दी](README.hi.md) · [العربية](README.ar.md)

---

## Ce qu'un plugin peut faire

| Contribution | Interface | Exemple |
|---|---|---|
| Thèmes (fond, accents, échelle circadienne) | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| Moteurs : reconnaissance, LLM, synthèse | `EngineContributor` | — |
| Outils que le modèle peut appeler | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| Phrases d'accueil | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| Blocs de caractère dans le prompt | `PromptContributor` | — |
| Déclarer les capacités nécessaires | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| Commandes traitées **avant** le modèle | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| Votre propre écran dans l'app | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Notifications avec boutons | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| État d'arrière-plan durable (service au premier plan) | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| Baisser l'audio pendant qu'Amalia parle | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| Remplacer un élément visuel de l'écran principal | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

Référence complète : **[docs/extension-points.md](docs/extension-points.md)**

---

## Deux types de plugins

**1. Déclaratif — sans code.** Un simple `plugin.json` avec des thèmes et des
phrases. Cela couvre la plupart des demandes « que ça ait une autre allure ».

**2. Plugin de code.** Un `plugin.json` plus un `classes.dex` contenant une classe
Kotlin/Java qui implémente `AmaliaPlugin` et les contributions nécessaires.

Les deux se distribuent en un seul fichier **`.amaliaplug`** — un zip :

```
mon-plugin.amaliaplug
├── plugin.json     ← obligatoire
├── classes.dex     ← facultatif (plugins de code uniquement)
└── ...             ← vos ressources, que vous lisez vous-même
```

---

## Démarrage rapide — un thème en cinq minutes

1. Créez `plugin.json` :

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "Verre chaud au coucher du soleil",
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
  "phrases": { "fr": ["Coucher de soleil activé."], "en": ["Sunset is on."] }
}
```

2. Empaquetez (`plugin.json` doit être à la **racine** de l'archive) :

```bash
cd mon-plugin
zip -r ../sunset.amaliaplug plugin.json
```

3. Dans l'app : **Réglages → Plugins → Installer depuis un fichier**, puis
   choisissez le `.amaliaplug`. Ou hébergez-le et publiez-le dans un catalogue
   ([docs/catalog.md](docs/catalog.md)).

Guide détaillé : **[docs/quickstart.md](docs/quickstart.md)**

---

## Carte du dépôt

| Chemin | Contenu |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | De zéro à un plugin installé |
| [`docs/manifest.md`](docs/manifest.md) | Tous les champs de `plugin.json` |
| [`docs/themes.md`](docs/themes.md) | Thèmes déclaratifs et palettes |
| [`docs/packaging.md`](docs/packaging.md) | Compiler `classes.dex`, empaqueter, vérifier |
| [`docs/extension-points.md`](docs/extension-points.md) | Toutes les interfaces de contribution |
| [`docs/capabilities.md`](docs/capabilities.md) | Capacités, accès spéciaux, permissions |
| [`docs/localization.md`](docs/localization.md) | Plugins multilingues |
| [`docs/catalog.md`](docs/catalog.md) | Protocole du store et publication |
| [`docs/security.md`](docs/security.md) | Modèle de confiance — à lire avant publication |
| [`examples/`](examples) | Exemples, du trivial à une fonction complète |
| [`sdk/api/`](sdk/api) | Copies de référence des contrats de l'hôte |
| [`tools/`](tools) | Validateur et empaqueteur |

---

## Exemples

| Exemple | Type | Montre |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | déclaratif | thème complet : aperçu, échelle, motif, phrases |
| [`phrases-localized`](examples/phrases-localized) | déclaratif | salutations en neuf langues et repli par étiquette |
| [`reminder-kotlin`](examples/reminder-kotlin) | code | **une nouvelle capacité** : rappels vocaux, sans UI ni permissions |
| [`command-kotlin`](examples/command-kotlin) | code | commande traitée avant le modèle |
| [`tool-kotlin`](examples/tool-kotlin) | code | un outil que le modèle peut appeler |
| [`notification-kotlin`](examples/notification-kotlin) | code | notification avec boutons + état d'arrière-plan |
| [`store-catalog.json`](examples/store-catalog.json) | données | catalogue minimal pour le store |

```bash
# valider un manifeste ou un paquet déjà construit
python3 tools/validate.py examples/reminder-kotlin/plugin.json

# empaqueter et afficher l'entrée de catalogue (avec sha256 et taille)
tools/pack.sh examples/reminder-kotlin
```

---

## Langues

Amalia existe en neuf langues : russe (par défaut), anglais, japonais, allemand,
espagnol, français, chinois, hindi, arabe.

Un plugin **n'est pas obligé** d'être traduit, mais tout ce que lit l'utilisateur
devrait l'être. Les clés de `phrases` sont des étiquettes de langue ; `en-US`
retombe sur `en`. Voir **[docs/localization.md](docs/localization.md)**.

---

## Installer sans le store

Amalia comprend les liens profonds, un plugin peut donc se partager par URL :

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

L'app s'ouvre, trouve l'entrée dans son catalogue et affiche la même boîte de
confirmation en verre que dans le store.

---

## État et limites honnêtes

- **Pas encore d'artefact Gradle publié.** Les contrats vivent dans l'app hôte ;
  [`sdk/api/`](sdk/api) contient des copies de référence pour compiler. Un
  `:plugin-api` autonome est prévu.
- **Un plugin de code est du code de confiance.** Pas de bac à sable : il
  s'exécute dans le processus de l'app. N'installez que des plugins d'auteurs de
  confiance.
- **Pas encore de signature de paquet.** L'intégrité repose sur un `sha256`
  facultatif du catalogue, pas sur une signature.
- **La couche visuelle n'est pas libre.** Un plugin choisit une icône dans le jeu
  de l'hôte (`iconName`) et peut occuper un **emplacement** de l'écran principal,
  mais ne dessine pas à sa guise : « verre, lumière, air » est le produit.
- **Certaines choses sont impossibles** : apporter ses propres ressources de
  chaînes, déclarer un `BroadcastReceiver` au manifeste, demander des alarmes
  exactes. Chaque exemple explique le remplacement.

---

## Licence

MIT — voir [LICENSE](LICENSE).
