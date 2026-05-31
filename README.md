<div align="center">

<img src="docs/assets/airlyrics-logo.png" width="120" alt="AirLyrics Logo" />

# AirLyrics

Android floating lyrics app.

A lightweight Android app that automatically detects media playback and displays synced lyrics in a customizable floating window.

[English](README.md) · [简体中文](README.zh-CN.md)

<br />

[Download](https://github.com/AndSi-327/AirLyrics/releases) · [Documentation](docs/) · [Report Bug](https://github.com/AndSi-327/AirLyrics/issues)

<br />

[![Platform](https://img.shields.io/badge/platform-Android-brightgreen?style=flat-square)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-blueviolet?style=flat-square)]()
[![Rust](https://img.shields.io/badge/Rust-lyrics--core-orange?style=flat-square)]()
[![Release](https://img.shields.io/github/v/release/AndSi-327/AirLyrics?style=flat-square)](https://github.com/AndSi-327/AirLyrics/releases)

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

https://github.com/AndSi-327/AirLyrics/releases

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
      <img src="docs/assets/screenshot-media-source.jpg" width="260" alt="Media source" />
      <br />
      <sub>Media Source</sub>
    </td>
    <td align="center">
      <img src="docs/assets/screenshot-floating-settings.jpg" width="260" alt="Floating window settings" />
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
| [LYRICS_FORMAT.md](docs/LYRICS_FORMAT.md) | Supported lyrics formats and local import behavior |
| [LOCALIZATION.md](docs/LOCALIZATION.md) | Translation and localization guide |
| [TESTING.md](docs/TESTING.md) | Testing checklist |
| [CONTRIBUTING.md](docs/CONTRIBUTING.md) | Contribution guide |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Project architecture |

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
git clone https://github.com/AndSi-327/AirLyrics.git
cd AirLyrics
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

<div align="center">

AirLyrics

Floating lyrics for Android.

</div>
