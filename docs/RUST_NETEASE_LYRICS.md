# Rust Lyrics Core

[English](RUST_NETEASE_LYRICS.md) · [简体中文](RUST_NETEASE_LYRICS.zh-CN.md)

AirLyrics uses a Rust native library for online lyrics provider work. The Android side talks to it through JNI wrapper classes.

## Native module

```text
lyrics-core/
  src/lib.rs
  src/lrc.rs
  src/musixmatch.rs
  Cargo.toml
```

The Android app loads:

```text
libairlyrics_lyrics.so
```

from:

```text
app/src/main/jniLibs/
```

## Android bridge

```text
lyrics/providers/NeteaseLyricsNative.kt
lyrics/providers/NeteaseLyricsProvider.kt
lyrics/providers/MusixmatchLyricsNative.kt
lyrics/providers/MusixmatchLyricsProvider.kt
```

Provider classes convert Kotlin requests into native calls and convert native JSON/results back into `LyricsProviderResult`.

## Build requirements

- Rust toolchain
- Android NDK
- `cargo-ndk`

Install target and tool:

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

Build the app normally:

```bash
./gradlew :app:assembleDebug
```

For Kotlin-only development checks when native libraries are already available:

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

The skip flag is not a replacement for a real release build.

## Provider behavior

The online provider path is selected by `LyricsSettingsStore` and routed through `LyricsRepository`.

Local lyrics still win before native online lookup runs.

## Debugging

Useful filters:

```bash
adb logcat | grep -E 'AirLyricsLyrics|Netease|Musixmatch|airlyrics'
```

Common failure areas:

- Native library missing for the device ABI.
- NDK or Rust target not installed.
- Provider network failure.
- Online source returned no suitable match.
- Native call timed out or was cancelled by a newer request.
