# Lyrics Format

[English](LYRICS_FORMAT.md) · [简体中文](LYRICS_FORMAT.zh-CN.md)

AirLyrics supports local imports of `.lrc` and `.ttml` files.

## Plain LRC

Use one timestamp per line:

```lrc
[00:12.34]This is a lyric line
[00:15.60]This is the next lyric line
```

The timestamp format is `[mm:ss.xx]`, where `mm`, `ss`, and `xx` represent minutes, seconds, and
centiseconds.

## Translated Lyrics

Original and translated lyrics can share one line, separated by ` / `:

```lrc
[00:12.34]大好きだって 大切だって / I love you, I love you
```

They can also be written on separate lines with the same timestamp:

```lrc
[00:12.34]大好きだって 大切だって
[00:12.34]I love you, I love you
```

During import, the first line is treated as the original lyric and later unique lines as
translations. AirLyrics converts them to the single-line form automatically.

## Word-by-word LRC

Word-by-word lyrics are available only through local import. Each original lyric line must include
both a line timestamp and word timestamps:

```lrc
[00:12.34]<00:12.34>I <00:12.60>love <00:12.95>you
```

A translation uses the same line timestamp as the original lyric and does not need word timestamps:

```lrc
[00:12.34]<00:12.34>大<00:12.60>好き<00:12.95>だって
[00:12.34]I love you, I love you
```

Plain and word-by-word lyrics for the same song cannot be stored as independently managed versions.
Remove existing lyrics before importing the other type. After importing word-by-word lyrics,
AirLyrics automatically generates and synchronizes a plain LRC; edit or remove the word-by-word
lyrics instead of the generated file.

## TTML

AirLyrics supports a subset of TTML, including some timing and translation fields commonly used by
Apple Music and AMLL.

Line-timed TTML puts timing on each `<p>`:

```xml
<tt xmlns="http://www.w3.org/ns/ttml">
  <body>
    <div>
      <p begin="00:12.340" end="00:15.600">This is a lyric line</p>
      <p begin="00:15.600" dur="2.400s">This is the next line</p>
    </div>
  </body>
</tt>
```

Word-timed TTML also puts timing on lyric `<span>` elements:

```xml
<tt xmlns="http://www.w3.org/ns/ttml">
  <body>
    <div>
      <p begin="00:12.340" end="00:15.600"><span begin="00:12.340" end="00:12.600">I </span><span begin="00:12.600" end="00:13.100">love </span><span begin="00:13.100" end="00:15.600">you</span></p>
    </div>
  </body>
</tt>
```

TTML timing accepts clock times such as `00:12.340` and `00:00:12.340`, or offset times such as
`12.34s`. Use `end` for the end time or `dur` for the duration.

Supported translations include inline elements with the `x-translation` role and Apple Music head
translations linked to lyric lines by `itunes:key`.

The import option controls the result:

- The **Plain lyrics** option accepts line- or word-timed TTML and flattens word timing to line
  timing.
- The **Word-by-word lyrics** option requires timed `<span>` elements on every valid lyric line.
  Line-timed or mixed-timing TTML cannot be imported as word-by-word lyrics.

After import, TTML is converted to editable lyrics without modifying the source file. Styling and
complex multi-voice presentation data are not preserved.

## Compatible LRC Variants

These common variants are also supported:

```lrc
[00:12.34]Lyric
[00:12:34]Lyric
[01:02.345]Lyric
[00:12.34][00:15.60]Repeated lyric
```

AirLyrics also attempts to parse compact formats exported by some tools:

```lrc
[00:00:58]Line A[00:01:20]Line B[00:02:18]Line C
```

This format is supported for compatibility only and should not be edited manually.
