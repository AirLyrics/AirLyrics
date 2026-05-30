# Musixmatch source testing checklist

Musixmatch is available as a user-selected lyrics search source. Local lyrics are still checked first.

## Build commands

Use the full build when testing Musixmatch because the Rust library must include the Musixmatch provider:

```bash
./gradlew :app:assembleDebug
```

Use the skip flag only when checking Kotlin/UI changes that do not touch Rust:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

## Manual test flow

1. Open AirLyrics.
2. Go to Settings -> Lyrics settings.
3. Set lyrics search source to `Musixmatch`.
4. Play an overseas song with title, artist and duration metadata.
5. Show the floating lyrics window.
6. Verify that the floating window shows ordinary synchronized LRC lyrics.
7. Stop playback, restart the same song, and verify local cache is used first after successful save.

Musixmatch testing does not cover word-by-word lyrics. Word-by-word display is local-only and should be tested by importing an enhanced LRC file from the app.

## Expected source behavior

- `只使用本地`: no online request; missing lyrics should show a local-only missing message.
- `网易云音乐`: local first, then NetEase.
- `Musixmatch`: local first, then Musixmatch.

## Useful log filter

```bash
adb logcat | grep -E 'AirLyricsLyrics|Musixmatch|airlyrics'
```

## Failure messages worth checking

- `Musixmatch 未找到歌词`: normal miss, try another song or clean metadata.
- `Musixmatch 网络请求失败`: network, DNS, proxy or TLS problem.
- `Musixmatch 请求过于频繁，请稍后再试`: rate-limit or temporary block.
- `Musixmatch 暂时需要访问凭据`: Musixmatch changed anonymous access; credentials support may be needed.
- `Musixmatch 歌词受限，无法获取`: restricted lyrics.

## Good first songs

Use popular English/Japanese tracks with clean title and artist metadata. Avoid remixes, live versions, sped-up edits and titles with long bracket suffixes for the first smoke test.

## Translation language test

Musixmatch no longer exposes a manual translation-language selector in the app UI.

When Musixmatch is selected, AirLyrics uses the device's default system language as the translation language code. For example, a Chinese system tries `zh`, and an English system tries `en`. Translation is still optional and depends on Musixmatch coverage. If the original lyrics succeed but translation is unavailable, the lookup still succeeds and the app keeps showing original lyrics.

The floating-window page only controls how lyrics are displayed, such as original / translation content mode, current / previous / next line range, and whether locally imported enhanced LRC word-by-word timing is preferred. When testing translation, set the floating-window display mode to `原文 + 翻译` or `仅翻译`.

Useful log filter:

```bash
adb logcat | grep -E 'AirLyricsLyrics|Musixmatch|translation|airlyrics'
```
