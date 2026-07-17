# 项目架构

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics 是一个 Android 手机端悬浮歌词应用。Android 端使用 Kotlin，
联网歌词来源通过 Rust 编写的原生歌词核心接入。

代码按照职责拆分为共享模型、媒体检测、歌词查询、本地歌词存储、悬浮窗渲染、
设置持久化、设计 token 和界面页面。

## 运行流程

```text
音乐应用
  -> Android 媒体通知 / 媒体会话
  -> MediaNotificationListenerService
  -> CurrentMediaBroadcast
      -> MainReceivers
          -> MediaSourceController
          -> MainGraph
      -> FloatingLyricsService
          -> LyricsRepository
              -> LocalLyricsProvider
              -> NeteaseLyricsProvider 或 MusixmatchLyricsProvider
              -> LyricsStorage 可选本地保存
          -> FloatingLyricsWindow
          -> FloatingLyricsRenderer
```

主界面和悬浮歌词服务分别接收媒体状态。主界面负责展示状态和处理用户操作，
悬浮歌词服务负责查询歌词、管理悬浮窗并更新歌词内容。

除非调用方明确跳过本地查询，否则本地歌词始终优先。
只有在用户设置允许时，才会执行联网歌词查询。

## Android 端主要包

```text
app/              主界面入口、依赖组装、生命周期协调和功能控制
core/             跨包共享的稳定模型
design/           不归属于具体页面包的共享 UI token
media/            媒体通知与媒体会话读取，以及所选播放器持久化
lyrics/           歌词查询、歌词来源、解析、导入、显示格式化和存储
floating/         前台服务、悬浮窗控制和歌词渲染
settings/         设置持久化
ui/               页面、通用组件、导航、主题和小组件
i18n/             本地化工具和文案处理
```

## 应用内通信协议

跨组件通信按协议归属到专用对象，不再使用共享常量包集中暴露 action 和 extra。

```text
CurrentMediaBroadcast
负责媒体状态广播。MediaNotificationListenerService 发送当前媒体变化，
MainReceivers / MediaSourceController 和 FloatingLyricsService 分别接收。

FloatingServiceCommand
负责发送给 FloatingLyricsService 的 startService 命令。主界面控制器和前台通知
只构造命令对象，由它统一转换为 Intent；服务端也通过它解析 Intent。

FloatingWindowStateBroadcast
负责悬浮窗可见性、锁定和触摸穿透状态广播。FloatingLyricsService 发送状态，
MainReceivers / FloatingController 接收并更新主界面状态。
```

通知关系：

```text
Android 媒体通知 / 媒体会话
  -> MediaNotificationListenerService
  -> CurrentMediaBroadcast
  -> 主界面和 FloatingLyricsService

FloatingLyricsService 前台通知
  -> FloatingServiceNotification
  -> PendingIntent
  -> FloatingServiceCommand
  -> FloatingLyricsService.handleCommand

FloatingLyricsService 悬浮窗状态变化
  -> FloatingWindowStateBroadcast
  -> MainReceivers
  -> FloatingController
```

## App 外壳

`MainActivity` 是较薄的界面入口，只负责创建 `MainGraph`，
并将创建、恢复和销毁等生命周期事件转交给它处理。

`MainGraph` 是主界面的依赖组装入口和生命周期协调器。
权限结果、界面结果回调、广播接收、页面渲染和具体功能分别拆分到
`controller/`、`contracts/`、`host/`、`lifecycle/`、`platform/`、`render/` 和 `state/` 中。

重要文件：

```text
app/MainActivity.kt
app/MainGraph.kt
app/controller/MediaSourceController.kt
app/controller/LyricsController.kt
app/controller/FloatingController.kt
app/contracts/MainAppContracts.kt
app/platform/PermissionHelper.kt
app/lifecycle/MainLaunchers.kt
app/lifecycle/MainReceivers.kt
app/render/MainHandRenderer.kt
```

## 媒体检测

`MediaNotificationListenerService` 读取活跃媒体通知和媒体会话，
并通过 `CurrentMediaBroadcast` 广播当前播放状态。

`MediaSessionObserver` 负责监听活跃媒体会话、注册 controller 回调，
并在同一媒体应用存在多个 session 时统一选择最合适的 controller。
当前策略由 `CurrentMediaReader` 维护：优先播放中且能产出歌曲标题，
其次能产出歌曲标题，再回退到播放中、有 metadata 或第一个可用 session。

`MediaSourceStore` 保存用户选中的媒体应用包名，
用于处理多个音乐应用同时存在的情况。

媒体页面展示当前媒体和可用播放器。
刷新操作只更新相关媒体状态，不会重新构建整个界面。

## 歌词查询

`LyricsRepository` 是统一的歌词查询入口。
界面中的歌词操作通常由 `LyricsController` 发起，
悬浮歌词服务也会在媒体发生变化时直接查询歌词。
仓库由调用方传入当前 `LyricsSettings` 模型，因此 `lyrics/` 不依赖设置存储实现。

默认流程如下：

1. 查询本地导入或此前保存的歌词。
2. 本地未命中时，查询设置中选择的联网歌词来源。
3. 根据用户设置，将联网查询结果保存到本地。
4. 启用逐字歌词时，尝试附加本地保存的逐字歌词。

除非请求明确跳过本地查询，否则 `LocalLyricsProvider`
始终优先于联网歌词来源。

逐字歌词导入后会生成普通 LRC 作为普通显示模式的回退内容。该普通 LRC 由逐字歌词维护；
用户修改逐字歌词时会重新生成它，移除逐字歌词时也会移除这份生成结果。

## 悬浮歌词

`FloatingLyricsService` 是前台服务和悬浮歌词功能的协调入口。
它负责接收媒体变化、查询歌词、创建前台通知，
并协调悬浮窗实现和歌词渲染器。
它不直接选择或注册媒体 controller；媒体会话选择由 `media/` 层完成。

前台通知由 `FloatingServiceNotification` 创建。通知按钮不会直接暴露 action 字符串，
而是通过 `FloatingServiceCommand` 构造 `PendingIntent`，再回到
`FloatingLyricsService.handleCommand` 执行对应命令。

悬浮窗状态变化通过 `FloatingWindowStateBroadcast` 发送到主界面，
用于同步显示状态、锁定状态和触摸穿透状态。

`FloatingLyricsWindow` 负责悬浮窗的创建、更新和移除，
以及窗口位置、样式、锁定和触摸穿透等行为。

`FloatingLyricsRenderer` 负责歌词时间轴、当前歌词定位、
文本更新、切行动画和逐字高亮。

悬浮窗支持样式修改、锁定、触摸穿透、位置保存、歌词偏移、
切行动画，以及存在逐字歌词时的逐字高亮。

## 设置

设置通过 `settings/store/` 下的专用存储类读写。
界面页面不直接操作裸露的 `SharedPreferences` 键，也不直接读取
`settings/store/`、`lyrics/storage/` 或 `media/` 包内的数据来源。
页面代码通过 UI host 方法接收页面数据，并通过 `MainUiActions` 触发操作。

不同功能的设置分别管理，避免将所有设置读写集中在单个文件中。
共享的设置值模型放在 `core/model/` 下，避免 `settings/`、`lyrics/`、`media/`、
`floating/` 和 `ui/` 互相依赖各自包内模型。

## 本地化

较短的界面文案放在 Android 字符串资源中。

较长文本放在 `assets/` 目录中，并按照当前语言读取对应文件。

`i18n/` 提供共享的语言判断、文本格式化和功能文案辅助函数。
