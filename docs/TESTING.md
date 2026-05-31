# Testing

[English](TESTING.md) · [简体中文](TESTING.zh-CN.md)

AirLyrics should be tested at three levels: unit tests, Android instrumentation tests and manual device checks.

## Unit tests

Run fast JVM tests:

```bash
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
```

Important coverage areas:

- Normal LRC parsing.
- Compact one-line LRC exports.
- Multi-timestamp lines.
- Original / translation merge behavior.
- Current-line lookup.
- Enhanced / word-by-word LRC parsing.
- Karaoke codec round trip.
- Local lyrics fallback behavior.

## Instrumentation tests

Run on a device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

Important coverage areas:

- Local lyrics save/read/list/delete.
- Lyrics index persistence.
- Karaoke save/read/delete behavior.
- Overwrite protection.
- Storage Access Framework folder behavior.

## Build check

Kotlin-only check:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

Full build with native core:

```bash
./gradlew :app:assembleDebug
```

## Manual release checklist

Before a public release, test on at least one real Android device:

- First launch.
- Overlay permission flow.
- Notification access flow.
- Media source detection.
- Manual media source selection.
- Floating window start / stop.
- Lock and touch-through behavior.
- Floating style changes.
- Lyrics offset adjustment and persistence.
- Online search with NetEase.
- Online search with Musixmatch when available.
- Local normal LRC import.
- Local enhanced / word-by-word LRC import.
- Recent local lyrics list.
- Theme switching.
- Language switching.
- App restart after settings changes.

## Test sample lyrics

Manual sample files live in:

```text
docs/test-lyrics-samples/
```
