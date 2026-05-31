# Musixmatch 测试

[English](MUSIXMATCH_TESTING.md) · [简体中文](MUSIXMATCH_TESTING.zh-CN.md)

Musixmatch 是可选择的联网歌词来源之一。它适合国际歌曲，但结果取决于 Musixmatch 覆盖情况和网络行为。

## 选择 Musixmatch

1. 打开设置。
2. 进入歌词设置。
3. 选择 Musixmatch 作为歌词来源。
4. 保持联网 fallback 开启。
5. 播放标题和歌手信息干净的歌曲。
6. 刷新歌词。

## 推荐冒烟测试

使用一首热门歌曲，最好具备：

- 清晰标题。
- 清晰歌手。
- 没有很长的 remix / live / version 后缀。
- 时长稳定。

第一次测试尽量避开翻唱、Live、加速版和地区版本。

## 翻译行为

Musixmatch 翻译是否存在取决于来源结果。AirLyrics 不保证每首歌都有翻译行。

如果要观察翻译效果，把悬浮歌词内容模式设为原文 + 翻译或仅翻译。

## 预期结果

成功查询可能得到：

- 只有原文歌词。
- 原文歌词和翻译。
- 没有可用结果。
- Provider 错误或频率限制。

没有可用结果不应导致崩溃。用户仍然可以手动导入本地歌词。

## 调试

```bash
adb logcat | grep -E 'AirLyricsLyrics|Musixmatch|translation|airlyrics'
```

检查：

- 当前来源是否为 Musixmatch。
- 联网 fallback 是否开启。
- 本地歌词是否已经优先命中。
- 音乐应用是否暴露正确标题和歌手。
- Provider 是否返回无歌词、受限歌词或临时网络错误。
