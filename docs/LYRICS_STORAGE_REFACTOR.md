# LyricsStorage refactor notes

`LyricsStorage` is now a public facade instead of a 1000-line storage blob. Public callers keep using the same API, while implementation details live in small focused files.

## Files

- `LyricsStorage.kt`: public API, small orchestration, public nested models kept for compatibility.
- `LyricsStoragePaths.kt`: selected SAF directory, fallback app directory, managed directory, index file location.
- `LyricsFileStore.kt`: raw file reads/writes/deletes, URI import text decoding, legacy file access.
- `LyricsIndexStore.kt`: `lyrics_index.json` parse/write/find logic.
- `LyricsIndexEntry.kt`: internal index row model.
- `SongIdentity.kt`: song matching, normalized key generation, legacy filename generation.
- `KaraokeLyricsCodec.kt`: enhanced LRC parsing, karaoke JSON codec, plain LRC shadow generation.
- `LocalLyricsLister.kt`: recent local lyrics listing and metadata merge.
- `StorageConstants.kt`: internal storage constants shared by the helpers.

## Rules

- Keep user-facing API in `LyricsStorage` unless there is a clear reason to expose a new class.
- Keep disk details out of UI code.
- Keep pure codecs in `KaraokeLyricsCodec` so they stay easy to unit test.
- Keep song matching in `SongIdentity`; do not duplicate title/artist matching logic.
