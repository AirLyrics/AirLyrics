# 贡献指南

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)

感谢您愿意为 AirLyrics 做贡献。

为了方便 review，请尽量让每个 PR 只做一件事。不要把无关的格式化、重构和功能改动混在一起。

在说明中可以使用图片或者视频，会更加清晰

## 提交 PR 前

请在提交 PR 前尽量运行以下基础检查：

```bash
./gradlew :app:lintDebug -Pairlyrics.skipRustBuild=true
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
./scripts/check_localization.sh
```

如果修改了 Android UI 或应用集成逻辑，也可以在不重新构建 Rust 的情况下构建 debug APK：

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

如果修改了 Rust 歌词核心，请额外运行：

```bash
cd lyrics-core
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

如果改动涉及歌词解析、本地歌词存储、歌词导入、Android 存储权限或 SAF 文件夹行为，请额外在真机或模拟器上运行：

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

注意：`connectedDebugAndroidTest` 可能会卸载或覆盖设备上已安装的 AirLyrics。运行前请确认测试设备上的数据可以被清除，建议使用测试机或模拟器。

## 相关规范

### 添加一个设置

新增设置时，请按现有结构接入，不要只在 UI 页面里临时保存状态。

1. 在 `settings/model/` 添加或扩展设置数据模型。
2. 在对应的 `settings/store/*Store.kt` 中添加读取和保存逻辑。
3. 更新对应 UI 页面，让用户可以查看或修改这个设置。
4. 在真正使用该设置的模块中读取并应用它，例如悬浮窗渲染、歌词查询或歌词存储逻辑。
5. 如果这个设置改变了用户可见行为，请同步更新相关文档或测试。

不要在 UI 页面或 Service 中直接读写裸 `SharedPreferences`，除非您正在创建新的 Store。

### 添加歌词 Provider

AirLyrics 是悬浮歌词应用，不是找歌词项目。除非目前的歌词源已经基本无法满足正常使用，否则不建议继续添加在线歌词源，优先建议用户手动导入本地歌词。

确实需要新增歌词来源时，请保持 Provider 的职责单一。

1. 在 `lyrics/providers/` 下实现 `LyricsProvider`。
2. 注册到 `LyricsRepository`。
3. 如果需要让用户手动选择来源，请在设置中暴露这个 Provider。
4. 安全处理网络失败、无结果和模糊匹配。
5. 除非应用设计改变，否则 enhanced / word-by-word 歌词应继续以本地导入优先。

Provider 只负责获取和返回歌词数据，不应该直接更新 UI。

### 本地化规则

修改 UI 文案时，请注意：

- 不要随意修改已有 string key。
- 保持 `%1$s`、`%2$d` 等 placeholder 不变。
- UI 文案尽量简短。
- 不要翻译歌曲名、歌手名、文件名、包名和路径。
- 提交前运行 `./scripts/check_localization.sh`。

如果新增了 string 资源，请同时补充对应语言的文本，避免界面出现缺失翻译。

## 不要提交的文件

注意不要提交本地构建产物或本机配置，例如：

```text
.gradle/
.kotlin/
build/
app/build/
lyrics-core/target/
local.properties
```

生成的 APK、签名文件、本机 SDK 路径和 IDE 缓存都不应该进入仓库。
