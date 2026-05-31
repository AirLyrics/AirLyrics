<div align="center">

<img src="docs/assets/airlyrics-logo.png" width="120" alt="AirLyrics Logo" />

# AirLyrics

Android floating lyrics app.

A lightweight Android app that automatically detects media playback and displays synced lyrics in a customizable floating window.

[English](README.md) · [简体中文](README.zh-CN.md)

<br />

[Download](https://github.com/AndSi-327/android-floating-lyrics/releases) · [Documentation](docs/README.md) · [Report Bug](https://github.com/AndSi-327/android-floating-lyrics/issues)

<br />

[![Platform](https://img.shields.io/badge/platform-Android-brightgreen?style=flat-square)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-blueviolet?style=flat-square)]()
[![Rust](https://img.shields.io/badge/Rust-lyrics--core-orange?style=flat-square)]()
[![Release](https://img.shields.io/github/v/release/AndSi-327/android-floating-lyrics?style=flat-square)](https://github.com/AndSi-327/android-floating-lyrics/releases)

</div>

---

<div align="center">

<img src="docs/assets/screenshot-floating-lyrics.jpg" width="720" alt="AirLyrics floating lyrics" />

</div>

---

## Status

AirLyrics is under active development.

The app is usable, but compatibility may vary depending on Android version, device manufacturer, and the music app being used.

---

## Download

Download the latest APK from GitHub Releases:

https://github.com/AndSi-327/android-floating-lyrics/releases

After installation, grant the required permissions and select your music app as the media source.

---

## Features

- Automatically detects current media playback
- Displays synced lyrics in a floating window above other apps
- Provides online lyrics search
- Supports local lyrics import
- Supports enhanced / word-by-word lyrics import
- Customizable floating window style
- Adjustable lyrics offset
- Supports original and translated lyrics
- Supports light and dark themes

---

## Quick Start

1. Install AirLyrics.
2. Grant overlay permission.
3. Grant notification access.
4. Select the current media source.
5. Start the floating lyrics window.
6. Play music in your music app.
7. Search lyrics online or import local lyrics manually.

---

## Screenshots

<div align="center">

<table>
  <tr>
    <td align="center">
      <img src="docs/assets/screenshot-media-source.jpg" width="240" alt="Media source" />
      <br />
      <sub>Media Source</sub>
    </td>
    <td align="center">
      <img src="docs/assets/screenshot-lyrics-source.jpg" width="240" alt="Lyrics search and local lyrics settings" />
      <br />
      <sub>Lyrics Search</sub>
    </td>
    <td align="center">
      <img src="docs/assets/screenshot-floating-settings.jpg" width="240" alt="Floating window settings" />
      <br />
      <sub>Customization</sub>
    </td>
  </tr>
</table>

</div>

---

## Permissions

AirLyrics requires several Android permissions to work properly.

| Permission | Purpose |
| --- | --- |
| Display over other apps | Show the floating lyrics window |
| Notification access | Detect current media playback |
| File picker | Import local lyrics files |

---

## Documentation

More details are available in the docs directory.

| Document | Description |
| --- | --- |
| [Documentation Home](docs/README.md) | Browse all English documentation by topic |
| [Lyrics Format](docs/LYRICS_FORMAT.md) | Local import, normal LRC and word-by-word lyrics |
| [Contributing](docs/CONTRIBUTING.md) | Development environment, workflow and code map |
| [Testing](docs/TESTING.md) | Build, automated tests and manual release checks |
| [Architecture](docs/ARCHITECTURE.md) | Module layout and runtime flow |

---

## Build From Source

### Requirements

- Android Studio
- JDK 17
- Android SDK
- Android NDK
- Rust toolchain
- cargo-ndk

### Clone

```bash
git clone https://github.com/AndSi-327/android-floating-lyrics.git
cd android-floating-lyrics
```

### Build

```bash
./gradlew assembleDebug
```

The APK will be generated under:

```txt
app/build/outputs/apk/debug/
```

For Rust lyrics core build details, see [RUST_NETEASE_LYRICS.md](docs/RUST_NETEASE_LYRICS.md).

---

## Contributing

Contributions are welcome.

Good areas to contribute:

- Bug reports
- Compatibility testing
- Translation improvements
- Documentation improvements
- UI text polishing

Please read [CONTRIBUTING.md](docs/CONTRIBUTING.md) before submitting changes.

---

## Credit

- [waylyrics](https://github.com/waylyrics/waylyrics)

---

## License

AirLyrics is licensed under the MIT License.

See [LICENSE](LICENSE) for details.

