# Project Architecture

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics uses Kotlin for the Android app and a Rust core for online lyrics lookup, connected through JNI.

## Project structure

| Directory | Responsibility |
| --- | --- |
| `app/` | The single Gradle module `:app`, containing the Android app and tests |
| `lyrics-core/` | Rust lyrics lookup, matching and parsing |
| `scripts/` | Build checks and development tools |
| `docs/` | User, contributor and architecture documentation |

Gradle builds `libairlyrics_lyrics.so` with `cargo ndk` into `app/src/main/jniLibs/`. Use `-Pairlyrics.skipRustBuild=true` when the native library is already available.

## Android responsibilities

| Package | Responsibility |
| --- | --- |
| `app` | Dependency wiring, lifecycle, ViewModels and user operations |
| `ui` | Pages, components and UI-facing interfaces |
| `media` | Media sessions, player selection and playback state |
| `lyrics` | Lyrics lookup, import, parsing and storage |
| `floating` | Foreground service, overlay controls and rendering |
| `settings` / `displayscope` | Persistent settings / app-based visibility rules |
| `core` / `design` / `feedback` / `i18n` | Shared models, design tokens, feedback contracts and localization |

## Runtime flow

```text
Music app → media → Main UI / FloatingLyricsService
                      → LyricsRepository → Local storage / JNI → Rust providers
                      → FloatingLyricsRenderer → Overlay
```

The main UI handles user actions; the floating service follows the selected player. Stale media snapshots and lookup results are discarded. Automatic lookups cannot override an active manual search.

Imports, edits and deletions notify the UI and floating service through `LyricsChangedBroadcast`. Cross-component actions use dedicated broadcast or command objects to avoid duplicate action and extra definitions.

## Package boundaries

`scripts/architecture/policy.json` defines the allowed dependencies in this generated table:

<!-- architecture-policy:start -->
```text
core         -> (none)
design       -> core.model
displayscope -> (none)
feedback     -> (none)
settings     -> core.model, core.prefs, core.color
lyrics       -> core
i18n         -> core.model, core.prefs
i18n.lyrics  -> lyrics.LyricsLookupErrorType, lyrics.LyricsLookupException, lyrics.storage.LyricsStorage
media        -> core.model, core.prefs, i18n.LocalizedServiceContext
ui           -> core.model, core.color, design, feedback.AirFeedback, i18n
floating     -> core, design, displayscope, feedback, i18n, i18n.lyrics, lyrics, media, settings
app          -> core, design, displayscope, feedback, i18n, i18n.lyrics, lyrics, media, settings, floating, ui
```
<!-- architecture-policy:end -->

- `app` wires feature packages together. `ui` accesses data and operations through interfaces, without depending on feature implementations or settings storage.
- `lyrics` and `media` are independent. `floating` can coordinate both, but cannot depend on the main UI.
- Rules use the most specific registered group: allowing `i18n` does not allow `i18n.lyrics`. Interfaces must not expose forbidden implementation types either.

Run the source, JVM dependency and policy checks with JDK 17 and Python 3.10+:

```bash
./scripts/check_architecture_boundaries.sh
```

`--source-only` is a quick precheck. Register new packages or dependency changes with a reason, then use `--update-docs` to refresh both language tables. Reflection strings, native code and external library internals remain outside the checks; code review is still required.

## State and storage

- `MainActivity` creates `MainGraph` to wire dependencies. `MainViewModel` owns UI state, `SavedStateHandle` preserves restorable operations, and `MainGraph` handles UI effects.
- `LyricsStorage` manages lyrics and indexes in app storage or a user-selected SAF directory. Writes are serialized; word timing and generated plain LRC are updated together, with rollback attempted on failure.
- Desired overlay visibility is separate from actual visibility. Pausing, locking the screen or visibility restrictions do not clear the user's intent to keep it enabled.
- Settings use `settings/store/`. UI text uses Android string resources; longer help and changelog content lives in `assets/`.

## Lyrics source limits and matching

### Spicy Lyrics

Spicy supports plain and word-by-word lyrics and must be enabled manually. Lyrics and edits stay inside the app, expire 30 days after acquisition, and cannot be exported, shared as files or backed up. Source and contributor credits are preserved when displaying lyrics; requests are subject to rate limits. See the [Spicy API terms](https://developers.spicylyrics.org/terms).

Set `airlyrics.spicy.clientKey` in an untracked `local.properties` or user Gradle properties file, or use `AIRLYRICS_SPICY_CLIENT_KEY`. Use an `sl_pk_` key with client access enabled, never an `sl_sk_` secret key. Do not commit real keys. Without a configured key, Spicy is unavailable.

### Lyrics matching

Manual imports take priority over cached and online lyrics. Selected providers run in priority order and share title, artist, duration and album scoring. Queries broaden when needed, so a match may be a different recording. Spicy searches Spotify for candidates before fetching lyrics; line timing can remain available when word timing is missing.

International title search first tries to resolve an international title, then uses the same matching flow. It preserves the original song identity for storage. See `lyrics-core/src/matching.rs` and the [matching documentation](lyrics-matching.md).
