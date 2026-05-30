# AirLyrics 本地歌词测试样例

这些文件用于手动测试本地普通歌词、enhanced LRC 逐字歌词、长句换行、错误提示和翻译模式边界。歌词格式说明见 `docs/LYRICS_FORMAT.md`。

建议测试顺序：

1. 导入 `plain-basic.lrc`，选择“普通歌词”，确认显示为普通 LRC。
2. 导入 `karaoke-zh.lrc`，选择“逐字歌词”，确认导入后悬浮窗显示中会刷新，并显示“普通 + 逐字”。
3. 导入 `karaoke-ja.lrc`，测试日文逐字高亮。
4. 导入 `karaoke-en-long.lrc`，把悬浮窗宽度调窄、字体调大，确认长英文句能自然换行，不裁切。
5. 导入 `karaoke-invalid-plain-selected-as-karaoke.lrc`，选择“逐字歌词”，确认弹出 enhanced LRC 格式错误提示。
6. 导入 `karaoke-with-translation-style.lrc`，用于观察逐字歌词与含翻译行文本的边界表现。

逐字歌词只支持本地 enhanced LRC，例如：

```lrc
[00:01.00]<00:01.00>你<00:01.20>好
```
