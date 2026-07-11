# Lyrics Format

[English](LYRICS_FORMAT.md) · [简体中文](LYRICS_FORMAT.zh-CN.md)

AirLyrics supports local `.lrc` file import. Use local import when online lyrics are missing,
inaccurate or out of sync.

## Normal LRC

Normal LRC is used to display lyrics line by line.

Preferred format:

```lrc
[00:12.34]This is a lyric line
[00:15.60]This is the next lyric line
```

Format:

```text
[mm:ss.xx]lyric text
```

Notes:

- `mm` means minutes.
- `ss` means seconds.
- `xx` means centiseconds.
- When editing lyrics manually, put one main timestamp and one lyric sentence on each line.

## Translated lyrics

When translation lines are available, AirLyrics can display original lyrics and translated lyrics.

Preferred local format:

```lrc
[00:12.34]大好きだって 大切だって / I love you, I love you
```

AirLyrics also supports original and translated lines written under the same timestamp when
importing or saving normal LRC:

```lrc
[00:12.34]大好きだって 大切だって
[00:12.34]I love you, I love you
```

During import, this will be normalized to:

```lrc
[00:12.34]大好きだって 大切だって / I love you, I love you
```

If several translation lines share the same timestamp, the first line is treated as the original
lyric. Later different lines are kept as translations.

The actual display depends on the lyrics content mode:

- Original + translation
- Original only
- Translation only

## Word-by-word LRC

Word-by-word lyrics are mainly supported through local import. They contain a line timestamp and
inline word timestamps.

Preferred format:

```lrc
[00:12.34]<00:12.34>I <00:12.60>love <00:12.95>you
```

Format:

```text
[line start]<word start>word<word start>word
```

The line timestamp decides when the line appears. Inline timestamps decide word highlight timing.

Word-by-word imports may also include translation lines under the same line timestamp:

```lrc
[00:12.34]<00:12.34>大<00:12.60>好き<00:12.95>だって
[00:12.34]I love you, I love you
```

When the song does not already have normal lyrics, AirLyrics automatically generates normal LRC from
the word-by-word lyrics. If same-timestamp translation lines are present, the generated normal LRC
includes them:

```lrc
[00:12.34]大好きだって / I love you, I love you
```

When the song already has normal lyrics, AirLyrics does not allow importing word-by-word lyrics.
Remove the existing normal lyrics first, then import the word-by-word lyrics. This avoids keeping two
independently editable lyric versions for the same song.

After word-by-word lyrics are imported, the normal LRC is stored as an automatically generated
fallback. Edit the word-by-word lyrics only; saving them regenerates the normal LRC. If you remove
word-by-word lyrics, the generated normal LRC is removed with them.

## Supported variants

The parser supports these common variants:

```lrc
[00:12.34]Lyric
[00:12:34]Lyric
[01:02.345]Lyric
[00:12.34][00:15.60]Repeated lyric
```

It also tries to recover compact exports from some tools:

```lrc
[00:00:58]Line A[00:01:20]Line B[00:02:18]Line C
```

However, this compact format is not recommended for manual maintenance.

## Not recommended

When creating lyrics manually, watch out for:

- Plain text without timestamps.
- Very long lines without natural spaces.
- Multiple unrelated songs mixed in one file.
- A song version or duration that does not match.
- Importing a normal LRC file as word-by-word lyrics.
- Importing word-by-word lyrics while existing normal lyrics are still bound to the song. Remove the
  normal lyrics first.
- Editing the generated normal LRC separately after importing word-by-word lyrics. Edit the
  word-by-word lyrics instead.
