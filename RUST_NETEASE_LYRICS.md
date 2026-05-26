# Air Lyrics Rust NetEase lyric core

This branch makes NetEase Cloud Music the main online lyric provider through a Rust native core, following the same dependency route used by Waylyrics:

- Rust crate: `lyrics-core`
- NetEase API library: `ncmapi2` through the `ncmapi` package alias
- Android bridge: hand-written JNI
- Kotlin entry point: `NeteaseLyricsProvider`

## Build requirements

Install Rust and the Android native toolchain first:

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

Make sure Android Studio / SDK Manager has an NDK installed. Then build normally:

```bash
./gradlew :app:assembleDebug
```

The Gradle task `buildRustLyrics` runs before `preBuild` and writes native libraries into:

```text
app/src/main/jniLibs/arm64-v8a/libairlyrics_lyrics.so
app/src/main/jniLibs/armeabi-v7a/libairlyrics_lyrics.so
app/src/main/jniLibs/x86_64/libairlyrics_lyrics.so
```

For Kotlin-only checks on a machine without Rust/cargo-ndk, use:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

That skip flag is only for development checks. A real APK needs the Rust `.so` files.

## Runtime flow

```text
FloatingLyricsService
  -> local cached .lrc first
  -> LyricsFetcher
  -> NeteaseLyricsProvider
  -> NeteaseLyricsNative.fetchBestLyricsJson(...)
  -> Rust ncmapi2 search + lyric
  -> merged LRC saved back to local cache
```

LRCLIB is no longer used by `LyricsFetcher`. NetEase Rust is the main online provider.

## Matching logic

The Rust side searches with:

1. title + album + artist
2. title + artist
3. title

Candidates are scored by title, artist, duration, and album similarity. Live/remix/cover/instrumental style variants receive a penalty. The final lyric result returns original LRC, translated LRC, and a merged LRC used by the Android display.
