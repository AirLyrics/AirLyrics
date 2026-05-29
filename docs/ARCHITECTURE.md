# Air Lyrics Architecture

Air Lyrics is an Android floating-lyrics app with a Kotlin Android shell and a Rust lyrics core. The Android side is intentionally split by responsibility so contributors can work on one feature area without reading the whole project.

## Top-level layout

```text
app/                 Android app module
lyrics-core/          Rust lyrics/native module
gradle/               Gradle Wrapper files
PROJECT_STRUCTURE.md  Quick source-tree map
ARCHITECTURE.md       This document
CONTRIBUTING.md       Contributor workflow
```

## Android source layout

```text
app/src/main/java/com/andsi/airlyrics/
  app/
    MainActivity.kt
    controller/
  lyrics/
  media/
  settings/
    model/
    store/
  floating/
  ui/
    components/
    navigation/
    pages/
    pages/settings/
    theme/
    widgets/
  common/
```

## Main responsibilities

### MainActivity

`MainActivity.kt` is the app shell. It owns lifecycle wiring, Activity Result launchers, broadcast receivers, and thin delegation methods. New feature logic should not be added directly to it.

Nearby `app/` helpers split the old Activity responsibilities:

```text
MainActivityRenderer.kt      Root view creation and page rendering.
MainUiActionsFactory.kt      Central UI action callbacks.
MainActivitySettingsUi.kt    Settings-page shared UI helpers.
MainActivityFloatingUi.kt    Floating-style controls and media-source cards.
MainActivityMediaUi.kt       Media refresh button and visual refresh helpers.
controller/                  Coordinators for media, lyrics, and floating actions.
```

When adding a feature, prefer a page/component/controller/store file first. Only touch `MainActivity.kt` when the feature needs lifecycle, permission, launcher, or broadcast wiring.

### media

This module owns Android media-session observation and selected-player state.

Important files:

```text
MediaNotificationListener.kt  Reads active media notifications and broadcasts updates.
MediaSourceStore.kt           Persists the selected media package.
```

Use this module when changing player detection, playback state sync, or media-source selection.

### lyrics

This module owns lyric lookup, provider routing, local lyric import/cache, and LRC parsing.

Important files:

```text
LyricsRepository.kt       Single lookup entry point used by the service.
LyricsProvider.kt         Interface for pluggable lyric sources.
LocalLyricsProvider.kt    Local imported/saved lyric source.
NeteaseLyricsProvider.kt  NetEase online lyric source.
MusixmatchLyricsProvider.kt  Musixmatch online lyric source.
LyricsStorage.kt          Local .lrc storage.
LrcParser.kt              Timestamped lyric parsing and current-line lookup.
```

To add a lyric source, create a new provider implementing `LyricsProvider`, then register it in `LyricsRepository` and expose the option in `LyricsSettingsStore`.

### settings

This module is the single settings center. UI and services should read/write settings through stores instead of directly touching `SharedPreferences`.

Important files:

```text
model/ThemeSettings.kt
model/FloatingLyricsStyle.kt
model/LyricsSettings.kt
store/ThemeSettingsStore.kt
store/FloatingLyricsStyleStore.kt
store/LyricsSettingsStore.kt
store/QuickFloatingStore.kt
```

When adding a new setting, add it to a model first, then add store read/write methods, then wire UI and runtime behavior to that store.

### floating

This module owns the Android foreground service and the floating lyrics window.

Important files:

```text
FloatingLyricsService.kt        Service command router, media receiver, lyric loading coordinator.
FloatingWindowController.kt     WindowManager view creation, show/hide, drag, lock, click-through, style application.
FloatingLyricsRenderer.kt       Parsed lyric state and current-line rendering.
CurrentMediaInfo.kt             Current media snapshot used by service logic.
FloatingServiceNotification.kt  Foreground-service notification setup.
```

`FloatingLyricsService` should stay as an orchestration layer. Window details belong in `FloatingWindowController`; rendering details belong in `FloatingLyricsRenderer`.

### ui/components

Reusable view helpers and small UI building blocks. Put repeated rows, cards, text helpers, buttons, and animations here.

### ui/pages

Top-level app pages:

```text
MediaPage.kt
FloatingPage.kt
SettingsPages.kt
```

### ui/pages/settings

Settings sub-pages are split by user-facing feature area:

```text
SettingsHomePage.kt
ThemeSettingsPage.kt
FloatingSettingsPage.kt
LyricsSettingsPage.kt
SystemSettingsPage.kt
AboutPage.kt
```

This is the preferred place for settings UI changes.

### ui/theme

Theme palettes, color definitions, and MainActivity theme helpers. Runtime persistence lives in `settings/store`, not here.

### util

Shared constants and small app-wide utilities. Broadcast/service action strings live in `BroadcastActions.kt`.

## Runtime flow

```text
MediaNotificationListener
  -> BroadcastActions.MEDIA_UPDATE
  -> FloatingLyricsService
  -> LyricsRepository
  -> LyricsProvider / LocalLyricsProvider
  -> FloatingLyricsRenderer
  -> FloatingWindowController.textView
```

A simplified flow:

1. The notification listener observes playback metadata and state.
2. It sends a local app broadcast with title, artist, package, playing state, duration, and position.
3. `FloatingLyricsService` accepts updates only from the selected media source.
4. The service asks `LyricsRepository` for lyrics.
5. The repository checks local lyrics first, then the selected online provider.
6. The renderer parses LRC timestamps and updates the current lyric line.
7. The window controller owns the visible floating TextView.

## Design rules

Prefer these boundaries:

```text
UI pages              call settings stores and service commands
Settings stores       own SharedPreferences keys
Lyrics repository     owns provider order and cache policy
Lyrics providers      own one source only
Floating service      orchestrates, but avoids UI/window details
Floating controller   owns WindowManager and view styling
Renderer              owns parsed lyrics and current-line timing
```

Avoid these patterns:

```text
Direct SharedPreferences access from random UI files
New broadcast action strings scattered across classes
New lyric-source logic inside FloatingLyricsService
WindowManager details inside MainActivity
Large page-specific UI blocks inside MainActivity
```

## Build notes

Common debug build during Android-only work:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

Full native rebuild requires Rust Android targets and `cargo-ndk`.
