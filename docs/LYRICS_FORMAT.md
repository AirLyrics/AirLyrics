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

## Complete Support Reference

The examples above are the recommended forms. This section lists the formats and variants accepted by local import. In-app help continues to show only the main examples.

### Files and Import Options

| File contents | Plain lyrics option | Word-by-word lyrics option |
| --- | --- | --- |
| Plain LRC, including translations | Supported | Not sufficient on its own; plain lines at matching timestamps can translate word-timed lines |
| Word-timed LRC with line and word tags | Word tags are not interpreted; use the word-by-word option | Supported |
| Line-timed TTML | Supported | Not supported |
| Word-timed TTML | Supported, flattened to lines | Supported |
| Mixed line- and word-timed TTML | Supported | Not supported; every visible lyric line needs word timing |
| AirLyrics word-timing JSON | Not supported | Compatible; see the structure below |

Prefer `.lrc` and `.ttml` extensions. When the filename and type are inconclusive, the app tries to detect the contents. It does not convert unrelated formats automatically.

### LRC Timestamps and Variants

The same time forms work inside word tags `<…>`:

| Timestamp example | Meaning |
| --- | --- |
| `[00:12]` | 12 seconds, no fraction |
| `[00:12.3]`, `[00:12:3]` | 12.3 seconds, one fractional digit |
| `[00:12.34]`, `[00:12:34]` | 12.34 seconds, two fractional digits |
| `[00:12.345]`, `[00:12:345]` | 12.345 seconds, three fractional digits |
| `[1:02.34]` | Minutes may also use one digit |

Minutes use one or two digits. Seconds require two digits and must be below 60; fractions allow up to three digits. The third LRC field is a fraction of a second: `[00:00:58]` means **0.58 seconds**, not 58 seconds. Hour-based timestamps are not supported.

Plain LRC also supports these forms:

| Form | Example and handling |
| --- | --- |
| Multiple timestamps | `[00:12.34][00:15.60]Repeated lyric`: shown at both times |
| Compact lines | `[00:12.34]First line[00:15.60]Second line`: split at the timestamps |
| Translation on separate lines | At the same timestamp, the first line is the original and later distinct text becomes translation |
| Inline translation | `[00:12.34]Original / Translation`: ASCII `/` requires whitespace on both sides |
| Full-width separator | `[00:12.34]Original／Translation`: spaces around `／` are optional |
| Multiple translations | `[00:12.34]Original / Translation one / Translation two` |
| Translation only | `[00:12.34]/ Translation`: whitespace is required after `/` |
| Empty lyric line | `[00:12.34]`: preserved as an empty timed line in plain lyrics |
| Metadata | Separate tags such as `[ar:Artist]`, `[ti:Title]`, `[al:Album]` and `[by:Creator]` are retained |

`[offset:…]` is retained as metadata, but does not shift the timeline; use the in-app lyrics offset. Credit lines such as `Lyrics: A / B` are not split into translations. Nonempty lyric text needs a timestamp. A file containing only metadata or empty lines cannot be imported.

Additional word-timed LRC rules:

- Each original line needs a line tag `[…]` and at least one word tag `<…>` followed by text. Segments starting before the line timestamp are ignored.
- Put each original line on its own row; do not duplicate a word-timed passage with multiple line tags. Put translations on separate plain LRC rows with the same line timestamp.
- Keep spaces between words in the text, for example `<00:12.34>I <00:12.60>love <00:12.95>you`.
- An empty trailing tag does not set the end time. Use TTML `end` or `dur` when explicit word end times are needed.

### TTML Timing and Structure

| Time form | Examples |
| --- | --- |
| Hours, minutes, seconds | `01:02:03`, `01:02:03.004` |
| Minutes, seconds | `02:03`, `02:03.45` |
| Seconds only | `95`, `95.5` |
| Seconds with `s` | `12s`, `12.34s`, `12.3456s` |

The first three forms allow up to three fractional digits. Values ending in `s` may have more digits and are rounded to milliseconds. Clock minutes and seconds must be below 60. Units `ms`, `m`, `h`, frames (`f`), ticks (`t`) and frame-based clocks are not supported.

- Use a `<tt>` root with one `<body>`. Every lyric `<p>` needs `begin` and either `end` or `dur`. When both are present, `end` takes precedence. If `<body dur="…">` is set, lines must end within that duration.
- Timed word `<span>` elements also need `begin` and `end` or `dur`, within their parent line's range. Nonempty segments must end after they start.
- Times are measured from the start of the song; parent `begin` values are not added. Timing on a `<div>` alone is not inherited by its `<p>` elements.
- Namespace prefixes are supported. `itunes:timing="Line"` ignores word timing; `Word` or an omitted declaration allows timed `<span>` elements.
- `<br/>` becomes a space. Formatting elements retain their text, not their styling.

### TTML Translation Forms

Place **inline translations** inside the lyric line using `ttm:role="x-translation"`:

```xml
<tt xmlns="http://www.w3.org/ns/ttml"
    xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
  <body>
    <p begin="12s" end="15s">Hello<span ttm:role="x-translation">你好</span></p>
  </body>
</tt>
```

**Apple Music head translations** link `text for` to the lyric line's `itunes:key`:

```xml
<tt xmlns="http://www.w3.org/ns/ttml"
    xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions">
  <head>
    <metadata>
      <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
        <translations>
          <translation type="subtitle" xml:lang="zh-Hans">
            <text for="L1">你好</text>
          </translation>
        </translations>
      </iTunesMetadata>
    </metadata>
  </head>
  <body>
    <p begin="12s" end="15s" itunes:key="L1">Hello</p>
  </body>
</tt>
```

Inline and head translations can be combined, with duplicate text removed. Background vocals (`x-bg`), romanization (`x-roman`) and ruby readings are ignored, leaving the main lyrics; separate vocal tracks are not provided. DTDs, external entities, nested `<p>` elements and nested timed `<span>` elements are not supported.

### AirLyrics Word-Timing JSON (Compatibility Format)

Import this through **Word-by-word lyrics** only. It is not a general lyrics JSON format; LRC and TTML remain recommended.

```json
[
  {
    "startMs": 12000,
    "endMs": 15000,
    "text": "Hello world",
    "tokens": [
      {"text": "Hello ", "startMs": 12000, "endMs": 13000},
      {"text": "world", "startMs": 13000, "endMs": 15000}
    ]
  }
]
```

Times are in milliseconds. Each line needs nonempty `text`, valid start/end times and at least one valid `tokens` segment. The object form `{"metadata": ["[ar:Artist]"], "lines": [...]}` is also accepted: `lines` holds the array above and `metadata` is optional. A line may include a `background` boolean. JSON from other tools must follow this structure to be imported.

### Formats Requiring Conversion

Untimed text, SRT, ASS, QRC, KRC, YRC and other encrypted or compressed lyrics files must be converted to a format listed above. Renaming the extension does not convert the contents. TTML support is limited to this reference, not the entire TTML standard.
