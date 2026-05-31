# Lyrics Module

[English](LYRICS_MODULE.md) · [简体中文](LYRICS_MODULE.zh-CN.md)

The lyrics module owns lookup, provider routing, parsing, local storage and display formatting.

## Main flow

```text
LyricsRepository.findLyrics(...)
  -> LocalLyricsProvider.fetch(...)
  -> selected online provider when allowed
  -> optional LyricsStorage.saveLyrics(...)
  -> optional local karaoke attachment
```

Local lyrics are checked first so user-imported files and saved corrections win over online results.

## Important files

```text
lyrics/LyricsRepository.kt                 Central lookup entry point
lyrics/LyricsFetcher.kt                    Compatibility wrapper for older call sites
lyrics/LyricsProvider.kt                   Provider interface and result model
lyrics/LyricsLookupCancellation.kt         Single-worker latest-request-wins runner
lyrics/parser/LrcParser.kt                 LRC parsing and line lookup
lyrics/display/LyricsDisplayFormatter.kt   Original / translation display formatting
lyrics/providers/LocalLyricsProvider.kt    Local imported/saved source
lyrics/providers/NeteaseLyricsProvider.kt  NetEase provider bridge
lyrics/providers/MusixmatchLyricsProvider.kt Musixmatch provider bridge
lyrics/storage/                            Local file, index and karaoke storage
```

## Lookup settings

`LyricsSettings` controls:

- Search source: local only, NetEase Cloud Music, Musixmatch.
- Whether online fallback is enabled.
- Whether successful online lyrics are auto-saved locally.
- Original / translation display mode.
- Current / neighboring line display range.
- Line switch animation.
- Whether enhanced / word-by-word lyrics are used.

## Provider contract

Providers return `LyricsProviderResult`. They should handle no-result cases without touching UI directly. UI and service layers decide how to display errors or empty states.

## Parser responsibilities

`LrcParser` handles:

- Normal timestamped LRC.
- Multiple timestamps on one line.
- Compact exported LRC.
- Original and translation merge for storage.
- Current-line lookup.
- Enhanced / word-by-word line parsing.

## Storage responsibilities

`lyrics/storage/` handles:

- Managed lyrics folder selection.
- Local lyrics file save/read/delete.
- Lyrics index metadata.
- Karaoke / enhanced lyrics codec.
- Song identity normalization.

Do not add UI behavior to storage classes.
