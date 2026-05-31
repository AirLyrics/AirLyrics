# Localization Guide

AirLyrics uses English as the default fallback language. Simplified Chinese is maintained by the author.

## UI strings

Short UI text lives in Android string resources:

- `app/src/main/res/values/strings.xml` is the default English fallback.
- `app/src/main/res/values-zh-rCN/strings.xml` is Simplified Chinese.
- New languages should add a new Android resource folder, for example `values-ja/strings.xml`.

Do not edit string names when translating. Translate values only.

## Long help text

Localized help documents live in assets:

- `app/src/main/assets/help/lyrics_format.en.md`
- `app/src/main/assets/help/lyrics_format.zh-CN.md`
- `app/src/main/assets/help/fuzzy_matching.en.md`
- `app/src/main/assets/help/fuzzy_matching.zh-CN.md`

New languages can add files such as `lyrics_format.ja.md` and `fuzzy_matching.ja.md`.

## Changelog

The changelog is maintained in English only:

- `app/src/main/assets/changelog.txt`

Please do not add localized changelog copies unless this policy changes.

## Terms

- AirLyrics: do not translate
- LRC: do not translate
- Plain LRC: 普通歌词
- Enhanced LRC: 逐字歌词
- Online lyrics: 联网歌词
- Local lyrics: 本地歌词
- Lyrics offset: 歌词偏移
- Fuzzy matching: 弱匹配
- Click-through: 触摸穿透
- Floating lyrics: 悬浮歌词

## Before submitting

Run the localization checker before opening a PR:

```bash
./scripts/check_localization.sh
```

Every localized `strings.xml` must keep the same string names as `app/src/main/res/values/strings.xml`. Do not add generated/hash-like keys such as `ui_title_a1b2c3`; use stable semantic names instead.

## Placeholders

Keep placeholders such as `%1$s`, `%2$d`, and line breaks intact. They are used by Android formatting.

## User data

Do not translate or pass user data through localization helpers: song titles, artist names, album names, file names, lyrics, paths, and player names must remain unchanged.
