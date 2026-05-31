# AirLyrics Documentation

[English](README.md) · [简体中文](README.zh-CN.md)

This directory contains the project documentation for AirLyrics. The main README is intentionally short; details live here so users can download the APK quickly while contributors can still find the deeper notes.

## User documents

| Document | Use it for |
| --- | --- |
| [Lyrics Format](LYRICS_FORMAT.md) | Local lyrics import, normal LRC, translation lines and enhanced / word-by-word LRC |
| [Testing](TESTING.md) | Manual release checks and automated test commands |
| [Localization](LOCALIZATION.md) | Adding or improving UI translations |

## Contributor documents

| Document | Use it for |
| --- | --- |
| [Contributing](CONTRIBUTING.md) | Development workflow and contribution rules |
| [Architecture](ARCHITECTURE.md) | How Android UI, media detection, lyrics lookup and floating service fit together |
| [Project Structure](PROJECT_STRUCTURE.md) | Quick source-tree map |
| [Settings Module](SETTINGS_MODULE.md) | Persistent settings contracts and stores |
| [Lyrics Module](LYRICS_MODULE.md) | Repository, providers, parser, storage and display formatting |
| [Lyrics Lookup Cancellation](LYRICS_LOOKUP_CANCELLATION.md) | Latest-request-wins lookup runner and cancellation behavior |
| [Lyrics Storage Refactor](LYRICS_STORAGE_REFACTOR.md) | Current local lyrics storage split and migration notes |
| [Rust Lyrics Core](RUST_NETEASE_LYRICS.md) | Native lyrics provider build and runtime bridge |
| [Musixmatch Testing](MUSIXMATCH_TESTING.md) | Manual checks for Musixmatch lookup and translations |

## Language rule

English documents use the normal file name, for example `LYRICS_FORMAT.md`.
Simplified Chinese documents use `.zh-CN.md`, for example `LYRICS_FORMAT.zh-CN.md`.
---

## License

AirLyrics is licensed under the MIT License.

See [LICENSE](../LICENSE) for details.
