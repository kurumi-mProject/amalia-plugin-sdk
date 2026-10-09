# Amalia Plugin SDK

**Amalia** 是一款 Android 语音助手（Kotlin、Jetpack Compose）。它的内核刻意做得很小：
语音识别、语言模型、语音输出——这就是产品本身。其余一切都是**插件**：主题、引擎选择、
设备工具、语音命令、界面、通知、后台功能、问候语、性格设定。

本仓库是 SDK：文档、包格式、扩展契约和可直接运行的示例。

> **其他语言：**
> [English](README.md) · [Русский](README.ru.md) · [Español](README.es.md) ·
> [日本語](README.ja.md) · [Deutsch](README.de.md) · [Français](README.fr.md) ·
> [हिन्दी](README.hi.md) · [العربية](README.ar.md)

---

## 插件能做什么

| 贡献 | 接口 | 示例 |
|---|---|---|
| 主题（背景、强调色、昼夜色阶） | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| 引擎：识别、LLM、合成 | `EngineContributor` | — |
| 供模型调用的工具 | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| 主屏问候语 | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| 系统提示词中的人物设定 | `PromptContributor` | — |
| 声明所需的能力 | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| **模型之前**处理的语音命令 | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| 应用内的自定义界面 | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| 带按钮的通知 | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| 长时间后台运行（前台服务） | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| 阿玛莉亚说话时压低其他音量 | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| 替换主屏视觉（波形、灯、问候） | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

完整参考：**[docs/extension-points.md](docs/extension-points.md)**

---

## 两种插件

**1. 声明式——零代码。** 只有一个 `plugin.json`，描述主题和问候语。大多数
「换个样子」的需求靠它就够了。

**2. 代码插件。** `plugin.json` 加一个 `classes.dex`，其中是实现了 `AmaliaPlugin`
以及所需贡献接口的 Kotlin/Java 类。

两者都打包成一个 **`.amaliaplug`** 文件（就是一个 zip）：

```
my-plugin.amaliaplug
├── plugin.json     ← 必需
├── classes.dex     ← 可选（仅代码插件）
└── ...             ← 你自己的资源，由你自己读取
```

---

## 五分钟做一个主题

1. 新建 `plugin.json`：

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "温暖的黄昏玻璃",
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
  "phrases": { "en": ["Sunset is on."], "zh": ["日落已开启。"] }
}
```

2. 打包（`plugin.json` 必须在压缩包**根目录**）：

```bash
cd my-plugin
zip -r ../sunset.amaliaplug plugin.json
```

3. 在应用中：**设置 → 插件 → 从文件安装**，选择 `.amaliaplug`。也可以把它
   放到服务器并加入目录（[docs/catalog.md](docs/catalog.md)）。

详见：**[docs/quickstart.md](docs/quickstart.md)**

---

## 仓库导航

| 路径 | 内容 |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | 从零到装好插件 |
| [`docs/manifest.md`](docs/manifest.md) | `plugin.json` 全部字段 |
| [`docs/themes.md`](docs/themes.md) | 声明式主题与调色板 |
| [`docs/packaging.md`](docs/packaging.md) | 生成 `classes.dex`、打包、校验 |
| [`docs/extension-points.md`](docs/extension-points.md) | 全部贡献接口 |
| [`docs/capabilities.md`](docs/capabilities.md) | 能力、特殊访问、权限 |
| [`docs/localization.md`](docs/localization.md) | 多语言插件 |
| [`docs/catalog.md`](docs/catalog.md) | 商店协议与发布 |
| [`docs/security.md`](docs/security.md) | 信任模型（发布前必读） |
| [`examples/`](examples) | 示例：从最简单到完整功能 |
| [`sdk/api/`](sdk/api) | 宿主契约的参考副本 |
| [`tools/`](tools) | 校验器与打包脚本 |

---

## 示例

| 示例 | 类型 | 说明 |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | 声明式 | 完整主题：预览、昼夜色阶、装饰、问候语 |
| [`phrases-localized`](examples/phrases-localized) | 声明式 | 九种语言的问候语与语言回退 |
| [`reminder-kotlin`](examples/reminder-kotlin) | 代码 | **新增能力**：语音提醒，无界面、无权限 |
| [`command-kotlin`](examples/command-kotlin) | 代码 | 在模型之前处理的命令 |
| [`tool-kotlin`](examples/tool-kotlin) | 代码 | 供模型调用的工具 |
| [`notification-kotlin`](examples/notification-kotlin) | 代码 | 带按钮的通知 + 后台状态 |
| [`store-catalog.json`](examples/store-catalog.json) | 数据 | 最小商店目录 |

```bash
# 校验清单或已打包文件
python3 tools/validate.py examples/reminder-kotlin/plugin.json

# 打包并输出目录条目（含 sha256 和大小）
tools/pack.sh examples/reminder-kotlin
```

---

## 多语言

阿玛莉亚内置九种语言：俄语（默认）、英语、日语、德语、西班牙语、法语、中文、印地语、阿拉伯语。

插件**不必**翻译，但用户能读到的文字最好翻译。`phrases` 的键是语言标签，
`en-US` 会回退到 `en`。详见 **[docs/localization.md](docs/localization.md)**。

---

## 不用商店也能安装

阿玛莉亚支持深链，所以可以直接分享链接：

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

应用会打开、在目录中找到该条目，并显示与商店里相同的玻璃确认对话框。

---

## 现状与诚实的限制

- **还没有发布 Gradle 构件。** 契约目前位于宿主应用内；[`sdk/api/`](sdk/api)
  提供了可直接编译的参考副本，独立的 `:plugin-api` 在计划中。
- **代码插件即信任代码。** 没有沙箱，插件运行在应用进程内。只安装你信任
  作者的插件。
- **还没有包签名。** 完整性依靠目录中的可选 `sha256`，而不是签名。
- **不允许自由绘制界面。** 插件从宿主图标集中选择图标（`iconName`），可以
  占用主屏**槽位**，但不能随意作画：「玻璃、光、空气」就是产品本身。
- **有些事做不到**：不能自带字符串资源、不能在清单里注册 `BroadcastReceiver`、
  不能申请精确闹钟。每个示例都说明了用什么替代。

---

## 许可证

MIT — 见 [LICENSE](LICENSE)。
