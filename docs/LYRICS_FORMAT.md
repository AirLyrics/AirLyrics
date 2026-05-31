# Lyrics Format

[English](LYRICS_FORMAT.md) · [简体中文](LYRICS_FORMAT.zh-CN.md)

AirLyrics supports local `.lrc` file import. Use local import when online lyrics are missing, inaccurate or out of sync.

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

A common local format is to put original and translated lines alternately under the same timestamp:

```lrc
[00:12.34]大好きだって 大切だって
[00:12.34]I love you, I love you
```

The actual display depends on the lyrics content mode:

- Original + translation
- Original only
- Translation only

## Enhanced / word-by-word LRC

Word-by-word lyrics are mainly supported through local import. They contain a line timestamp and inline word timestamps.

Preferred format:

```lrc
[00:12.34]<00:12.34>I <00:12.60>love <00:12.95>you
```

Format:

```text
[line start]<word start>word<word start>word
```

The line timestamp decides when the line appears. Inline timestamps decide word highlight timing.

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
