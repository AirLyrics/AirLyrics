# Lyrics module

This module owns lyrics lookup, parsing, storage, and provider orchestration.

## Main entry points

- `LyricsRepository` is the only lookup entry point new code should call.
- `LyricsProvider` is the interface for adding a new lyrics source.
- `LocalLyricsProvider` checks imported/saved `.lrc` files.
- `NeteaseLyricsProvider` wraps the Rust NetEase lookup bridge.
- `LyricsFetcher` remains as a compatibility wrapper for older callback-style code.

## Lookup order

1. Local lyrics always win.
2. If the user selected local-only mode, lookup stops there.
3. Otherwise, `LyricsRepository` uses the selected online provider.
4. Successful online lyrics can be saved locally when auto-save is enabled.

## Adding a provider

1. Create a new object or class implementing `LyricsProvider`.
2. Return a normalized `LyricsProviderResult`.
3. Register the provider inside `LyricsRepository.onlineProviders`.
4. Add a user-facing option in `LyricsSettingsStore.sourceOptions`.

Keep provider-specific network parsing inside the provider. UI and services should not know how each source works.
