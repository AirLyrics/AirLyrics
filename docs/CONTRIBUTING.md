# Contributing

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)

Thank you for contributing to AirLyrics.

AirLyrics is stable and actively maintained, with maintenance-focused updates.

Bug fixes, compatibility fixes, translation updates, documentation improvements, and small UI text
polishing are welcome.

For larger features or behavior changes, please open an issue first so the direction can be
discussed before implementation.

To make review easier, please keep each PR focused on one purpose. Avoid mixing unrelated
formatting, refactoring and feature changes in the same PR.

If available, images or videos can make the explanation easier to understand.

## Before submitting a PR

Please run the basic checks before submitting a PR:

```bash
./gradlew :app:lintDebug -Pairlyrics.skipRustBuild=true
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
./scripts/check_localization.sh
./scripts/check_architecture_boundaries.sh
```

If you changed Android UI or app integration code, you can also build a debug APK without rebuilding
Rust:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

If you changed the Rust lyric core, also run:

```bash
cd lyrics-core
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
```

If your changes affect lyrics parsing, local lyrics storage, lyrics import, Android storage
permissions, or SAF folder behavior, also run this check on a real device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

Note: `connectedDebugAndroidTest` may uninstall or overwrite the AirLyrics app already installed on
the device. Please make sure the test device can safely lose its existing app data. A test device or
emulator is recommended.

## Related guidelines

### Adding a setting

When adding a setting, please connect it through the existing structure instead of storing temporary
state only in a UI page.

1. Add or extend a settings data model under `settings/model/`.
2. Add read and save logic in the matching `settings/store/*Store.kt`.
3. Update the relevant UI page so users can view or change the setting.
4. Read and apply the setting in the module that actually uses it, such as floating window rendering, lyrics lookup or lyrics storage.
5. If the setting changes user-visible behavior, please update the related documentation or tests.

Do not read or write raw `SharedPreferences` directly from UI pages or Services unless you are
creating a new Store.

### Adding a lyrics provider

AirLyrics is a floating lyrics app, not a lyrics search project. Unless the current lyrics sources
can no longer satisfy normal use, please prefer manual local lyrics import instead of adding more
online providers.

When a new provider is truly needed, keep the Provider responsibility narrow.

1. Implement `PlainLyricsProvider` under `lyrics/providers/`.
2. Register it in `LyricsRepository`.
3. If users need to choose the source manually, expose this Provider in settings.
4. Handle network failures, no-result cases and ambiguous matches safely.
5. Unless the app design changes, word-by-word lyrics should remain local-import-first.

Providers only fetch and return lyrics data. They should not update UI directly.

### Localization rules

When changing UI text, please note:

- Do not casually change existing string keys.
- Keep placeholders such as `%1$s` and `%2$d` unchanged.
- Keep UI text short.
- Do not translate song titles, artist names, file names, package names or paths.
- Run `./scripts/check_localization.sh` before submitting.

### Architecture boundaries

The project currently uses one Gradle `:app` module, so package boundaries are enforced by checks.
Run `./scripts/check_architecture_boundaries.sh` before submitting refactors. In particular, UI
code must not import `settings`, `lyrics`, `media`, `floating`, or `app` packages directly.

If you add new string resources, please also provide text for the corresponding languages to avoid
missing translations in the UI.

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
