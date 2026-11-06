# Amalia SDK3 — دليل مختصر

SDK مستقل بلغة Kotlin/JVM لإضافات مضيف Amalia على Android. SDK3 واجهة تأليف فوق وقت تشغيل البروتوكول الحالي، وليس محمّلاً جديداً أو إصدار ABI مستقراً برقم 3.0.

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## البدء

المتطلبات: JDK 17 وGradle 9.7.1 وPython 3. نشر مكتبات JVM وتجميع JAR لا يحتاجان Android SDK أو مصادر التطبيق. إنشاء حزمة DEX موقعة يحتاج D8 من Android build-tools وOpenSSL.

نفّذ من جذر هذا المستودع:

```bash
gradle -p platform \
  :protocol:publishSdkPublicationToSdkDistributionRepository \
  :runtime:publishSdkPublicationToSdkDistributionRepository
export AMALIA_SDK_REPOSITORY="$PWD/platform/build/sdk-repository"
./tools/amalia create plugin weather
./tools/amalia add network weather --domain api.example.com
./tools/amalia validate weather
gradle -p weather jar -PamaliaSdkRepository="$AMALIA_SDK_REPOSITORY"
```

مستودع Maven المحلي هو `platform/build/sdk-repository`؛ لا يُفترض وجود خدمة Maven عامة أو استخدام `mavenLocal()`. انسخ المجلد كاملاً واستخدم مساراً مطلقاً. تستخدم CLI غلاف المشروع إن وُجد، وإلا تستخدم `gradle` من PATH؛ المشاريع الجديدة لا تتضمن غلافاً.

## الخطوات التالية والحدود

يوضح دليل CLI أوامر `test` و`dev` و`package` وخيارات التوقيع. ملف التأليف الوحيد هو `amalia.json`؛ يُولَّد `GeneratedManifest` و`platform.json` تلقائياً. التصريح بالقدرات لا يمنح صلاحيات.

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

تحتوي الحزم الموقعة بمفتاح P-256 على `platform.json` و`package.sig` و`classes.dex`. الثقة بالناشر والموافقة على التفعيل ومنح صلاحية الاستدعاء خطوات منفصلة. الإضافات كود موثوق داخل عملية المضيف، وليست sandbox. يستخدم JVM dev مضيفاً وهمياً وfixtures للشبكة، وليس Android أو HTTP حقيقياً.

لا يوجد استرداد بعد موت العملية أو فيديو/HLS/بث مباشر أو OAuth أو ثمانية تكاملات إنتاجية مكتملة. تظل أمثلة Telegram والمنزل الذكي والذكاء المحلي مسودات أو نوايا غير منفذة بوضوح. التحقق الثابت والتجميع لا يثبتان العمل على الجهاز.


[MIT — LICENSE](LICENSE)
