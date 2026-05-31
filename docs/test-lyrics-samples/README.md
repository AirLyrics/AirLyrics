# Test Lyrics Samples

[English](README.md) · [简体中文](README.zh-CN.md)

These files are used for manual testing of local normal lyrics, enhanced / word-by-word LRC, long-line wrapping, invalid enhanced imports and translation-style edge cases.

See also: [Lyrics Format](../LYRICS_FORMAT.md).

## Suggested order

1. Import `plain-basic.lrc` as normal lyrics and confirm line-based LRC display.
2. Import `karaoke-zh.lrc` as enhanced lyrics and confirm word-by-word highlighting.
3. Import `karaoke-ja.lrc` to check Japanese enhanced lyrics.
4. Import `karaoke-en-long.lrc`, then narrow the floating window and increase text size to check wrapping.
5. Import `karaoke-invalid-plain-selected-as-karaoke.lrc` as enhanced lyrics and confirm the format error path.
6. Import `karaoke-with-translation-style.lrc` to observe enhanced lyrics with translation-like lines.

## Minimal enhanced example

```lrc
[00:01.00]<00:01.00>Hello <00:01.30>AirLyrics
```
