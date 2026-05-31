# Contributing

[English](CONTRIBUTING.md) · [简体中文](CONTRIBUTING.zh-CN.md)

Thanks for helping AirLyrics. Keep changes narrow and easy to review.

## Environment

- Android Studio or IntelliJ IDEA
- JDK 17
- Android SDK
- Android NDK if rebuilding the native lyrics core
- Rust toolchain and `cargo-ndk` if rebuilding `lyrics-core`

For Kotlin-only checks, use the skip flag when native libraries are already present:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

Release builds include native libraries.

## Before opening a PR

```bash
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
./scripts/check_localization.sh
```

If your change touches local lyrics storage, import behavior, parser logic or Android storage permissions, also run instrumentation tests on a device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

## Where to change code

| Task | Main location |
| --- | --- |
| Media detection | `media/` and `app/controller/AppMediaController.kt` |
| Lyrics lookup | `lyrics/LyricsRepository.kt` and `lyrics/providers/` |
| Local lyrics storage | `lyrics/storage/` |
| LRC parsing | `lyrics/parser/LrcParser.kt` |
| Floating window runtime | `floating/` |
| Floating page UI | `ui/pages/FloatingPage.kt` and related floating page files |
| Lyrics settings UI | `ui/pages/settings/LyricsSettingsPage.kt` |
| System permissions UI | `ui/pages/settings/SystemSettingsPage.kt` |
| Persistent settings | `settings/model/` and `settings/store/` |
| UI theme / tokens | `ui/theme/` and `ui/tokens/` |
| Localization | Android string resources and `i18n/` helpers |

## Adding a setting

1. Add or extend a data model under `settings/model/`.
2. Add read/write behavior in the matching `settings/store/*Store.kt`.
3. Update the relevant UI page.
4. Apply the setting in the runtime module that uses it.
5. Update docs and tests when the behavior is user-visible.

Use settings stores instead of direct `SharedPreferences` access from UI pages or services.

## Adding a lyrics provider

1. Implement `LyricsProvider` under `lyrics/providers/`.
2. Register it in `LyricsRepository`.
3. Add a user-facing source option in settings.
4. Handle network failures, no-result cases and ambiguous matches safely.
5. Keep enhanced / word-by-word lyrics import local-first unless the app design changes.

Providers return data and do not update UI directly.

## Localization rules

- Do not change existing string keys casually.
- Keep placeholders such as `%1$s` unchanged.
- Keep UI text short.
- Do not translate song titles, artist names, file names, package names or paths.
- Run `./scripts/check_localization.sh` before submitting.

## Files not to commit

```text
.gradle/
.kotlin/
build/
app/build/
lyrics-core/target/
local.properties
```

Generated APKs and local machine configuration stay out of the repository.
