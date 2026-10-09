# Amalia Plugin SDK

**Amalia** एंड्रॉइड के लिए एक वॉइस असिस्टेंट है (Kotlin, Jetpack Compose)। इसका कोर
जानबूझकर छोटा है: वाक् पहचान, भाषा मॉडल और आवाज़ — यही उत्पाद है। बाकी सब कुछ
**प्लगइन** है: थीम, इंजन का चुनाव, डिवाइस टूल, वॉइस कमांड, स्क्रीन, नोटिफिकेशन,
बैकग्राउंड फ़ीचर, अभिवादन वाक्य और चरित्र।

यह रिपॉज़िटरी SDK है: दस्तावेज़, पैकेज फ़ॉर्मैट, एक्सटेंशन कॉन्ट्रैक्ट और चलने
वाले उदाहरण।

> **दूसरी भाषाओं में:**
> [English](README.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
> [Español](README.es.md) · [日本語](README.ja.md) · [Deutsch](README.de.md) ·
> [Français](README.fr.md) · [العربية](README.ar.md)

---

## प्लगइन क्या कर सकता है

| योगदान | इंटरफ़ेस | उदाहरण |
|---|---|---|
| थीम (बैकग्राउंड, एक्सेंट, सर्केडियन सीढ़ी) | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| इंजन: पहचान, LLM, आवाज़ | `EngineContributor` | — |
| मॉडल जिन टूल को बुला सकता है | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| होम स्क्रीन के अभिवादन | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| सिस्टम प्रॉम्प्ट में चरित्र | `PromptContributor` | — |
| ज़रूरी क्षमताएँ घोषित करना | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| **मॉडल से पहले** संभाली जाने वाली कमांड | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| ऐप के अंदर अपनी स्क्रीन | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| बटन वाले नोटिफिकेशन | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| लंबे समय तक बैकग्राउंड (फ़ोरग्राउंड सर्विस) | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| अमालिया बोलते समय दूसरी आवाज़ कम करना | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| होम स्क्रीन के दृश्य को बदलना | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

पूरा संदर्भ: **[docs/extension-points.md](docs/extension-points.md)**

---

## दो तरह के प्लगइन

**1. घोषणात्मक — बिना कोड।** सिर्फ़ `plugin.json`, जिसमें थीम और वाक्य होते हैं।
«दिखने में बदलाव चाहिए» वाली ज़्यादातर माँगों के लिए यही काफ़ी है।

**2. कोड प्लगइन।** `plugin.json` के साथ `classes.dex`, जिसमें `AmaliaPlugin` और
ज़रूरी योगदान लागू करने वाली Kotlin/Java क्लास होती है।

दोनों एक ही **`.amaliaplug`** फ़ाइल के रूप में बँटते हैं — यानी एक zip:

```
mera-plugin.amaliaplug
├── plugin.json     ← अनिवार्य
├── classes.dex     ← वैकल्पिक (सिर्फ़ कोड प्लगइन)
└── ...             ← अपने संसाधन, जिन्हें आप खुद पढ़ते हैं
```

---

## तुरंत शुरुआत — पाँच मिनट में एक थीम

1. `plugin.json` बनाएँ:

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "सूर्यास्त का गर्म काँच",
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
  "phrases": { "hi": ["सूर्यास्त चालू है।"], "en": ["Sunset is on."] }
}
```

2. पैक करें (`plugin.json` संग्रह की **जड़** में होना चाहिए):

```bash
cd mera-plugin
zip -r ../sunset.amaliaplug plugin.json
```

3. ऐप में: **सेटिंग → प्लगइन → फ़ाइल से इंस्टॉल करें** और `.amaliaplug` चुनें।
   या इसे होस्ट करके कैटलॉग में जोड़ें ([docs/catalog.md](docs/catalog.md))।

पूरी गाइड: **[docs/quickstart.md](docs/quickstart.md)**

---

## रिपॉज़िटरी का नक़्शा

| पथ | सामग्री |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | शून्य से इंस्टॉल तक |
| [`docs/manifest.md`](docs/manifest.md) | `plugin.json` के सभी फ़ील्ड |
| [`docs/themes.md`](docs/themes.md) | घोषणात्मक थीम और पैलेट |
| [`docs/packaging.md`](docs/packaging.md) | `classes.dex` बनाना, पैक करना, जाँचना |
| [`docs/extension-points.md`](docs/extension-points.md) | सभी योगदान इंटरफ़ेस |
| [`docs/capabilities.md`](docs/capabilities.md) | क्षमताएँ, विशेष पहुँच, अनुमतियाँ |
| [`docs/localization.md`](docs/localization.md) | बहुभाषी प्लगइन |
| [`docs/catalog.md`](docs/catalog.md) | स्टोर प्रोटोकॉल और प्रकाशन |
| [`docs/security.md`](docs/security.md) | भरोसे का मॉडल — प्रकाशन से पहले पढ़ें |
| [`examples/`](examples) | उदाहरण: सरल से पूरी सुविधा तक |
| [`sdk/api/`](sdk/api) | होस्ट कॉन्ट्रैक्ट की संदर्भ प्रतियाँ |
| [`tools/`](tools) | वैलिडेटर और पैकर |

---

## उदाहरण

| उदाहरण | प्रकार | क्या दिखाता है |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | घोषणात्मक | पूरी थीम: झलक, सीढ़ी, मोटिफ़, वाक्य |
| [`phrases-localized`](examples/phrases-localized) | घोषणात्मक | नौ भाषाओं में अभिवादन और टैग फ़ॉलबैक |
| [`reminder-kotlin`](examples/reminder-kotlin) | कोड | **नई क्षमता**: आवाज़ से रिमाइंडर, बिना UI और अनुमति |
| [`command-kotlin`](examples/command-kotlin) | कोड | मॉडल से पहले संभाली गई कमांड |
| [`tool-kotlin`](examples/tool-kotlin) | कोड | मॉडल द्वारा बुलाया जाने वाला टूल |
| [`notification-kotlin`](examples/notification-kotlin) | कोड | बटन वाला नोटिफिकेशन + बैकग्राउंड स्थिति |
| [`store-catalog.json`](examples/store-catalog.json) | डेटा | न्यूनतम स्टोर कैटलॉग |

```bash
# मैनिफ़ेस्ट या बने पैकेज को जाँचें
python3 tools/validate.py examples/reminder-kotlin/plugin.json

# पैक करें और कैटलॉग प्रविष्टि छापें (sha256 और आकार सहित)
tools/pack.sh examples/reminder-kotlin
```

---

## भाषाएँ

अमालिया नौ भाषाओं में आती है: रूसी (डिफ़ॉल्ट), अंग्रेज़ी, जापानी, जर्मन, स्पैनिश,
फ़्रेंच, चीनी, हिन्दी और अरबी।

प्लगइन का अनुवाद **अनिवार्य नहीं**, पर उपयोगकर्ता जो पढ़ता है वह अनूदित होना चाहिए।
`phrases` की कुंजियाँ भाषा टैग हैं; `en-US` → `en` पर चला जाता है।
देखें **[docs/localization.md](docs/localization.md)**।

---

## बिना स्टोर इंस्टॉल करना

अमालिया डीप-लिंक समझता है, इसलिए प्लगइन लिंक से भी बाँटा जा सकता है:

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

ऐप खुलेगा, कैटलॉग में वह प्रविष्टि ढूँढेगा और स्टोर जैसा ही काँच वाला पुष्टि
संवाद दिखाएगा।

---

## स्थिति और ईमानदार सीमाएँ

- **अभी प्रकाशित Gradle आर्टिफ़ैक्ट नहीं है।** कॉन्ट्रैक्ट होस्ट ऐप के अंदर हैं;
  [`sdk/api/`](sdk/api) में संदर्भ प्रतियाँ हैं जिनके विरुद्ध आप कंपाइल कर सकते हैं।
  अलग `:plugin-api` योजना में है।
- **कोड प्लगइन भरोसेमंद कोड है।** कोई सैंडबॉक्स नहीं: प्लगइन ऐप की प्रक्रिया में
  चलता है। सिर्फ़ भरोसेमंद लेखकों के प्लगइन इंस्टॉल करें।
- **पैकेज हस्ताक्षर अभी नहीं।** अखंडता कैटलॉग के वैकल्पिक `sha256` से जाँची जाती है,
  हस्ताक्षर से नहीं।
- **दृश्य परत खुली नहीं है।** प्लगइन होस्ट के आइकन सेट से आइकन चुनता है
  (`iconName`) और होम स्क्रीन का **स्लॉट** ले सकता है, पर मनमाना चित्र नहीं बना
  सकता: «काँच, रोशनी, हवा» ही उत्पाद है।
- **कुछ असंभव है**: अपने स्ट्रिंग संसाधन लाना, मैनिफ़ेस्ट में `BroadcastReceiver`
  दर्ज करना, सटीक अलार्म माँगना। हर उदाहरण विकल्प बताता है।

---

## लाइसेंस

MIT — देखें [LICENSE](LICENSE)।
