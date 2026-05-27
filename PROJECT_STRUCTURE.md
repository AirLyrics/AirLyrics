# Project structure

AirLyrics is split by responsibility so future features do not all land in `MainActivity.kt`.

```text
app/src/main/java/com/andsi/airlyrics/
  MainActivity.kt                 # Activity entry point and page orchestration
  core/
    lyrics/                       # LRC parsing, local lyrics storage, lyric fetching providers
    media/                        # Media-session listener and selected player storage
    settings/                     # Small SharedPreferences stores used by UI state
  floating/                       # Foreground service, window controller, floating-window style persistence
  ui/
    components/                   # Reusable view builders, text rows, cards and small animations
    widgets/                      # Reusable custom views
    theme/                        # Light/dark palettes and theme persistence
    pages/                        # Page-level builders split out of MainActivity
  util/                           # Shared contracts such as broadcast / service actions
```

The first UI cleanup moved generic view-building helpers out of `MainActivity.kt` into `ui/components/MainUiComponents.kt`. Page orchestration still lives in `MainActivity.kt`, while common cards, text rows, action buttons, spacers and soft-press animations are now reusable.

The second UI cleanup moved app palettes and theme persistence into `ui/theme`. `AirLyricsTheme` owns the light/dark color palettes and color picker swatches, `ThemeStore` owns dark-mode persistence, and `MainActivityTheme` exposes small extension properties used by the existing view code.

The third UI cleanup moved page builders into `ui/pages`: `MediaPage.kt`, `FloatingPage.kt`, and `SettingsPages.kt`. `MainActivity.kt` now focuses more on app lifecycle, navigation, permissions, and shared page actions.

The fourth cleanup split floating-window mechanics out of `FloatingLyricsService.kt` into `floating/FloatingWindowController.kt`. The service now owns media state, lyric loading, foreground lifecycle and commands; the controller owns `WindowManager`, window creation/removal, dragging, style application, lock state and click-through behavior.

The fifth cleanup moved broadcast / service action strings into `util/BroadcastActions.kt` and the quick floating-window button state into `core/settings/QuickFloatingStore.kt`. UI, media listener and service code now share a small contract object instead of reaching into `FloatingLyricsService` for constants, and MainActivity no longer reads `floating_quick_control` preferences directly.

Recommended next refactor steps:

1. Split lyric rendering / playback timing out of `FloatingLyricsService.kt`.
2. Move permission and file helpers into `util`.
3. Gradually move shared page actions from `MainActivity.kt` into page-specific controllers.
