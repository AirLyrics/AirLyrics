# Testing

AirLyrics' safest regression checks are focused on the lyric core first.

## JVM unit tests

Run fast parser and codec tests without a device:

```bash
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
```

Covered areas:

- ordinary LRC parsing, compact one-line LRC exports, multi-tag lines
- original / translation merge behavior
- normalized local LRC storage format
- current-line binary search
- enhanced `.lrc` word timing parsing
- karaoke JSON round trip and plain LRC fallback conversion

## Android instrumentation tests

Run storage tests on a device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

Covered areas:

- managed local lyric save / read / list / delete flow
- index metadata persistence for title, artist and provider
- karaoke lyric save / read / delete-only-karaoke behavior
- overwrite protection

Keep new lyric features covered here before changing `LrcParser` or `LyricsStorage`.
