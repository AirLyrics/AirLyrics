# 项目架构

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics 使用 Kotlin 实现 Android 应用，Rust 核心负责联网歌词查询，通过 JNI 与应用交互。

## 项目结构

| 目录 | 职责 |
| --- | --- |
| `app/` | 唯一的 Gradle 模块 `:app`，包含 Android 应用和测试 |
| `lyrics-core/` | Rust 歌词查询、匹配与解析 |
| `scripts/` | 构建检查和开发工具 |
| `docs/` | 使用、贡献和架构文档 |

Gradle 通过 `cargo ndk` 构建 `libairlyrics_lyrics.so`，输出到 `app/src/main/jniLibs/`。已有原生库时，可用 `-Pairlyrics.skipRustBuild=true` 跳过 Rust 构建。

## Android 主要职责

| 包 | 职责 |
| --- | --- |
| `app` | 依赖组装、生命周期、ViewModel 和用户操作协调 |
| `ui` | 页面、组件及面向界面的接口 |
| `media` | 媒体会话监听、播放器选择和播放状态 |
| `lyrics` | 歌词查询、导入、解析和存储 |
| `floating` | 前台服务、悬浮窗控制和歌词渲染 |
| `settings` / `displayscope` | 设置持久化 / 按应用控制显示范围 |
| `core` / `design` / `feedback` / `i18n` | 共享模型、设计参数、反馈契约和本地化 |

## 运行流程

```text
音乐应用 → media → 主界面 / FloatingLyricsService
                        → LyricsRepository → 本地存储 / JNI → Rust 歌词源
                        → FloatingLyricsRenderer → 悬浮窗
```

主界面处理用户操作，悬浮服务跟随所选播放器更新歌词。旧媒体快照和过期查询结果会被丢弃；手动搜索期间，自动查询不能覆盖其结果。

导入、编辑或删除歌词后，通过 `LyricsChangedBroadcast` 通知界面和悬浮服务刷新。跨组件操作使用专用广播或命令对象，避免重复定义 action 和 extra。

## 包边界

依赖规则由 `scripts/architecture/policy.json` 维护，以下为自动生成的允许依赖表：

<!-- architecture-policy:start -->
```text
core         -> (none)
design       -> core.model
displayscope -> (none)
feedback     -> (none)
settings     -> core.model, core.prefs, core.color
lyrics       -> core, apple
i18n         -> core.model, core.prefs
i18n.lyrics  -> lyrics.LyricsLookupErrorType, lyrics.LyricsLookupException, lyrics.storage.LyricsStorage
media        -> core.model, core.prefs, i18n.LocalizedServiceContext
ui           -> core.model, core.color, core.text, design, feedback.AirFeedback, i18n, apple
floating     -> core, design, displayscope, feedback, i18n, i18n.lyrics, lyrics, media, settings
app          -> core, design, displayscope, feedback, i18n, i18n.lyrics, lyrics, media, settings, floating, ui, apple
apple        -> (none)
```
<!-- architecture-policy:end -->

- `app` 负责组装功能包；`ui` 通过接口获取数据和操作，不直接访问业务实现或设置存储。
- `lyrics` 与 `media` 互不依赖；`floating` 可以协调二者，但不依赖主界面。
- 授权按最精确的分组匹配，允许 `i18n` 不等于允许 `i18n.lyrics`。接口也不能暴露被禁止的实现类型。

使用 JDK 17 和 Python 3.10+ 检查源码、JVM 依赖及规则一致性：

```bash
./scripts/check_architecture_boundaries.sh
```

`--source-only` 仅用于快速预检查。新增包或调整依赖时，修改规则并填写理由，再用 `--update-docs` 同步中英文表格。检查不覆盖反射字符串、原生代码和外部库内部实现，仍需代码审查。

## 状态与存储

- `MainActivity` 创建 `MainGraph` 组装依赖；`MainViewModel` 管理界面状态，`SavedStateHandle` 保存需要恢复的操作，UI effect 交由 `MainGraph` 执行。
- `LyricsStorage` 统一管理应用目录或 SAF 自定义目录中的歌词和索引。写入串行执行；逐字歌词与生成的普通 LRC 协调更新，失败时尝试回滚。
- 悬浮窗的“期望显示”与实际可见性分开保存，暂停、锁屏或显示范围限制不会清除用户的开启意图。
- 设置通过 `settings/store/` 读写；界面文案使用 Android 字符串资源，较长的帮助和更新记录放在 `assets/`。

## 歌词来源限制与匹配

### Spicy Lyrics

Spicy 支持普通和逐字歌词，需手动启用。歌词和编辑内容仅保留在应用内，获取 30 天后到期，不提供文件导出、分享或备份。显示时保留来源和贡献者署名；请求受限流约束。具体要求见 [Spicy API 使用条款](https://developers.spicylyrics.org/terms)。

构建时，在未跟踪的 `local.properties` 或用户 Gradle 配置中设置 `airlyrics.spicy.clientKey`，或使用环境变量 `AIRLYRICS_SPICY_CLIENT_KEY`。使用已启用客户端访问的 `sl_pk_` key，不要使用 `sl_sk_` secret key 或将实际 key 提交到仓库。未配置时，Spicy 显示为不可用。

### 歌词匹配

手动导入优先于缓存和联网搜索。在线来源按所选优先级查询，共用歌名、歌手、时长和专辑评分；找不到时逐步放宽条件，因此命中不保证是同一录音版本。Spicy 通过 Spotify 搜索候选歌曲，再获取歌词；没有逐字歌词时可保留普通同步歌词。

共用的匹配规则位于 `lyrics-core/src/matching.rs`，详见[歌词匹配](lyrics-matching.md)。
