# Project Architecture

[English](ARCHITECTURE.md) · [简体中文](ARCHITECTURE.zh-CN.md)

AirLyrics is an Android floating lyrics application. The Android application is written in Kotlin,
while the online lyrics integrations are implemented by a Rust native core exposed through JNI.

The codebase is organized around media detection, lyrics lookup and storage, floating-window
rendering, settings persistence, localization, UI rendering, and application-level coordination.

## Project Layout

```text
app/              Android application module and Kotlin sources
lyrics-core/      Rust lyrics lookup core compiled as a native library
scripts/          Repository checks and development helpers
docs/             User, contributor, format, and architecture documentation
```

The Gradle project contains one Android module, `:app`. `lyrics-core/` is a Cargo crate rather than
a Gradle subproject. The app module's `buildRustLyrics` task builds the crate with `cargo ndk` and
places `libairlyrics_lyrics.so` under `app/src/main/jniLibs/`. Normal pre-build tasks depend on it
unless `-Pairlyrics.skipRustBuild=true` is supplied.

## Runtime Flow

```text
Music app
  -> Android media session and notification lifecycle
  -> MediaNotificationListenerService
      -> MediaSessionObserver
          -> CurrentMediaReader
          -> CurrentMediaBroadcast
              -> MainReceivers
                  -> MediaSourceController
                  -> Main UI invalidation
              -> FloatingLyricsService
                  -> MediaSnapshotGate
                  -> LyricsLookupRunner
                      -> LyricsRepository
                          -> LocalPlainLyricsProvider
                              -> LyricsStorage
                          -> NeteasePlainLyricsProvider or MusixmatchPlainLyricsProvider
                              -> JNI
                              -> lyrics-core
                          -> optional local save and local word-by-word attachment
                  -> FloatingLyricsRenderer
                  -> FloatingLyricsWindow
```

The main UI and the floating service consume media broadcasts independently. The main UI displays
state and handles user actions. The floating service accepts updates only from the selected media
package, looks up lyrics, maintains playback timing, and updates the overlay.

While floating lyrics are active, the service also periodically reads the selected media session
through `CurrentMediaReader`. This recovers delayed or missed listener updates and keeps the selected
session synchronized. `MediaSnapshotGate` rejects older sequenced snapshots so stale callbacks
cannot move playback state backwards.

Lyrics lookup uses latest-request-wins semantics. `LyricsLookupRunner` cancels the previous Kotlin
worker and its native lookup when a newer song or reload request arrives. The floating service also
checks its request key before applying a completed result.

Successful lyrics imports use a separate durable-change flow:

```text
Lyrics import
  -> MainLyricsWorkflow
  -> LyricsController
  -> LyricsStorage
  -> LyricsChangedBroadcast
      -> MainReceivers -> rebuild affected UI
      -> FloatingLyricsService -> reload when the changed song is current
```

## Main Android Packages

```text
app/              Composition root, lifecycle coordination, controllers, workflows, and UI adapters
core/             Dependency-stable models, color helpers, and preference abstractions
design/           Shared UI design tokens
media/            Media-session observation, current-media models, broadcasts, and source persistence
lyrics/           Lookup, cancellation, providers, parsing, importing, formatting, and storage
floating/         Foreground service, service commands, overlay control, and lyrics rendering
settings/         Feature-specific settings persistence and status-popup policy
ui/               Screens, feedback surfaces, components, navigation, themes, UI models, and async UI helpers
i18n/             Language selection, localized assets, and user-facing text formatters
```

Transient feedback is surface-owned: the main Activity uses an anchored Snackbar host, while the
floating Service uses a Toast host. Both read the same status-popup preference.

## Package Boundaries

The current top-level Kotlin package dependencies are:

```text
core      -> (none)
design    -> (none)
settings  -> core
lyrics    -> core
i18n      -> core, lyrics
media     -> core, i18n
ui        -> core, design, i18n
floating  -> core, design, i18n, lyrics, media, settings
app       -> core, design, floating, i18n, lyrics, media, settings, ui
```

Arrows mean “imports from”; generated `R` and platform libraries are omitted.

The important boundary rules are:

- `core/` and `design/` do not depend on feature packages.
- `lyrics/` and `media/` remain independent of each other and do not depend on `app/`, `floating/`,
  `settings/`, or `ui/`.
- `ui/` does not import concrete media, lyrics, settings, or floating implementations. It receives
  UI-facing data and actions through interfaces under `ui/model/`.
- `floating/` may coordinate media, lyrics, and settings, but it does not depend on the main app
  shell or UI pages.
- `app/` is the composition layer that is allowed to connect all feature packages.

`scripts/check_architecture_boundaries.sh` enforces these import restrictions in CI.

## App-Local Communication Protocols

Cross-component communication is owned by dedicated protocol objects. Raw action and extra names
must not be duplicated by senders or receivers.

```text
CurrentMediaBroadcast
Owns media-update and media-source-lost broadcasts. MediaNotificationListenerService sends them;
MainReceivers / MediaSourceController and FloatingLyricsService consume them independently.

FloatingServiceCommand
Owns commands sent to FloatingLyricsService through startForegroundService or PendingIntent.
Callers construct typed command objects, and the service parses commands through the same object.

FloatingWindowStateBroadcast
Owns floating-window visibility, lock, and touch-through state broadcasts. FloatingLyricsService
sends actual window state; MainReceivers / FloatingController synchronize the main UI.

LyricsChangedBroadcast
Owns durable lyrics-change notifications keyed by SongIdentity. LyricsController publishes after a
successful import; the main UI refreshes and the service reloads only if the changed song is current.
```

The broadcasts are package-scoped and registered as not exported. The foreground notification is
created by `FloatingServiceNotification`; its actions create `PendingIntent`s through
`FloatingServiceCommand` and return to `FloatingLyricsService.handleCommand`.

`AppLocalProtocolGuardTest` ensures that app-local action strings remain in the four protocol-owner
files.

## App Shell and UI Boundary

`MainActivity` is a thin Android entry point. It creates `MainGraph` and forwards activity lifecycle
and saved-state callbacks.

`MainGraph` is the main-screen composition root and lifecycle coordinator. It assembles controllers,
activity-result launchers, broadcast receivers, the UI host, the renderer, the lyrics workflow, and
the app I/O executor. It also preserves navigation and pending lyrics-import state across activity
recreation.

Feature work is split as follows:

```text
controller/       Media-source, lyrics, and floating-feature orchestration
contracts/        Small app-layer dependency interfaces
host/             Adapters that implement UI host capabilities and MainUiActions
lifecycle/        Activity-result launchers and receiver registration
platform/         Android permission and navigation helpers
render/           Main view construction, references, and targeted invalidation
state/            Main-screen and pending-operation state
workflow/         Multi-step lyrics import and directory-selection flows
```

The handwritten screens under `ui/pages/` depend on `MainUiHost` and other UI models rather than
reading feature stores directly. `app/host/` converts media, lyrics, settings, and floating state
into page data and maps `MainUiActions` back to controllers. `LatestUiTaskRunner` prevents older
asynchronous page loads from overwriting newer UI state.

Important files:

```text
app/MainActivity.kt
app/MainGraph.kt
app/controller/MediaSourceController.kt
app/controller/LyricsController.kt
app/controller/FloatingController.kt
app/workflow/MainLyricsWorkflow.kt
app/lifecycle/MainLaunchers.kt
app/lifecycle/MainReceivers.kt
app/host/MainActivityUiHost.kt
app/render/MainHandRenderer.kt
ui/model/MainUiHost.kt
ui/model/MainUiActions.kt
```

## Media Detection

`MediaNotificationListenerService` provides the notification-listener permission boundary. Android
notification changes trigger a debounced session rescan, while `MediaSessionObserver` listens to
active-session changes and controller metadata or playback callbacks.

The observer registers one callback per media-session token and publishes the best usable controller
for each media package. `CurrentMediaReader` owns the shared selection rule used by the observer,
main UI, and floating service: prefer a titled playing controller, then a titled controller, then a
playing, metadata-bearing, or first usable controller.

`CurrentMediaInfo` contains the source package, song metadata, playback state, estimated position,
and a monotonically increasing snapshot sequence. `CurrentMediaBroadcast` transports that model
between Android components.

`MediaSourceStore` persists the package selected by the user. This selection scopes media display,
lyrics import and deletion, and floating-service updates when several players are active.

## Lyrics Lookup

`LyricsRepository` is the unified lookup entry point. UI lyrics operations are coordinated by
`LyricsController` and `MainLyricsWorkflow`; the floating service calls the repository when the
selected media changes or lyrics are explicitly reloaded.

The repository receives a `LyricsSettings` value from its caller, so `lyrics/` does not depend on the
settings storage implementation. The normal lookup order is:

1. Read locally imported or previously cached plain lyrics.
2. If local lyrics are absent and online lookup is enabled, call the selected online provider.
3. Save a successful online result locally when auto-save or forced save is enabled.
4. If word-by-word display is enabled, attach locally stored word timing data when available.

Local plain lyrics always take priority unless a request explicitly bypasses local lookup. Online
auto-save does not replace plain lyrics while word-by-word lyrics exist.

Word-by-word lyrics are local-import-first. Importing them creates a generated plain LRC fallback for
normal display modes. Editing word-by-word lyrics regenerates that fallback; removing word-by-word
lyrics removes it when it is still the generated copy. A separately managed plain LRC and
word-by-word LRC cannot be imported for the same song at the same time.

## Native Lyrics Core

`NeteasePlainLyricsProvider` and `MusixmatchPlainLyricsProvider` adapt the Kotlin provider contract
to JNI. `LyricsNativeLibrary` loads `libairlyrics_lyrics.so`, and provider-specific JNI objects pass
song metadata, translation language, and a native lookup ID to the Rust core.

The Rust core searches and scores provider candidates, fetches plain and translated LRC, and returns
a JSON result with stable success and error fields. Kotlin maps this JSON into `LyricsProviderResult`
or typed `LyricsLookupException` values. Cancellation IDs allow newer requests to stop native work
between network stages.

The native result shape is covered by shared Rust and Kotlin contract fixtures under
`lyrics-core/testdata/native-contract/`.

## Lyrics Storage

`LyricsStorage` is the public facade for local lyrics persistence. Its implementation is split into
focused helpers for paths, file I/O, file naming, index access, listing, editing, deletion, plain
lyrics, and word-by-word lyrics.

The default backend is the app-specific files directory. A user-selected directory uses Android's
Storage Access Framework with persistable read/write permission. Managed lyrics live under a
`lyrics/` directory and are described by `lyrics_index.json`.

`SongIdentity` centralizes normalized song matching and stable storage keys. Storage operations are
serialized through the facade so concurrent imports cannot interleave index and file updates.
Word-by-word import coordinates the word timing data, generated plain fallback, and index entry as
one operation; it snapshots existing state and attempts rollback if a later write fails. Editing
word-by-word lyrics regenerates the fallback while holding the same storage lock.

Recent-lyrics listing and editing also go through the facade. The UI never edits the index or files
directly.

## Floating Lyrics

`FloatingLyricsService` is the foreground-service coordination entry point. The class owns shared
runtime state, while focused files split command handling, selected-media observation, lyrics lookup,
pause visibility, notification controls, and protocol keys.

The service does not implement media-session selection itself; it uses `CurrentMediaReader` from the
media layer and filters all updates by `MediaSourceStore`. Its persisted desired visibility is kept
separate from actual window visibility, which is reported to the UI through
`FloatingWindowStateBroadcast`.

`FloatingLyricsWindow` owns `WindowManager` operations: creation, removal, position persistence,
style application, dragging, locking, and touch-through flags. Window-operation failures converge on
a hidden state and broadcast that actual state.

`FloatingLyricsRenderer` owns LRC timelines, playback position estimation, lyrics offset, current and
neighboring-line selection, original/translation display modes, line transitions, and word-by-word
highlighting.

When auto hide/show is enabled, a paused track temporarily removes the window without clearing the
user's desired-visible setting. Selected-media observation continues so playback can restore the
window when it resumes.

## Settings

Feature settings are read and written through dedicated stores under `settings/store/`:

```text
AppSettingsStore             Global app and status-popup behavior
FloatingLyricsStyleStore     Overlay appearance, behavior, position, and preview state
LyricsOffsetStore            Per-song timing offsets
LyricsSettingsStore          Lookup and lyrics-display preferences
QuickFloatingStore           Persisted desired overlay visibility
ThemeSettingsStore           Main UI theme
```

UI pages do not access raw `SharedPreferences` keys or concrete settings, lyrics-storage, or media
data sources. Shared setting value models live under `core/model/`, allowing feature packages to
exchange stable values without depending on each other's stores.

## Localization

Short UI text is stored in Android string resources. Longer help and changelog content is stored
under `assets/` and loaded according to the current language.

`LanguageSettingsStore` persists system, English, or Simplified Chinese mode and applies it to both
activities and services. Other helpers under `i18n/` format media state, settings values, lookup
errors, offsets, and floating-style labels.

## Architecture Safeguards

- `scripts/check_architecture_boundaries.sh` checks forbidden top-level package imports.
- `AppLocalProtocolGuardTest` prevents app-local action strings from escaping protocol owners.
- Native-result contract tests keep Rust JSON and Kotlin parsing aligned.
- Storage atomicity, song identity, latest-result gating, and floating-service lifecycle behavior are
  covered by focused unit, Robolectric, and instrumentation tests.
