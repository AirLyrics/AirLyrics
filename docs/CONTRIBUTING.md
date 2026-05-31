# Contributing

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)

Thank you for contributing to AirLyrics.

To make review easier, please keep each PR focused on one purpose. Avoid mixing unrelated formatting, refactoring and feature changes in the same PR.

If available, images or videos can make the explanation easier to understand.

## Before opening a PR

Most changes only need the basic checks:

```bash
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
./scripts/check_localization.sh
```

If your change touches lyrics parsing, local lyrics storage, lyrics import, Android storage permissions or SAF folder behavior, please also run this on a real device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

If you only changed the README, documentation, comments or a small amount of text that does not affect runtime behavior, Android instrumentation tests are usually not required.

## Related guidelines

### Adding a setting

When adding a setting, please connect it through the existing structure instead of storing temporary state only in a UI page.

1. Add or extend a settings data model under `settings/model/`.
2. Add read and save logic in the matching `settings/store/*Store.kt`.
3. Update the relevant UI page so users can view or change the setting.
4. Read and apply the setting in the module that actually uses it, such as floating window rendering, lyrics lookup or lyrics storage.
5. If the setting changes user-visible behavior, please update the related documentation or tests.

Do not read or write raw `SharedPreferences` directly from UI pages or Services unless you are creating a new Store.

### Adding a lyrics provider

When adding a lyrics source, keep the Provider responsibility narrow.

1. Implement `LyricsProvider` under `lyrics/providers/`.
2. Register it in `LyricsRepository`.
3. If users need to choose the source manually, expose this Provider in settings.
4. Handle network failures, no-result cases and ambiguous matches safely.
5. Unless the app design changes, enhanced / word-by-word lyrics should remain local-import-first.

Providers only fetch and return lyrics data. They should not update UI directly.

### Localization rules

When changing UI text, please note:

- Do not casually change existing string keys.
- Keep placeholders such as `%1$s` and `%2$d` unchanged.
- Keep UI text short.
- Do not translate song titles, artist names, file names, package names or paths.
- Run `./scripts/check_localization.sh` before submitting.

If you add new string resources, please also provide text for the corresponding languages to avoid missing translations in the UI.

## Files not to commit

Please do not commit local build outputs or local machine configuration, for example:

```text
.gradle/
.kotlin/
build/
app/build/
lyrics-core/target/
local.properties
```

Generated APKs, signing files, local SDK paths and IDE caches should stay out of the repository.
