# 歌词查询取消

[English](LYRICS_LOOKUP_CANCELLATION.md) · [简体中文](LYRICS_LOOKUP_CANCELLATION.zh-CN.md)

AirLyrics 的歌词查询采用“最新请求优先”模型。这样可以避免快速切歌、连续刷新或切换来源时，把旧歌词回调到 UI。

## 主要类

```text
lyrics/LyricsLookupCancellation.kt
```

`LyricsLookupRunner` 持有单 worker executor。提交新查询时，会先取消上一个 active handle，再启动新请求。

## 取消层级

1. 新请求到来时取消当前 `LyricsLookupHandle`。
2. `Future.cancel(true)` 中断 worker 线程。
3. `LyricsLookupCancellationToken` 标记取消，并在查询阶段之间检查。
4. 回调投递前确认 token 仍然 active，才允许更新调用方。

## 检查阶段

`LyricsRepository` 会在关键步骤前后检查取消：

- 读取设置前。
- 本地 Provider 查询前。
- 联网 Provider 查询前。
- Provider 返回后。
- 本地缓存保存前。
- 返回最终结果前。

Rust/JNI 原生调用边界是协作式取消：请求可能要等 native call 返回或超时后才停止，但已取消结果不会再送到 UI。

## 何时取消

以下场景应取消 active lookup：

- 切歌。
- 手动刷新。
- 切换媒体源。
- 切换歌词来源。
- 本地导入替换当前歌词。
- 悬浮服务关闭。
- Activity / Service 生命周期清理。

## 规则

不要只依赖“结果返回后比较旧歌名”。应该取消任务，并在回调投递前检查 generation token。
