# Project Structure

[English](PROJECT_STRUCTURE.md) · [简体中文](PROJECT_STRUCTURE.zh-CN.md)

Use this as a quick map before editing code.

```text
app/src/main/java/com/andsi/airlyrics/
  app/                         MainActivity shell, render helpers, actions and controllers
    controller/                Media, lyrics and floating-window coordinators
  common/                      Shared broadcast/action constants
  floating/                    Foreground service, window controller and lyric renderer
    model/                     Current media data used by floating service
  i18n/                        Localized labels and formatting helpers
  lyrics/                      Lookup repository, providers, parser, formatter and storage
    display/                   Display formatting for original/translation modes
    parser/                    LRC parser and line lookup
    providers/                 Local, NetEase and Musixmatch providers
    storage/                   Local lyrics files, index, paths and karaoke codec
  media/                       Notification listener and selected media source store
  settings/
    model/                     Settings data contracts
    store/                     Persistent settings stores
  ui/
    components/                Reusable View helpers and dialogs
    model/                     UI action contract
    navigation/                Page enums and bottom tabs
    pages/                     Media and floating pages
      settings/                Settings home, lyrics, system and about pages
    theme/                     Palette and theme helpers
    tokens/                    Spacing, text size and motion constants
    widgets/                   Custom View widgets
```

## Native module

```text
lyrics-core/                   Rust native lyrics core
```

The Android app loads `libairlyrics_lyrics.so` from `app/src/main/jniLibs/`.

## Resource highlights

```text
app/src/main/res/values/strings.xml          English fallback strings
app/src/main/res/values-zh-rCN/strings.xml   Simplified Chinese strings
app/src/main/assets/changelog.txt            English changelog shown in About page
scripts/check_localization.sh                Resource key validation script
```

## Rule of thumb

- UI page layout belongs under `ui/pages/`.
- Persistent setting logic belongs under `settings/store/`.
- Runtime floating-window behavior belongs under `floating/`.
- Lyrics lookup and parsing belong under `lyrics/`.
- Media-source detection belongs under `media/`.
