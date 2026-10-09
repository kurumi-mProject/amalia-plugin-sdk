# Amalia Plugin SDK

**Amalia** は Android 向けの音声アシスタントです（Kotlin / Jetpack Compose）。
コアは意図的に小さく作られています。音声認識・言語モデル・声——それ自体が製品です。
それ以外はすべて**プラグイン**です：テーマ、エンジンの選択、デバイス操作ツール、
音声コマンド、画面、通知、バックグラウンド機能、挨拶フレーズ、性格の設定。

このリポジトリは SDK です：ドキュメント、パッケージ形式、拡張コントラクト、
そして実際に動くサンプル。

> **他の言語：**
> [English](README.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
> [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) ·
> [हिन्दी](README.hi.md) · [العربية](README.ar.md)

---

## プラグインでできること

| 提供するもの | インターフェース | 例 |
|---|---|---|
| テーマ（背景・アクセント・概日リズムの階調） | `ThemeContributor` | [theme-neon-glass](examples/theme-neon-glass) |
| エンジン：認識・LLM・合成 | `EngineContributor` | — |
| モデルが呼べるツール | `ToolContributor` | [tool-kotlin](examples/tool-kotlin) |
| ホーム画面の挨拶フレーズ | `PhraseContributor` | [phrases-localized](examples/phrases-localized) |
| システムプロンプトの性格ブロック | `PromptContributor` | — |
| 必要なケイパビリティの宣言 | `CapabilityContributor` | [command-kotlin](examples/command-kotlin) |
| **モデルより前**に処理する音声コマンド | `CommandContributor` | [reminder-kotlin](examples/reminder-kotlin) |
| アプリ内の独自画面 | `ScreenContributor` | [docs/extension-points.md](docs/extension-points.md) |
| ボタン付き通知 | `NotificationContributor` | [notification-kotlin](examples/notification-kotlin) |
| 長時間のバックグラウンド動作（フォアグラウンドサービス） | `BackgroundContributor` | [notification-kotlin](examples/notification-kotlin) |
| アマリアが話す間、他の音を絞る | `AudioSessionContributor` | [docs/extension-points.md](docs/extension-points.md) |
| ホーム画面の見た目の差し替え（波形・ランプ・挨拶） | `WidgetContributor` | [docs/extension-points.md](docs/extension-points.md) |

完全なリファレンス：**[docs/extension-points.md](docs/extension-points.md)**

---

## 2 種類のプラグイン

**1. 宣言型 — コード不要。** テーマとフレーズだけを書いた `plugin.json`。
「見た目を変えたい」という要望のほとんどはこれで足ります。

**2. コードプラグイン。** `plugin.json` と、`AmaliaPlugin` および必要な
コントリビューションを実装した Kotlin/Java クラスを含む `classes.dex`。

どちらも 1 つの **`.amaliaplug`** ファイル（zip）として配布します。

```
my-plugin.amaliaplug
├── plugin.json     ← 必須
├── classes.dex     ← 任意（コードプラグインのみ）
└── ...             ← 独自リソース。読み込みは自分で行います
```

---

## クイックスタート — 5 分でテーマ

1. `plugin.json` を作成します。

```json
{
  "id": "com.example.theme.sunset",
  "name": "Sunset",
  "version": "1.0.0",
  "author": "you",
  "category": "theme",
  "description": "夕暮れの暖かなガラス",
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
  "phrases": { "ja": ["夕暮れをオンにしたよ。"], "en": ["Sunset is on."] }
}
```

2. パッケージ化（`plugin.json` はアーカイブの**ルート**に置きます）。

```bash
cd my-plugin
zip -r ../sunset.amaliaplug plugin.json
```

3. アプリで：**設定 → プラグイン → ファイルからインストール** を選び、
   `.amaliaplug` を指定します。サーバーに置いてカタログに載せることもできます
   （[docs/catalog.md](docs/catalog.md)）。

詳細：**[docs/quickstart.md](docs/quickstart.md)**

---

## リポジトリの地図

| パス | 内容 |
|---|---|
| [`docs/quickstart.md`](docs/quickstart.md) | ゼロからインストールまで |
| [`docs/manifest.md`](docs/manifest.md) | `plugin.json` の全フィールド |
| [`docs/themes.md`](docs/themes.md) | 宣言型テーマとパレット |
| [`docs/packaging.md`](docs/packaging.md) | `classes.dex` のビルド・梱包・確認 |
| [`docs/extension-points.md`](docs/extension-points.md) | すべてのコントリビューション |
| [`docs/capabilities.md`](docs/capabilities.md) | ケイパビリティ・特別アクセス・権限 |
| [`docs/localization.md`](docs/localization.md) | 多言語プラグイン |
| [`docs/catalog.md`](docs/catalog.md) | ストアのプロトコルと公開 |
| [`docs/security.md`](docs/security.md) | 信頼モデル（公開前に必読） |
| [`examples/`](examples) | サンプル集 |
| [`sdk/api/`](sdk/api) | ホスト側コントラクトの参照コピー |
| [`tools/`](tools) | バリデータとパッケージャ |

---

## サンプル

| サンプル | 種類 | 内容 |
|---|---|---|
| [`theme-neon-glass`](examples/theme-neon-glass) | 宣言型 | 完全なテーマ：プレビュー・階調・モチーフ・フレーズ |
| [`phrases-localized`](examples/phrases-localized) | 宣言型 | 9 言語の挨拶とタグのフォールバック |
| [`reminder-kotlin`](examples/reminder-kotlin) | コード | **新しい機能**：音声リマインダー（UI も権限も不要） |
| [`command-kotlin`](examples/command-kotlin) | コード | モデルより先に処理されるコマンド |
| [`tool-kotlin`](examples/tool-kotlin) | コード | モデルが呼ぶツール |
| [`notification-kotlin`](examples/notification-kotlin) | コード | ボタン付き通知＋バックグラウンド状態 |
| [`store-catalog.json`](examples/store-catalog.json) | データ | 最小のストアカタログ |

```bash
# マニフェスト、または完成したパッケージを検証
python3 tools/validate.py examples/reminder-kotlin/plugin.json

# パッケージ化してカタログ項目を出力（sha256 とサイズ付き）
tools/pack.sh examples/reminder-kotlin
```

---

## 言語対応

Amalia は 9 言語で提供されます：ロシア語（既定）・英語・日本語・ドイツ語・
スペイン語・フランス語・中国語・ヒンディー語・アラビア語。

プラグインの翻訳は**必須ではありません**が、ユーザーが読むものは翻訳すべきです。
`phrases` のキーは言語タグで、`en-US` は `en` にフォールバックします。
詳細：**[docs/localization.md](docs/localization.md)**

---

## ストアを使わずにインストール

Amalia はディープリンクに対応しているので、URL で配布できます。

```
https://mirabel.tech/plugin?id=com.example.theme.sunset
```

アプリが開き、カタログから該当項目を探し、ストアと同じガラスの確認ダイアログを
表示します。

---

## 現状と正直な制限

- **公開された Gradle アーティファクトはまだありません。** コントラクトはホスト
  アプリ内にあり、[`sdk/api/`](sdk/api) にコンパイル用の参照コピーがあります。
  独立した `:plugin-api` は予定です。
- **コードプラグインは信頼されたコードです。** サンドボックスはなく、アプリの
  プロセス内で動作します。信頼できる作者のものだけを入れてください。
- **パッケージ署名はまだありません。** 完全性はカタログの任意の `sha256` で
  確認され、署名ではありません。
- **見た目は自由に描けません。** アイコンはホストのセットから選び（`iconName`）、
  ホーム画面の**スロット**を占有できますが、自由に描画はできません。
  「ガラス・光・空気」が製品そのものです。
- **できないこと**：独自の文字列リソースの同梱、マニフェストへの
  `BroadcastReceiver` 登録、正確なアラームの要求。各サンプルが代替手段を説明します。

---

## ライセンス

MIT — [LICENSE](LICENSE) を参照。
