# 歌词模块

[English](LYRICS_MODULE.md) · [简体中文](LYRICS_MODULE.zh-CN.md)

歌词模块负责查询、Provider 路由、解析、本地存储和显示格式化。

## 主流程

```text
LyricsRepository.findLyrics(...)
  -> LocalLyricsProvider.fetch(...)
  -> 设置允许时进入选中的联网 Provider
  -> 可选 LyricsStorage.saveLyrics(...)
  -> 可选附加本地 karaoke / 逐字歌词
```

本地歌词始终优先，因此用户导入的文件和本地修正结果会覆盖联网结果。

## 重要文件

```text
lyrics/LyricsRepository.kt                   歌词查询总入口
lyrics/LyricsFetcher.kt                      旧调用点兼容包装
lyrics/LyricsProvider.kt                     Provider 接口和结果模型
lyrics/LyricsLookupCancellation.kt           单线程、最新请求优先的查询 Runner
lyrics/parser/LrcParser.kt                   LRC 解析和行查找
lyrics/display/LyricsDisplayFormatter.kt     原文 / 翻译显示格式化
lyrics/providers/LocalLyricsProvider.kt      本地导入 / 已保存歌词来源
lyrics/providers/NeteaseLyricsProvider.kt    网易云 Provider 桥接
lyrics/providers/MusixmatchLyricsProvider.kt Musixmatch Provider 桥接
lyrics/storage/                              本地文件、索引与逐字歌词存储
```

## 查询设置

`LyricsSettings` 控制：

- 查询来源：仅本地、网易云音乐、Musixmatch。
- 是否允许联网 fallback。
- 联网成功结果是否自动保存本地。
- 原文 / 翻译显示模式。
- 当前行 / 相邻行显示范围。
- 切行动画。
- 是否使用增强 / 逐字歌词。

## Provider 契约

Provider 返回 `LyricsProviderResult`。Provider 应安全处理无结果，不直接操作 UI。错误或空状态如何显示由 UI 和服务层决定。

## 解析器职责

`LrcParser` 处理：

- 普通时间戳 LRC。
- 一行多个时间戳。
- 紧凑导出的 LRC。
- 原文和翻译合并存储。
- 当前歌词行查找。
- 增强 / 逐字歌词行解析。

## 存储职责

`lyrics/storage/` 处理：

- 管理歌词目录选择。
- 本地歌词文件保存 / 读取 / 删除。
- 歌词索引元数据。
- Karaoke / enhanced 歌词 Codec。
- 歌曲身份归一化。

不要把 UI 行为加入存储类。
