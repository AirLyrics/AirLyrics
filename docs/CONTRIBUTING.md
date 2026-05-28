# Contributing to Air Lyrics

Thanks for helping Air Lyrics grow. This project is small enough to move quickly, but the modules are split so each contribution has a clear home.

## Recommended environment

- Android Studio or IntelliJ IDEA
- JDK compatible with the Android Gradle Plugin used by this project
- Android SDK installed locally
- Rust toolchain only when rebuilding `lyrics-core`

For normal Android UI/service work, use:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

This uses the existing native library under `app/src/main/jniLibs/` and skips rebuilding Rust.

## Files that should not be committed

Do not commit local build outputs or machine-specific files:

```text
.gradle/
.kotlin/
build/
app/build/
lyrics-core/target/
local.properties
```

If `git status` shows `lyrics-core/target/...`, restore or ignore those changes unless the task explicitly requires native build artifacts.

## Finding the right place to change code

Use this map before editing:

```text
Theme colors / dark mode       ui/theme/ and settings/store/ThemeSettingsStore.kt
Floating window appearance     ui/pages/settings/FloatingSettingsPage.kt and settings/store/FloatingLyricsStyleStore.kt
Floating window behavior       floating/FloatingWindowController.kt
Foreground service commands    floating/FloatingLyricsService.kt
Lyrics source selection        ui/pages/settings/LyricsSettingsPage.kt and settings/store/LyricsSettingsStore.kt
Lyrics lookup logic            lyrics/LyricsRepository.kt
New lyric provider             lyrics/<YourProvider>.kt
Media player detection         media/MediaNotificationListener.kt
Reusable UI component          ui/components/
Settings sub-page              ui/pages/settings/
Broadcast/service constants    common/BroadcastActions.kt
```

## Adding a new setting

1. Add the value to an existing model in `settings/model/`, or create a new model if it is a new settings group.
2. Add read/write behavior in the matching `*Store.kt` file.
3. Update the relevant UI page under `ui/pages/settings/`.
4. Apply the setting in the runtime module that uses it, such as `FloatingWindowController` or `LyricsRepository`.
5. Build and test.

Avoid direct `SharedPreferences` access in UI pages or services unless you are creating a settings store.

## Adding a new lyric source

1. Create a provider in `lyrics/`, for example `LrcLibLyricsProvider.kt`.
2. Implement `LyricsProvider`.
3. Register the provider in `LyricsRepository`.
4. Add a user-facing option in `LyricsSettingsStore` and `LyricsSettingsPage`.
5. Test with common cases: exact match, missing artist, no lyrics, bad network response, and local-only mode.

Provider code should not update UI directly. Return `LyricsProviderResult` and let the repository/service handle the rest.

## Changing floating-window behavior

- Dragging, style application, lock state, click-through, and WindowManager params belong in `FloatingWindowController`.
- Parsed LRC lines and current playback position belong in `FloatingLyricsRenderer`.
- Service actions and media updates belong in `FloatingLyricsService`.

Try to keep `FloatingLyricsService` as a coordinator instead of adding more view logic there.

## Commit style

Use small commits with clear messages:

```text
refactor settings pages
add lrc provider skeleton
fix floating window state sync
update theme palette
```

Before committing:

```bash
git status
git diff --cached --stat
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

## Pull request checklist

- The project builds.
- No build outputs are committed.
- New settings go through `settings/store` stores.
- New lyric sources implement `LyricsProvider`.
- UI changes are placed in page/component/theme files, not directly in unrelated modules.
- Behavior changes are described clearly in the PR.
