# Settings Module

[English](SETTINGS_MODULE.md) · [简体中文](SETTINGS_MODULE.zh-CN.md)

Settings are centralized under `settings/model/` and `settings/store/`.

## Models

```text
settings/model/FloatingLyricsStyle.kt   Floating window visual style
settings/model/LyricsSettings.kt        Lyrics source, display and lookup behavior
settings/model/ThemeSettings.kt         Light / dark theme state
```

## Stores

```text
FloatingLyricsStyleStore.kt   Preset, text size, color, shadow, background, width, gravity, lock and position
LyricsSettingsStore.kt        Source, online fallback, auto-save, content mode, line range, animation, karaoke toggle
LyricsOffsetStore.kt          Per-song timing offset
MediaSourceStore.kt           Selected player package, under media module
LanguageSettingsStore.kt      App language preference helpers
QuickFloatingStore.kt         Remembered center-tab floating state
ThemeSettingsStore.kt         Theme mode persistence
```

## Rules

- UI reads and writes through stores.
- Services and controllers load settings from the same stores.
- Do not duplicate preference keys in multiple modules.
- Keep model classes data-only.
- Add localized labels through `i18n/` helpers when exposing enum values to users.

## Adding a new setting

1. Decide whether it belongs to an existing model.
2. Add a stable key in the matching store.
3. Provide a default value and safe migration behavior.
4. Add UI control in the correct page.
5. Apply it in the runtime module.
6. Update documentation and tests when needed.
