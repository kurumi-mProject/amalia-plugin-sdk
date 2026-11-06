# Amalia SDK3 — 簡易ガイド

Amalia Android ホストのプラグイン向け独立 Kotlin/JVM SDK です。SDK3 は既存のプロトコルランタイム上の開発用ファサードであり、新しいローダーや安定版 3.0 ABI ではありません。

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## はじめに

JDK 17、Gradle 9.7.1、Python 3 が必要です。JVM ライブラリの公開と JAR のコンパイルには Android SDK やアプリのソースは不要です。署名付き DEX パッケージの作成には Android build-tools の D8 と OpenSSL が必要です。

このリポジトリのルートで実行します：

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

ローカル Maven リポジトリは `platform/build/sdk-repository` です。公開 Maven サービスや `mavenLocal()` は前提としていません。移動時はディレクトリ全体をコピーし、絶対パスを指定してください。CLI はプロジェクトの wrapper、なければ PATH の `gradle` を使用します。新規プロジェクトには wrapper がありません。

## 次の手順と制限

`test`、`dev`、`package` と署名オプションは CLI ガイドを参照してください。開発者が編集するマニフェストは `amalia.json` のみで、`GeneratedManifest` と `platform.json` は生成されます。機能の宣言だけでは権限は付与されません。

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

P-256 署名付きパッケージは `platform.json`、`package.sig`、`classes.dex` を含みます。発行者の信頼設定、有効化への同意、呼び出し権限は別々に必要です。プラグインはホストプロセス内の信頼されたコードであり、サンドボックスではありません。JVM dev は fake host とネットワーク fixtures を使い、Android や実際の HTTP には接続しません。

プロセス終了後の復元、動画/HLS/ライブ配信、OAuth、8 件すべての完成した本番連携は未実装です。Telegram、スマートホーム、ローカル AI は明示的な下書き・未実行の意図です。静的検証やコンパイルだけでは実機での動作を証明できません。


[MIT — LICENSE](LICENSE)
