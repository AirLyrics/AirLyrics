# 项目架构

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics 是一个 Android 手机端悬浮歌词应用。Android 端使用 Kotlin，联网歌词 Provider 通过 Rust 原生歌词核心接入。

当前架构按职责拆分：媒体检测、歌词查询、本地歌词存储、悬浮窗渲染、设置持久化和 UI 页面都有独立位置。

## 运行流程

```text
音乐应用
  -> Android 媒体通知 / 媒体会话
  -> MediaNotificationListener
  -> MainActivity / FloatingLyricsService
  -> LyricsRepository
      -> LocalLyricsProvider
      -> NeteaseLyricsProvider 或 MusixmatchLyricsProvider
      -> LyricsStorage 可选本地保存
  -> FloatingLyricsRenderer
  -> FloatingWindowController
```

除非调用方明确跳过本地查询，否则本地歌词始终优先。联网查询只会在用户设置允许时执行。

## Android 模块

```text
app/              Activity 外壳、导航、权限 Launcher 和控制器
media/            媒体通知 / 会话读取，以及选中播放器持久化
lyrics/           歌词 Repository、Provider、解析、显示格式化和存储
floating/         前台服务、WindowManager 控制器和渲染器
settings/         设置数据模型和 SharedPreferences Store
ui/               页面、通用组件、导航、主题和小组件
common/           共享常量
```

## App 外壳

`MainActivity` 只负责 Android 生命周期胶水：Activity Result、权限流程、广播接收器和导航分发。具体功能交给控制器和页面文件。

重要文件：

```text
app/MainActivity.kt
app/MainActivityRenderer.kt
app/MainUiActionsFactory.kt
app/controller/AppMediaController.kt
app/controller/LyricsController.kt
app/controller/FloatingController.kt
```

## 媒体检测

`MediaNotificationListener` 读取活跃媒体通知并广播播放快照。`MediaSourceStore` 记住选中的媒体包名，避免多个音乐应用同时存在时跟错播放器。

媒体页面展示当前媒体与可用播放器。刷新操作只更新相关媒体状态，不重建整个界面。

## 歌词查询

`LyricsRepository` 是歌词查询入口。默认顺序是：

1. 本地导入或已保存歌词。
2. 设置中选择的联网 Provider。
3. 联网结果可选保存到本地。
4. 启用逐字歌词时，可附加本地 enhanced / word-by-word 歌词。

`LyricsFetcher` 仍作为旧调用点的兼容包装。新代码应优先使用 `LyricsRepository` 或 app 层歌词控制器。

## 悬浮歌词

`FloatingLyricsService` 是前台服务协调器。窗口细节属于 `FloatingWindowController`，歌词时间轴和文本更新属于 `FloatingLyricsRenderer`。

悬浮窗支持样式修改、锁定、触摸穿透、位置保存、切行动画，以及存在增强歌词时的逐字高亮。

## 设置

设置通过 `settings/store/` 下的专用 Store 读写。UI 页面不要直接写裸 `SharedPreferences` key，除非正在创建新的 Store。

## 本地化

短 UI 文案放在 Android 资源中，较长的说明文档使用文件管理。见 [本地化指南](LOCALIZATION.zh-CN.md)。
