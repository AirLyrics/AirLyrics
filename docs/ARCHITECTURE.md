# Architecture

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics is an Android floating lyrics app. The Android app is written in Kotlin and uses a Rust native lyrics core for online provider access.

The current architecture is split around responsibilities instead of screens only: media detection, lyrics lookup, local lyrics storage, floating-window rendering, settings persistence and UI pages each have their own home.

## Runtime flow

```text
Music app
  -> Android media notification / media session
  -> MediaNotificationListener
  -> MainActivity / FloatingLyricsService
  -> LyricsRepository
      -> LocalLyricsProvider
      -> NeteaseLyricsProvider or MusixmatchLyricsProvider
      -> LyricsStorage optional local save
  -> FloatingLyricsRenderer
  -> FloatingWindowController
```

Local lyrics are always checked first unless a caller explicitly bypasses local lookup. Online lookup only runs when the user's settings allow it.

## Android modules

```text
app/              Activity shell, navigation, permission launchers and controllers
media/            Media notification/session reading and selected-player persistence
lyrics/           Lyrics repository, providers, parser, display formatter and storage
floating/         Foreground service, WindowManager controller and renderer
settings/         Data models and SharedPreferences stores
ui/               Pages, reusable components, navigation, theme and widgets
common/           Shared constants
```

## App shell

`MainActivity` owns Android lifecycle glue: activity result launchers, permission flows, broadcast receivers and navigation dispatch. Feature logic is delegated to controllers and page files.

Important files:

```text
app/MainActivity.kt
app/MainActivityRenderer.kt
app/MainUiActionsFactory.kt
app/controller/AppMediaController.kt
app/controller/LyricsController.kt
app/controller/FloatingController.kt
```

## Media detection

`MediaNotificationListener` reads active media notifications and broadcasts snapshots. `MediaSourceStore` remembers the selected media package so AirLyrics can follow the right player when multiple apps expose playback state.

The Media page shows current media and available players. Refresh actions update only the relevant media state instead of rebuilding the whole app shell.

## Lyrics lookup

`LyricsRepository` is the central entry point. Its normal lookup order is:

1. Local imported or saved lyrics.
2. Online provider selected in settings.
3. Optional save of online result into local storage.
4. Optional local enhanced / word-by-word lyrics attachment when the feature is enabled.

`LyricsFetcher` remains as a compatibility wrapper for older call sites. New code should prefer `LyricsRepository` or the app-level lyrics controller.

## Floating lyrics

`FloatingLyricsService` is the foreground service coordinator. Window details belong to `FloatingWindowController`, while parsed lyric timing and text updates belong to `FloatingLyricsRenderer`.

The floating window supports style changes, lock state, touch-through behavior, position persistence, line switching animation and karaoke highlight rendering when enhanced lyrics are available.

## Settings

Settings are stored through dedicated stores under `settings/store/`. UI pages should not directly write raw `SharedPreferences` keys unless a new store is being introduced.

## Localization

Short UI strings live in Android resources. Longer documents and changelog-style text are handled as files. See [Localization](LOCALIZATION.md).
