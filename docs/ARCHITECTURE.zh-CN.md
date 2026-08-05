# 项目架构

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics 是一个 Android 悬浮歌词应用。Android 应用使用 Kotlin 编写，联网歌词来源由
Rust 原生核心实现，并通过 JNI 暴露给 Android 端。

代码按照职责划分为媒体检测、歌词查询与存储、悬浮窗渲染、设置持久化、本地化、
界面渲染和应用层协调。

## 项目目录

```text
app/              Android 应用模块和 Kotlin 源码
lyrics-core/      编译为原生库的 Rust 歌词查询核心
scripts/          仓库检查和开发辅助脚本
docs/             用户、贡献、歌词格式和架构文档
```

Gradle 项目只包含一个 Android 模块 `:app`。`lyrics-core/` 是 Cargo crate，而不是 Gradle
子项目。App 模块的 `buildRustLyrics` 任务通过 `cargo ndk` 构建该 crate，并将
`libairlyrics_lyrics.so` 放入 `app/src/main/jniLibs/`。除非传入
`-Pairlyrics.skipRustBuild=true`，常规 pre-build 任务都会依赖该任务。

## 运行流程

```text
音乐应用
  -> Android 媒体会话和通知生命周期
  -> MediaNotificationListenerService
      -> MediaSessionObserver
          -> CurrentMediaReader
          -> CurrentMediaBroadcast
              -> MainReceivers
                  -> MediaSourceController
                  -> 主界面局部刷新
              -> FloatingLyricsService
                  -> MediaSnapshotGate
                  -> LyricsLookupRunner
                      -> LyricsRepository
                          -> LocalPlainLyricsProvider
                              -> LyricsStorage
                          -> NeteasePlainLyricsProvider 或 MusixmatchPlainLyricsProvider
                              -> JNI
                              -> lyrics-core
                          -> 可选本地保存和本地逐字歌词附加
                  -> FloatingLyricsRenderer
                  -> FloatingLyricsWindow
```

主界面和悬浮服务分别消费媒体广播。主界面负责展示状态和处理用户操作；悬浮服务只接受
所选媒体应用包的更新，负责查询歌词、维护播放时间并刷新悬浮窗。

悬浮歌词处于工作状态时，服务还会通过 `CurrentMediaReader` 定期读取所选媒体会话。
这样可以补偿延迟或遗漏的监听器更新，并保持所选 session 同步。`MediaSnapshotGate`
会拒绝序号更旧的快照，避免过期回调让播放状态倒退。

歌词查询采用“最新请求优先”。当歌曲变化或发起新的刷新请求时，`LyricsLookupRunner`
会取消此前的 Kotlin worker 和对应原生查询；悬浮服务在应用结果前还会核对请求 key。

歌词导入成功后使用另一条持久化变更流程：

```text
歌词导入
  -> MainLyricsWorkflow
  -> LyricsController
  -> LyricsStorage
  -> LyricsChangedBroadcast
      -> MainReceivers -> 刷新受影响界面
      -> FloatingLyricsService -> 变更歌曲是当前歌曲时重新加载
```

## Android 端主要包

```text
app/              依赖组装、生命周期协调、控制器、工作流和 UI 适配器
core/             依赖稳定的模型、颜色工具和偏好设置抽象
design/           共享 UI 设计 token
media/            媒体会话监听、当前媒体模型、广播和媒体来源持久化
lyrics/           查询、取消、歌词来源、解析、导入、格式化和存储
floating/         前台服务、服务命令、悬浮窗控制和歌词渲染
settings/         各功能设置持久化和 Toast 策略
ui/               页面、组件、导航、主题、UI 模型和异步界面工具
i18n/             语言选择、本地化 assets 和用户文案格式化
```

## 包边界

当前 Kotlin 顶层包的依赖关系如下：

```text
core      -> （无）
design    -> （无）
settings  -> core
lyrics    -> core
i18n      -> core, lyrics
media     -> core, i18n
ui        -> core, design, i18n
floating  -> core, design, i18n, lyrics, media, settings
app       -> core, design, floating, i18n, lyrics, media, settings, ui
```

箭头表示“导入自”，生成的 `R` 类和平台库未列出。

主要边界规则如下：

- `core/` 和 `design/` 不依赖功能包。
- `lyrics/` 与 `media/` 互不依赖，也不依赖 `app/`、`floating/`、`settings/` 或 `ui/`。
- `ui/` 不导入具体的媒体、歌词、设置或悬浮窗实现；页面通过 `ui/model/` 下的接口接收
  面向界面的数据和操作。
- `floating/` 可以协调媒体、歌词和设置，但不依赖主应用外壳或 UI 页面。
- `app/` 是允许连接所有功能包的依赖组装层。

CI 通过 `scripts/check_architecture_boundaries.sh` 强制检查这些导入限制。

## 应用内通信协议

跨组件通信由专用协议对象负责。发送方和接收方不得各自重复定义原始 action 或 extra 名称。

```text
CurrentMediaBroadcast
负责媒体更新和媒体来源丢失广播。MediaNotificationListenerService 发送；
MainReceivers / MediaSourceController 与 FloatingLyricsService 分别消费。

FloatingServiceCommand
负责通过 startForegroundService 或 PendingIntent 发送给 FloatingLyricsService 的命令。
调用方构造有类型的命令对象，服务端也通过同一对象解析命令。

FloatingWindowStateBroadcast
负责悬浮窗可见性、锁定和触摸穿透状态广播。FloatingLyricsService 发送真实窗口状态，
MainReceivers / FloatingController 据此同步主界面。

LyricsChangedBroadcast
负责按 SongIdentity 标识持久歌词变更。LyricsController 在导入成功后发送；主界面进行刷新，
悬浮服务只在变更歌曲为当前歌曲时重新加载。
```

这些广播限定在应用包内，并以 not exported 方式注册。前台通知由
`FloatingServiceNotification` 创建；通知操作通过 `FloatingServiceCommand` 构造
`PendingIntent`，再回到 `FloatingLyricsService.handleCommand`。

`AppLocalProtocolGuardTest` 确保应用内 action 字符串只存在于四个协议所有者文件中。

## App 外壳与 UI 边界

`MainActivity` 是较薄的 Android 入口，只创建 `MainGraph`，并转发生命周期与状态保存回调。

`MainGraph` 是主界面的依赖组装入口和生命周期协调器。它组装控制器、Activity Result
launcher、广播接收器、UI host、渲染器、歌词工作流和 App I/O executor，同时在 Activity
重建时保存导航状态和待处理的歌词导入状态。

具体职责拆分如下：

```text
controller/       媒体来源、歌词和悬浮功能协调
contracts/        小型应用层依赖接口
host/             UI host 能力和 MainUiActions 的适配器
lifecycle/        Activity Result launcher 和广播注册
platform/         Android 权限和导航辅助工具
render/           主界面构建、View 引用和定向刷新
state/            主界面状态和待处理操作状态
workflow/         多步骤歌词导入和目录选择流程
```

`ui/pages/` 下的手写页面依赖 `MainUiHost` 等 UI 模型，不直接读取功能存储。
`app/host/` 将媒体、歌词、设置和悬浮状态转换为页面数据，并把 `MainUiActions` 映射到控制器。
`LatestUiTaskRunner` 会阻止较旧的异步页面加载覆盖较新的界面状态。

重要文件：

```text
app/MainActivity.kt
app/MainGraph.kt
app/controller/MediaSourceController.kt
app/controller/LyricsController.kt
app/controller/FloatingController.kt
app/workflow/MainLyricsWorkflow.kt
app/lifecycle/MainLaunchers.kt
app/lifecycle/MainReceivers.kt
app/host/MainActivityUiHost.kt
app/render/MainHandRenderer.kt
ui/model/MainUiHost.kt
ui/model/MainUiActions.kt
```

## 媒体检测

`MediaNotificationListenerService` 是通知访问权限的边界。Android 通知变化会触发防抖后的
媒体会话重新扫描；`MediaSessionObserver` 则监听活跃 session 变化，以及 controller 的
metadata 和播放状态回调。

Observer 为每个媒体 session token 注册一个回调，并为每个媒体应用包发布最合适的可用
controller。`CurrentMediaReader` 维护 Observer、主界面和悬浮服务共用的选择规则：依次优先
有标题且正在播放、有标题、正在播放、有 metadata，以及第一个可用 controller。

`CurrentMediaInfo` 包含来源包名、歌曲 metadata、播放状态、估算位置和单调递增的快照序号。
`CurrentMediaBroadcast` 负责在 Android 组件之间传递该模型。

`MediaSourceStore` 持久化用户选中的应用包名。当多个播放器同时活跃时，该选择会限定
媒体展示、歌词导入与删除，以及悬浮服务接收的更新。

## 歌词查询

`LyricsRepository` 是统一查询入口。界面歌词操作由 `LyricsController` 和
`MainLyricsWorkflow` 协调；所选媒体变化或明确刷新歌词时，悬浮服务会调用该仓库。

调用方将 `LyricsSettings` 值传入仓库，因此 `lyrics/` 不依赖设置存储实现。常规查询顺序为：

1. 读取本地导入或此前缓存的普通歌词。
2. 本地歌词不存在且允许联网查询时，调用选中的联网歌词来源。
3. 开启自动保存或强制保存时，将成功的联网结果保存到本地。
4. 开启逐字显示且存在本地逐字数据时，将逐字时间信息附加到结果。

除非请求明确跳过本地查询，否则本地普通歌词始终优先。存在逐字歌词时，联网自动保存不会
替换普通歌词。

逐字歌词以本地导入为主。导入后会生成普通 LRC，供普通显示模式回退使用；编辑逐字歌词会
重新生成该文件，移除逐字歌词时，如果普通歌词仍是自动生成版本，也会一并移除。同一首歌
不能同时导入一份独立维护的普通 LRC 和一份逐字 LRC。

## 原生歌词核心

`NeteasePlainLyricsProvider` 和 `MusixmatchPlainLyricsProvider` 将 Kotlin provider 协议
适配到 JNI。`LyricsNativeLibrary` 加载 `libairlyrics_lyrics.so`，各来源的 JNI 对象将歌曲
metadata、翻译语言和原生查询 ID 传入 Rust 核心。

Rust 核心负责搜索并评分候选歌曲、获取普通与翻译 LRC，然后返回包含稳定成功和错误字段的
JSON 结果。Kotlin 将 JSON 映射为 `LyricsProviderResult` 或有类型的
`LyricsLookupException`。取消 ID 允许新请求在原生查询的网络阶段之间终止旧任务。

`lyrics-core/testdata/native-contract/` 下的 Rust 与 Kotlin 共用契约 fixture 会验证原生
结果结构。

## 歌词存储

`LyricsStorage` 是本地歌词持久化的公共 facade。具体实现按路径、文件 I/O、文件命名、
索引访问、列表、编辑、删除、普通歌词和逐字歌词拆分为专用辅助类。

默认后端是 App 专属文件目录；用户选择的目录通过 Android Storage Access Framework
使用可持久化的读写权限。受管理歌词位于 `lyrics/` 目录，`lyrics_index.json` 记录其索引。

`SongIdentity` 统一规范化歌曲匹配和稳定存储 key。所有存储操作通过 facade 串行执行，
避免并发导入交错修改索引与文件。逐字歌词导入会将逐字时间数据、自动生成的普通歌词和
索引作为一次协调操作；开始前会保存现有状态，后续写入失败时尝试回滚。编辑逐字歌词时，
也会在持有同一存储锁的情况下重新生成普通歌词。

最近歌词列表和编辑也统一经过 facade，UI 不直接修改索引或歌词文件。

## 悬浮歌词

`FloatingLyricsService` 是前台服务的协调入口。主类持有共享运行状态，命令处理、所选媒体
观察、歌词查询、暂停可见性、通知快捷控制和协议 key 分别拆分到专用文件。

服务本身不实现媒体 session 选择，而是使用 media 层的 `CurrentMediaReader`，并通过
`MediaSourceStore` 过滤所有更新。持久化的“期望显示”状态与真实窗口可见性分开维护，
真实状态通过 `FloatingWindowStateBroadcast` 报告给主界面。

`FloatingLyricsWindow` 负责 `WindowManager` 操作，包括创建、移除、位置保存、样式应用、
拖动、锁定和触摸穿透 flag。窗口操作失败后会收敛为隐藏状态，并广播该真实状态。

`FloatingLyricsRenderer` 负责 LRC 时间轴、播放位置估算、歌词偏移、当前与相邻歌词选择、
原文/翻译显示模式、切行动画和逐字高亮。

开启自动隐藏/显示后，歌曲暂停会暂时移除窗口，但不会清除用户的“期望显示”设置。
所选媒体观察仍会继续，因此恢复播放时可以重新显示窗口。

## 设置

各功能设置通过 `settings/store/` 下的专用 store 读写：

```text
AppSettingsStore             全局 App 和 Toast 行为
FloatingLyricsStyleStore     悬浮窗样式、行为、位置和预览状态
LyricsOffsetStore            按歌曲保存的时间偏移
LyricsSettingsStore          歌词查询和显示偏好
QuickFloatingStore           持久化的悬浮窗期望显示状态
ThemeSettingsStore           主界面主题
```

UI 页面不直接访问裸露的 `SharedPreferences` key，也不读取具体的设置、歌词存储或媒体数据源。
共享设置值模型位于 `core/model/`，让不同功能包可以交换稳定值，而无需依赖彼此的 store。

## 本地化

较短的界面文案存放在 Android string resource 中，较长的帮助和更新记录文本存放在
`assets/` 下，并按照当前语言加载。

`LanguageSettingsStore` 持久化跟随系统、English 或简体中文模式，并应用到 Activity 和
Service。`i18n/` 下的其他辅助类负责格式化媒体状态、设置值、查询错误、歌词偏移和悬浮样式名称。

## 架构保障

- `scripts/check_architecture_boundaries.sh` 检查禁止出现的顶层包导入。
- `AppLocalProtocolGuardTest` 防止应用内 action 字符串离开协议所有者。
- 原生结果契约测试确保 Rust JSON 与 Kotlin 解析保持一致。
- 存储原子性、歌曲标识、最新结果门控和悬浮服务生命周期行为由对应的单元测试、
  Robolectric 测试和 instrumentation 测试覆盖。
