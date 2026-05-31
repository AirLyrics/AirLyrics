# Lyrics Format

[English](LYRICS_FORMAT.md) · [简体中文](LYRICS_FORMAT.zh-CN.md)

AirLyrics supports local `.lrc` import. Local import is the recommended path when online lyrics are missing, inaccurate or not synchronized with your track.

## Normal LRC

Normal LRC displays one timed line at a time.

Recommended format:

```lrc
[00:12.34]This is a lyric line
[00:15.60]This is the next line
```

Format:

```text
[mm:ss.xx]lyric text
```

Notes:

- `mm` is minutes.
- `ss` is seconds.
- `xx` is centiseconds.
- Keep one main timestamp and one lyric sentence per line when possible.

## Translation lines

AirLyrics can display original lyrics with translated lyrics when translation lines are available.

A common local format is alternating original and translated lines with the same timestamp:

```lrc
[00:12.34]大好きだって 大切だって
[00:12.34]I love you, I love you
```

Display behavior depends on the selected lyrics content mode:

- Original + translation
- Original only
- Translation only

## Enhanced / word-by-word LRC

Enhanced lyrics are supported mainly through local import. They use line timestamps plus inline word timestamps.

Recommended format:

```lrc
[00:12.34]<00:12.34>I <00:12.60>love <00:12.95>you
```

Format:

```text
[line start]<word start>word<word start>word
```

The line timestamp decides when the line appears. Inline timestamps decide word-by-word highlighting.

## Supported variants

The parser tries to tolerate common variants:

```lrc
[00:12.34]Lyric
[00:12:34]Lyric
[01:02.345]Lyric
[00:12.34][00:15.60]Repeated lyric
```

It also attempts to recover compact exports like:

```lrc
[00:00:58]Line A[00:01:20]Line B[00:02:18]Line C
```

This compact style is not recommended for manual editing.

## Not recommended

Avoid these patterns when creating lyrics manually:

- Untimed plain text only.
- Very long lines without natural spaces.
- Mixed unrelated songs in one file.
- Wrong duration or wrong song version.
- Enhanced lyrics selected for a plain LRC file.

## Import behavior

When importing, AirLyrics asks whether the file should be treated as normal lyrics or enhanced lyrics.

- Normal lyrics are saved as line-based LRC.
- Enhanced lyrics are saved separately so word timing can be reused.
- Local lyrics have priority over online lookup.
- Lyrics offset can be adjusted later from the app.

## Test samples

Manual test files are available in:

```text
docs/test-lyrics-samples/
```
