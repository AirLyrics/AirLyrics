# Lyrics Storage Refactor

[English](LYRICS_STORAGE_REFACTOR.md) · [简体中文](LYRICS_STORAGE_REFACTOR.zh-CN.md)

The local lyrics storage code is split into smaller helpers. `LyricsStorage` remains the public facade for callers, while file IO, index handling and path resolution live in dedicated files.

## Current storage files

```text
lyrics/storage/LyricsStorage.kt        Public facade used by app code
lyrics/storage/LyricsStoragePaths.kt   SAF tree URI, managed lyrics dir and index file resolution
lyrics/storage/LyricsFileStore.kt      File read/write/delete helpers
lyrics/storage/LyricsIndexStore.kt     JSON index persistence
lyrics/storage/LocalLyricsLister.kt    Recent/local lyrics listing
lyrics/storage/KaraokeLyricsCodec.kt   Enhanced lyrics serialization
lyrics/storage/SongIdentity.kt         Stable song identity normalization
lyrics/storage/StorageConstants.kt     Storage keys and file names
```

## Storage model

AirLyrics uses a managed lyrics folder selected by the user when available. Imported or auto-saved lyrics are stored there together with an index file.

```text
lyrics/
  lyrics_index.json
  *.lrc
  enhanced / karaoke data handled by storage codec
```

The exact file names are normalized through song identity helpers to avoid unsafe path characters.

## Lookup priority

Local storage is not just a cache. It is the user's correction layer and therefore has priority over online lookup.

Normal priority:

1. Manual import.
2. Local saved lyrics.
3. Online lookup when enabled.
4. Auto-save online result when enabled.

## Refactor rules

- Keep `LyricsStorage` as a stable facade.
- Put path and SAF details in `LyricsStoragePaths`.
- Put raw file operations in `LyricsFileStore`.
- Put index JSON logic in `LyricsIndexStore`.
- Put enhanced lyrics encoding in `KaraokeLyricsCodec`.
- Do not add UI text or dialogs to storage classes.
