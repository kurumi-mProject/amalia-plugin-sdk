# Amalia Plugin SDK

**Amalia** مساعد صوتي لأندرويد (Kotlin، Jetpack Compose). نواته صغيرة عن قصد:
التعرف على الكلام، نموذج لغوي، وصوت — هذا هو المنتج. وكل ما تبقى هو **إضافة**:
السمات، واختيار المحرّكات، وأدوات الجهاز، والأوامر الصوتية، والشاشات، والإشعارات،
وميزات الخلفية، وعبارات الترحيب، والشخصية.

هذا المستودع هو الـ SDK: التوثيق، وصيغة الحزمة، وعقود الإضافات، وأمثلة قابلة للتشغيل.

> **بلغات أخرى:**
> [English](README.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
> [Español](README.es.md) · [日本語](README.ja.md) · [Deutsch](README.de.md) ·
> [Français](README.fr.md) · [हिन्दी](README.hi.md)

---

## ما تستطيع الإضافة فعله

| المساهمة | الواجهة | مثال |
|---|---|---|
| السمات (الخلفية، لون التمييز، سلّم اليوم) | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| المحرّكات: التعرف، النموذج، الصوت | `EngineContributor` | — |
| الأدوات التي يستدعيها النموذج | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| عبارات الترحيب | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| مقاطع الشخصية في التوجيه النظامي | `PromptContributor` | — |
| الإعلان عن القدرات المطلوبة | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| أوامر تُعالج **قبل** النموذج | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| شاشة خاصة بك داخل التطبيق | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| إشعارات بأزرار | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| حالة خلفية طويلة الأمد (خدمة أمامية) | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| خفض صوت آخر أثناء كلام أماليا | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| استبدال عنصر مرئي في الشاشة الرئيسية | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

المرجع الكامل: **[docs/extension-points.md](docs/extension-points.md)**

---

## نوعان من الإضافات

**١. تصريحية — بلا كود.** ملف `plugin.json` فقط، بسمات وعبارات. يكفي هذا لمعظم
طلبات «اجعله يبدو مختلفًا».

**٢. إضافة برمجية.** ملف `plugin.json` مع `classes.dex` يحتوي على صنف Kotlin/Java
يُنفّذ `AmaliaPlugin` والمساهمات التي يحتاجها.

يُوزَّع الاثنان كملف واحد **`.amaliaplug`** — أي ملف zip:

```
my-plugin.amaliaplug
├── plugin.json     ← إلزامي
├── classes.dex     ← اختياري (للإضافات البرمجية فقط)
└── ...             ← مواردك الخاصة، تقرأها بنفسك
```

---

## البداية السريعة — سمة في خمس دقائق

١. أنشئ `plugin.json`:

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "زجاج دافئ عند الغروب",
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
  "phrases": { "ar": ["الغروب مُفعّل."], "en": ["Sunset is on."] }
}
```

٢. احزم الملف (`plugin.json` يجب أن يكون في **جذر** الأرشيف):

```bash
cd my-plugin
zip -r ../sunset.amaliaplug plugin.json
```

٣. في التطبيق: **الإعدادات ← الإضافات ← التثبيت من ملف**، ثم اختر `.amaliaplug`.
أو استضفه وأضفه إلى كتالوج ([docs/catalog.md](docs/catalog.md)).

الدليل الكامل: **[docs/quickstart.md](docs/quickstart.md)**

---

## خريطة المستودع

| المسار | المحتوى |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | من الصفر إلى إضافة مثبَّتة |
| [`docs/manifest.md`](docs/manifest.md) | كل حقول `plugin.json` |
| [`docs/themes.md`](docs/themes.md) | السمات التصريحية واللوحات |
| [`docs/packaging.md`](docs/packaging.md) | بناء `classes.dex` والحزم والتحقق |
| [`docs/extension-points.md`](docs/extension-points.md) | كل واجهات المساهمة |
| [`docs/capabilities.md`](docs/capabilities.md) | القدرات والوصول الخاص والأذونات |
| [`docs/localization.md`](docs/localization.md) | إضافات متعددة اللغات |
| [`docs/catalog.md`](docs/catalog.md) | بروتوكول المتجر والنشر |
| [`docs/security.md`](docs/security.md) | نموذج الثقة — اقرأه قبل النشر |
| [`examples/`](examples) | أمثلة من البسيط إلى ميزة كاملة |
| [`sdk/api/`](sdk/api) | نسخ مرجعية لعقود المضيف |
| [`tools/`](tools) | أداة تحقق وأداة حزم |

---

## الأمثلة

| المثال | النوع | يوضّح |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | تصريحي | سمة كاملة: معاينة، سلّم، زخرفة، عبارات |
| [`phrases-localized`](examples/phrases-localized) | تصريحي | ترحيب بتسع لغات وتراجع حسب الوسم |
| [`reminder-kotlin`](examples/reminder-kotlin) | برمجي | **قدرة جديدة**: تذكيرات صوتية بلا واجهة ولا أذونات |
| [`command-kotlin`](examples/command-kotlin) | برمجي | أمر يُعالج قبل النموذج |
| [`tool-kotlin`](examples/tool-kotlin) | برمجي | أداة يستدعيها النموذج |
| [`notification-kotlin`](examples/notification-kotlin) | برمجي | إشعار بأزرار + حالة خلفية |
| [`store-catalog.json`](examples/store-catalog.json) | بيانات | كتالوج متجر بسيط |

```bash
# تحقّق من بيان أو حزمة جاهزة
python3 tools/validate.py examples/reminder-kotlin/plugin.json

# احزم واطبع مدخل الكتالوج (مع sha256 والحجم)
tools/pack.sh examples/reminder-kotlin
```

---

## اللغات

تأتي أماليا بتسع لغات: الروسية (افتراضيًا)، الإنجليزية، اليابانية، الألمانية،
الإسبانية، الفرنسية، الصينية، الهندية والعربية.

الإضافة **غير ملزمة** بالترجمة، لكن كل ما يقرأه المستخدم ينبغي أن يُترجم. مفاتيح
`phrases` هي وسوم اللغة؛ و`en-US` يتراجع إلى `en`.
انظر **[docs/localization.md](docs/localization.md)**.

---

## التثبيت بدون المتجر

تفهم أماليا الروابط المباشرة، لذا يمكنك مشاركة إضافة برابط:

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

يفتح التطبيق، ويجد المدخل في كتالوجه، ويعرض نفس مربع التأكيد الزجاجي الموجود في المتجر.

---

## الحالة وحدود صريحة

- **لا يوجد بعد أثر Gradle منشور.** العقود موجودة داخل تطبيق المضيف، وفي
  [`sdk/api/`](sdk/api) نسخ مرجعية يمكنك البناء مقابلها. ووحدة `:plugin-api`
  المستقلة مخطّطة.
- **الإضافة البرمجية كود موثوق.** لا يوجد صندوق رمل: تعمل داخل عملية التطبيق.
  ثبّت فقط إضافات مؤلفين تثق بهم.
- **لا يوجد توقيع حزم بعد.** السلامة تُتحقق ببصمة `sha256` اختيارية من الكتالوج،
  لا بتوقيع.
- **الطبقة البصرية ليست حرة.** تختار الإضافة أيقونة من مجموعة المضيف (`iconName`)
  ويمكنها احتلال **فتحة** في الشاشة الرئيسية، لكنها لا ترسم بحرية:
  «زجاج، ضوء، هواء» هو المنتج نفسه.
- **أمور مستحيلة**: إضافة موارد نصية خاصة، أو تسجيل `BroadcastReceiver` في البيان،
  أو طلب منبّهات دقيقة. كل مثال يشرح البديل.

---

## الترخيص

MIT — انظر [LICENSE](LICENSE).
