# Project Architecture

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics is an Android floating lyrics application. The Android side is written in Kotlin,
while online lyrics sources are integrated through the native lyrics core written in Rust.

The codebase is divided by responsibility into media detection, lyrics lookup, local lyrics storage,
floating window rendering, settings persistence, and UI screens.

## Runtime Flow

```text
Music app
  -> Android media notification / media session
  -> MediaNotificationListenerService
  -> Current media state broadcast
      -> MainReceivers
          -> MediaSourceController
          -> MainGraph
      -> FloatingLyricsService
          -> LyricsRepository
              -> LocalLyricsProvider
              -> NeteaseLyricsProvider or MusixmatchLyricsProvider
              -> LyricsStorage for optional local saving
          -> FloatingLyricsWindow
          -> FloatingLyricsRenderer
```

The main UI and the floating lyrics service receive media state updates separately.
The main UI displays state and handles user actions, while the floating lyrics service
looks up lyrics, manages the floating window, and updates the displayed lyrics.

Local lyrics always take priority unless the caller explicitly skips the local lookup.
Online lyrics lookup only runs when allowed by the user settings.

## Main Android Packages

```text
app/              Main UI entry point, dependency assembly, lifecycle coordination, and feature control
media/            Media notification and media session reading, plus selected player persistence
lyrics/           Lyrics lookup, providers, parsing, importing, display formatting, and storage
floating/         Foreground service, floating window control, and lyrics rendering
settings/         Settings models and settings persistence
ui/               Screens, shared components, navigation, themes, and widgets
i18n/             Localization utilities and text handling
common/           Shared constants
```

## App Shell

`MainActivity` is a thin UI entry point. It only creates `MainGraph`
and forwards lifecycle events such as creation, resume, and destruction to it.

`MainGraph` is the dependency assembly entry point and lifecycle coordinator for the main UI.
Permission results, activity result callbacks, broadcast handling, page rendering, and feature-specific logic
are split into `controller/`, `contracts/`, `host/`, `lifecycle/`, `platform/`, `render/`, and `state/`.

Important files:

```text
app/MainActivity.kt
app/MainGraph.kt
app/controller/MediaSourceController.kt
app/controller/LyricsController.kt
app/controller/FloatingController.kt
app/contracts/MainAppContracts.kt
app/platform/PermissionHelper.kt
app/lifecycle/MainLaunchers.kt
app/lifecycle/MainReceivers.kt
app/render/MainHandRenderer.kt
```

## Media Detection

`MediaNotificationListenerService` reads active media notifications and media sessions,
then broadcasts the current playback state.

`MediaSessionObserver` listens for active media sessions, registers controller callbacks,
and applies one shared controller selection strategy when a media app exposes multiple
sessions. `CurrentMediaReader` owns that strategy: prefer playing controllers that
can produce a media title, then controllers with a media title, then playing,
metadata-bearing, or first usable sessions as fallbacks.

`MediaSourceStore` stores the package name of the media app selected by the user,
which allows the app to handle cases where multiple music apps are active at the same time.

The media screen displays the current media item and available players.
Refresh operations only update the relevant media state and do not rebuild the entire UI.

## Lyrics Lookup

`LyricsRepository` is the unified entry point for lyrics lookup.
Lyrics operations in the UI are usually started by `LyricsController`,
while the floating lyrics service also performs lyrics lookup directly when the current media changes.

The default flow is:

1. Look up locally imported or previously saved lyrics.
2. If no local lyrics are found, query the online provider selected in settings.
3. Save online results locally according to the user settings.
4. When word-by-word lyrics are enabled, try to attach locally saved enhanced or word-by-word lyrics.

Unless the request explicitly skips the local lookup, `LocalLyricsProvider`
always takes priority over online providers.

## Floating Lyrics

`FloatingLyricsService` is the coordination entry point for the foreground service
and floating lyrics features.

It receives media changes, looks up lyrics, creates the foreground notification,
and coordinates the floating lyrics window with the lyrics renderer.
It does not select or register media controllers directly; media session selection
belongs to the `media/` layer.

`FloatingLyricsWindow` manages creation, updates, and removal of the floating window,
as well as window position, appearance, locking, and touch-through behavior.

`FloatingLyricsRenderer` manages the lyrics timeline, current line selection,
text updates, line transition animations, and word-by-word highlighting.

The floating window supports appearance customization, locking, touch-through mode,
position persistence, lyrics offset, line transition animations,
and word-by-word highlighting when word-level lyrics are available.

## Settings

Settings are read and written through dedicated storage classes under `settings/store/`.
UI screens do not access raw `SharedPreferences` keys directly.

Settings for different features are managed separately instead of being concentrated in a single file.

## Localization

Short UI strings are stored in Android string resources.

Longer text is stored under `assets/` and loaded according to the current language.

`i18n/` provides shared language detection, text formatting, and feature-specific text helpers.
