# AirLyrics project structure

AirLyrics is split by responsibility so contributors can find the right room before touching code.

```text
app/src/main/java/com/andsi/airlyrics/
  app/                            App shell, lifecycle, navigation, actions and Activity-scoped helpers.
    controller/                   Thin coordinators for media, lyrics and floating-window actions.
    MainActivity*Ui.kt            Activity-scoped UI helpers extracted out of MainActivity.
  lyrics/                         Lyrics lookup, providers, repository and LRC parsing.
  media/                          Android media notification/session state.
  settings/                       Persistent settings stores and setting data models.
    model/
    store/
  floating/                       Foreground service, floating window, lyric renderer and service notification.
  ui/
    components/                   Reusable View helpers and small widgets.
    navigation/                   Page enums and bottom-tab UI.
    pages/                        Top-level app pages.
      settings/                   Settings sub-pages split by feature area.
    theme/                        Palette and theme extension helpers.
    widgets/                      Custom drawable/view widgets.
  common/                         Shared constants and small utilities.
```

## Feature modules

```text
lyrics/
  LyricsRepository.kt             Unified lyrics lookup entry point.
  LyricsProvider.kt               Provider interface for online/local lyrics sources.
  LocalLyricsProvider.kt          Local imported/saved lyrics source.
  NeteaseLyricsProvider.kt        NetEase lyrics provider.
  MusixmatchLyricsProvider.kt     Musixmatch lyrics provider.
  LyricsStorage.kt                Local .lrc import/read/save helpers.
  LrcParser.kt                    LRC timestamp parser and current-line lookup.

media/
  MediaNotificationListener.kt    Reads media sessions and broadcasts playback state.
  MediaSourceStore.kt             Saves the selected media package.

settings/
  model/                          Settings data models.
  store/                          SharedPreferences access points.
```

## Floating module

```text
floating/FloatingLyricsService.kt        Service coordinator and command receiver.
floating/FloatingWindowController.kt     WindowManager view creation, dragging, style and visibility.
floating/FloatingLyricsRenderer.kt       Parsed lyric lines and current-line rendering.
floating/model/CurrentMediaInfo.kt             Current media snapshot.
floating/FloatingServiceNotification.kt  Foreground-service notification.
```

`FloatingLyricsService` should stay small. Add window details to `FloatingWindowController`, lyric timing details to `FloatingLyricsRenderer`, and notification details to `FloatingServiceNotification`.

## Settings pages

Settings are split into feature files:

```text
ui/pages/settings/SettingsHomePage.kt
ui/pages/settings/FloatingSettingsPage.kt
ui/pages/settings/LyricsSettingsPage.kt
ui/pages/settings/SystemSettingsPage.kt
ui/pages/settings/AboutPage.kt
```

When adding a new setting:

1. Add the data field to `settings/model/` when it belongs to persisted app state.
2. Add read/write functions to the matching `settings/store/*Store.kt`.
3. Add the UI control to the matching `ui/pages/settings/*Page.kt`.
4. Services/controllers should read settings from the store, not directly from `SharedPreferences`.

## Lyrics providers

Lyrics lookup goes through `LyricsRepository`. To add a new source, implement `LyricsProvider`, register it in the repository, then expose it in `LyricsSettingsStore` and `LyricsSettingsPage`.

## More docs

- `ARCHITECTURE.md` explains runtime flow and module boundaries.
- `CONTRIBUTING.md` explains build, commit, and contribution workflow.
- `MUSIXMATCH_TESTING.md` explains the Musixmatch smoke-test flow.
