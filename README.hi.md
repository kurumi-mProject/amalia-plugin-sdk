# Amalia SDK3 — संक्षिप्त परिचय

Amalia Android होस्ट के प्लगइन के लिए स्वतंत्र Kotlin/JVM SDK। SDK3 मौजूदा प्रोटोकॉल रनटाइम के ऊपर लेखक API है, नया लोडर या स्थिर 3.0 ABI रिलीज़ नहीं।

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## शुरुआत

JDK 17, Gradle 9.7.1 और Python 3 चाहिए। JVM लाइब्रेरी प्रकाशित करने और JAR संकलित करने के लिए Android SDK या ऐप स्रोत आवश्यक नहीं हैं। हस्ताक्षरित DEX पैकेज के लिए Android build-tools का D8 और OpenSSL चाहिए।

इस रिपॉज़िटरी की रूट डायरेक्टरी से चलाएँ:

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

स्थानीय Maven रिपॉज़िटरी `platform/build/sdk-repository` में बनती है; सार्वजनिक Maven सेवा या `mavenLocal()` की धारणा नहीं है। पूरी डायरेक्टरी कॉपी करें और पूर्ण पथ दें। CLI प्रोजेक्ट का wrapper, या उसके न होने पर PATH का `gradle` उपयोग करता है; नए प्रोजेक्ट में wrapper नहीं होता।

## अगले कदम और सीमाएँ

`test`, `dev`, `package` और हस्ताक्षर विकल्प CLI गाइड में हैं। लेखक का एकमात्र मैनिफ़ेस्ट `amalia.json` है; `GeneratedManifest` और `platform.json` अपने आप बनते हैं। क्षमताओं की घोषणा अनुमति नहीं देती।

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

P-256 हस्ताक्षरित पैकेज में `platform.json`, `package.sig`, `classes.dex` होते हैं। प्रकाशक पर भरोसा, सक्रियण सहमति और invocation grants अलग कदम हैं। प्लगइन होस्ट प्रक्रिया में विश्वसनीय कोड है, sandbox नहीं। JVM dev fake host और नेटवर्क fixtures उपयोग करता है, Android या वास्तविक HTTP नहीं।

प्रक्रिया बंद होने के बाद पुनर्प्राप्ति, वीडियो/HLS/लाइव स्ट्रीमिंग, OAuth और आठ पूरी production integrations उपलब्ध नहीं हैं। Telegram, smart home और local AI स्पष्ट रूप से ड्राफ़्ट या निष्पादित न किए गए इरादे हैं। स्थिर जाँच और संकलन डिवाइस पर सफलता का प्रमाण नहीं हैं।


[MIT — LICENSE](LICENSE)
