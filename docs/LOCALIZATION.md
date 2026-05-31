# Localization

[English](LOCALIZATION.md) · [简体中文](LOCALIZATION.zh-CN.md)

AirLyrics uses English as the fallback language and Simplified Chinese as the first maintained translation.

## String resources

Short UI text lives in Android resources:

```text
app/src/main/res/values/strings.xml          English fallback
app/src/main/res/values-zh-rCN/strings.xml   Simplified Chinese
```

Add new languages by creating a new Android resource directory, for example:

```text
app/src/main/res/values-ja/strings.xml
```

Translate values only. Keep string names unchanged.

## Code-side localized labels

Some setting labels are produced by helper files under `i18n/`, such as:

```text
i18n/Localization.kt
i18n/LyricsSettingsText.kt
i18n/FloatingStyleText.kt
i18n/I18nFormatters.kt
```

When adding a new enum option, update both the enum and the corresponding localized label helper.

## Changelog policy

The in-app changelog is loaded from:

```text
app/src/main/assets/changelog.txt
```

It is currently maintained in English only.

## Documentation language policy

Repository docs are paired:

```text
SOME_DOC.md          English
SOME_DOC.zh-CN.md    Simplified Chinese
```

English README links to English docs. Chinese README links to Chinese docs.

## Validation

Run:

```bash
./scripts/check_localization.sh
```

The checker should fail when localized resources miss required keys, contain unexpected keys, or use generated-looking key names.

## Placeholders and formatting

Keep placeholders exactly the same:

```text
%1$s
%2$d
%%
```

Do not change required line breaks when a string is intentionally multiline.

## Terms

| English | Simplified Chinese |
| --- | --- |
| Floating lyrics | 悬浮歌词 |
| Media source | 媒体源 |
| Online lyrics search | 联网歌词搜索 |
| Local lyrics import | 本地歌词导入 |
| Enhanced lyrics | 增强歌词 / 逐字歌词 |
| Word-by-word lyrics | 逐字歌词 |
| Lyrics offset | 歌词偏移 |
| Touch-through | 触摸穿透 |
| Auto-save | 自动保存 |

Do not translate app names, package names, file names, song titles, artist names, album names, lyrics, paths or user-selected folders.
