# 项目架构

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics 是一个 Android 手机端悬浮歌词应用。Android 端使用 Kotlin，
联网歌词来源通过 Rust 编写的原生歌词核心接入。

代码按照职责拆分为媒体检测、歌词查询、本地歌词存储、悬浮窗渲染、
设置持久化和界面页面。

## 运行流程

```text
音乐应用
  -> Android 媒体通知 / 媒体会话
  -> MediaNotificationListener
  -> 当前媒体状态广播
      -> MainReceivers
          -> AppMediaController
          -> MainGraph
      -> FloatingLyricsService
          -> LyricsRepository
              -> LocalLyricsProvider
              -> NeteaseLyricsProvider 或 MusixmatchLyricsProvider
              -> LyricsStorage 可选本地保存
          -> FloatingWindowController
          -> FloatingLyricsRenderer
```

主界面和悬浮歌词服务分别接收媒体状态。主界面负责展示状态和处理用户操作，
悬浮歌词服务负责查询歌词、管理悬浮窗并更新歌词内容。

除非调用方明确跳过本地查询，否则本地歌词始终优先。
只有在用户设置允许时，才会执行联网歌词查询。

## Android 端主要包

```text
app/              主界面入口、依赖组装、生命周期协调和功能控制
media/            媒体通知与媒体会话读取，以及所选播放器持久化
lyrics/           歌词查询、歌词来源、解析、导入、显示格式化和存储
floating/         前台服务、悬浮窗控制和歌词渲染
settings/         设置数据模型和设置持久化
ui/               页面、通用组件、导航、主题和小组件
i18n/             本地化工具和文案处理
common/           共享常量
```

## App 外壳

`MainActivity` 是较薄的界面入口，只负责创建 `MainGraph`，
并将创建、恢复和销毁等生命周期事件转交给它处理。

`MainGraph` 是主界面的依赖组装入口和生命周期协调器。
权限结果、界面结果回调、广播接收、页面渲染和具体功能分别拆分到
`controller/`、`host/`、`lifecycle/`、`platform/`、`render/` 和 `state/` 中。

重要文件：

```text
app/MainActivity.kt
app/MainGraph.kt
app/controller/AppMediaController.kt
app/controller/LyricsController.kt
app/controller/FloatingController.kt
app/lifecycle/MainLaunchers.kt
app/lifecycle/MainReceivers.kt
app/render/MainHandRenderer.kt
```

## 媒体检测

`MediaNotificationListener` 读取活跃媒体通知和媒体会话，
并广播当前播放状态。

`MediaSourceStore` 保存用户选中的媒体应用包名，
用于处理多个音乐应用同时存在的情况。

媒体页面展示当前媒体和可用播放器。
刷新操作只更新相关媒体状态，不会重新构建整个界面。

## 歌词查询

`LyricsRepository` 是统一的歌词查询入口。
界面中的歌词操作通常由 `LyricsController` 发起，
悬浮歌词服务也会在媒体发生变化时直接查询歌词。

默认流程如下：

1. 查询本地导入或此前保存的歌词。
2. 本地未命中时，查询设置中选择的联网歌词来源。
3. 根据用户设置，将联网查询结果保存到本地。
4. 启用逐字歌词时，尝试附加本地保存的增强歌词或逐字歌词。

除非请求明确跳过本地查询，否则 `LocalLyricsProvider`
始终优先于联网歌词来源。

## 悬浮歌词

`FloatingLyricsService` 是前台服务和悬浮歌词功能的协调入口。
它负责接收媒体变化、查询歌词、创建前台通知，
并协调悬浮窗控制器和歌词渲染器。

`FloatingWindowController` 负责悬浮窗的创建、更新和移除，
以及窗口位置、样式、锁定和触摸穿透等行为。

`FloatingLyricsRenderer` 负责歌词时间轴、当前歌词定位、
文本更新、切行动画和逐字高亮。

悬浮窗支持样式修改、锁定、触摸穿透、位置保存、歌词偏移、
切行动画，以及存在逐字歌词时的逐字高亮。

## 设置

设置通过 `settings/store/` 下的专用存储类读写。
界面页面不直接操作裸露的 `SharedPreferences` 键。

不同功能的设置分别管理，避免将所有设置读写集中在单个文件中。

## 本地化

较短的界面文案放在 Android 字符串资源中。

较长文本放在 `assets/` 目录中，并按照当前语言读取对应文件。

`i18n/` 提供共享的语言判断、文本格式化和功能文案辅助函数。