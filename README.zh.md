# Amalia SDK3 — 简明入口

面向 Amalia Android 宿主插件的独立 Kotlin/JVM SDK。SDK3 是现有协议运行时之上的开发接口，不是新加载器，也不是稳定的 3.0 ABI 发布。

- `org.amalia.platform:amalia-protocol:0.2.0-SNAPSHOT`
- `org.amalia.platform:amalia-runtime:0.2.0-SNAPSHOT`

[English / README](README.md) · [SDK / CLI](docs/SDK_CLI.md) ·
[SDK3_STATUS](docs/SDK3_STATUS.md) ·
[SDK_GUIDE_RU](docs/SDK_GUIDE_RU.md)

## 开始使用

需要 JDK 17、Gradle 9.7.1 和 Python 3。发布 JVM 库、编译 JAR 不需要 Android SDK 或应用源码。生成签名 DEX 包另需 Android build-tools 中的 D8 和 OpenSSL。

在本仓库根目录执行：

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

本地 Maven 仓库位于 `platform/build/sdk-repository`，不依赖公开 Maven 服务，也不是 `mavenLocal()`。转移时复制完整目录并传入绝对路径。CLI 优先使用项目的 wrapper，否则使用 PATH 中的 `gradle`；新项目不自带 wrapper。

## 后续步骤与限制

CLI 指南介绍 `test`、`dev`、`package` 和签名选项。`amalia.json` 是唯一的开发者清单；`GeneratedManifest` 和 `platform.json` 自动生成。声明能力不会授予权限。

[examples/sdk3](examples/sdk3/README.md) ·
[platform/protocol](platform/protocol) · [platform/runtime](platform/runtime)

P-256 签名包包含 `platform.json`、`package.sig`、`classes.dex`。发布者信任、激活同意和调用授权必须分别处理。插件是宿主进程内的受信任代码，不是沙箱。JVM dev 使用 fake host 和网络 fixtures，不连接 Android，也不执行真实 HTTP。

尚无进程终止后的恢复、视频/HLS/实时流、OAuth 或八个完整生产集成。Telegram、智能家居和本地 AI 明确仍是草稿或未执行意图。静态校验和编译成功不等于设备验收通过。


[MIT — LICENSE](LICENSE)
