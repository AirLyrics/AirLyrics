# 本地歌词测试样例

[English](README.md) · [简体中文](README.zh-CN.md)

这些文件用于手动测试本地普通歌词、增强 / 逐字 LRC、长句换行、错误导入路径和翻译风格边界。

另见：[歌词格式](../LYRICS_FORMAT.zh-CN.md)。

## 建议测试顺序

1. 将 `plain-basic.lrc` 作为普通歌词导入，确认逐行 LRC 显示正常。
2. 将 `karaoke-zh.lrc` 作为增强歌词导入，确认逐字高亮正常。
3. 导入 `karaoke-ja.lrc`，检查日文逐字歌词。
4. 导入 `karaoke-en-long.lrc`，将悬浮窗宽度调窄、字号调大，检查换行。
5. 将 `karaoke-invalid-plain-selected-as-karaoke.lrc` 作为增强歌词导入，确认格式错误路径正常。
6. 导入 `karaoke-with-translation-style.lrc`，观察增强歌词与类似翻译行的边界表现。

## 最小增强歌词示例

```lrc
[00:01.00]<00:01.00>Hello <00:01.30>AirLyrics
```
