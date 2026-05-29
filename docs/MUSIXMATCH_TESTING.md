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
6. Verify that the floating window shows synchronized lyrics.
7. Stop playback, restart the same song, and verify local cache is used first after successful save.

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
