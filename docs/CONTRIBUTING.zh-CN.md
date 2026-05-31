# 贡献指南

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)

感谢帮助 AirLyrics。提交时保持改动范围清晰，便于 review。

## 开发环境

- Android Studio 或 IntelliJ IDEA
- JDK 17
- Android SDK
- 重建原生歌词核心需要 Android NDK
- 重建 `lyrics-core` 需要 Rust toolchain 和 `cargo-ndk`

本地已有 native libraries 时，Kotlin 检查可跳过 Rust 构建：

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

正式 APK 需要包含原生库。

## 提交 PR 前

```bash
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
./scripts/check_localization.sh
```

如果改动涉及本地歌词存储、导入行为、解析器逻辑或 Android 存储权限，还应在真机或模拟器上运行 instrumentation tests：

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

## 代码位置速查

| 任务 | 主要位置 |
| --- | --- |
| 媒体检测 | `media/` 和 `app/controller/AppMediaController.kt` |
| 歌词查询 | `lyrics/LyricsRepository.kt` 和 `lyrics/providers/` |
| 本地歌词存储 | `lyrics/storage/` |
| LRC 解析 | `lyrics/parser/LrcParser.kt` |
| 悬浮窗运行时 | `floating/` |
| 悬浮窗页面 UI | `ui/pages/FloatingPage.kt` 及相关文件 |
| 歌词设置 UI | `ui/pages/settings/LyricsSettingsPage.kt` |
| 系统权限 UI | `ui/pages/settings/SystemSettingsPage.kt` |
| 持久化设置 | `settings/model/` 和 `settings/store/` |
| UI 主题 / tokens | `ui/theme/` 和 `ui/tokens/` |
| 本地化 | Android 字符串资源和 `i18n/` 辅助 |

## 添加一个设置

1. 在 `settings/model/` 添加或扩展数据模型。
2. 在匹配的 `settings/store/*Store.kt` 中添加读写逻辑。
3. 更新对应 UI 页面。
4. 在实际使用这个设置的运行时模块中应用它。
5. 如果行为对用户可见，同步更新文档和测试。

不要在 UI 页面或服务中直接读写裸 `SharedPreferences`，除非正在创建新的 Store。

## 添加歌词 Provider

1. 在 `lyrics/providers/` 下实现 `LyricsProvider`。
2. 注册到 `LyricsRepository`。
3. 在设置中暴露用户可选择的来源。
4. 安全处理网络失败、无结果和模糊匹配。
5. 除非应用设计改变，否则 enhanced / word-by-word 歌词应继续以本地导入优先。

Provider 只返回数据，不直接更新 UI。

## 本地化规则

- 不要随意修改已有 string key。
- 保持 `%1$s` 等 placeholder 不变。
- UI 文案保持简短。
- 不要翻译歌曲名、歌手名、文件名、包名和路径。
- 提交前运行 `./scripts/check_localization.sh`。

## 不要提交的文件

```text
.gradle/
.kotlin/
build/
app/build/
lyrics-core/target/
local.properties
```

生成的 APK 和本机配置不要进仓库。
