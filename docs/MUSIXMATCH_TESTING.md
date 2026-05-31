# Musixmatch Testing

[English](MUSIXMATCH_TESTING.md) · [简体中文](MUSIXMATCH_TESTING.zh-CN.md)

Musixmatch is one of the selectable online lyrics sources. It is useful for international songs, but availability depends on Musixmatch coverage and network behavior.

## Select Musixmatch

1. Open Settings.
2. Open Lyrics settings.
3. Choose Musixmatch as the lyrics source.
4. Keep online fallback enabled.
5. Play a track with clean title and artist metadata.
6. Refresh lyrics.

## Recommended smoke test

Use a popular track with:

- Clear title.
- Clear artist.
- No long remix/live/version suffix.
- Stable duration.

Avoid first tests with covers, live versions, sped-up edits or region-specific releases.

## Translation behavior

Musixmatch translation availability depends on the source result. AirLyrics does not guarantee that translation lines exist for every song.

To see translations when available, set the floating lyrics content mode to original + translation or translation only.

## Expected outcomes

A successful lookup may produce:

- Original lyrics only.
- Original lyrics with translation.
- No usable result.
- A provider error or rate limit.

No usable result should not crash the app. The user should still be able to import local lyrics.

## Debugging

```bash
adb logcat | grep -E 'AirLyricsLyrics|Musixmatch|translation|airlyrics'
```

Check:

- Whether the selected source is Musixmatch.
- Whether online fallback is enabled.
- Whether local lyrics are already taking priority.
- Whether the media app exposes correct title and artist.
- Whether the provider returns no lyrics, restricted lyrics or a temporary network error.
