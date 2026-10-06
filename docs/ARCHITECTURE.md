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
  -> MainViewModel (viewModelScope)
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
displayscope/     Display-scope policy, capability checks, and foreground-app observation
feedback/         Feature-independent transient-feedback contracts and Toast host
media/            Media-session observation, current-media models, broadcasts, and source persistence
lyrics/           Lookup, cancellation, providers, parsing, importing, formatting, and storage
floating/         Foreground service, service commands, overlay control, and lyrics rendering
settings/         Feature-specific settings persistence and status-popup policy
ui/               Screens, Snackbar feedback, components, navigation, themes, UI models, and async UI helpers
i18n/             Language selection, localized assets, and common value formatters
i18n/lyrics/      Lyric-specific error and storage-value text adapters
```

Transient feedback is surface-owned: the main Activity uses an anchored Snackbar host, while the
floating Service uses a Toast host. Their composition roots supply the same status-popup policy.

## Package Boundaries

The allowed dependencies below are generated from `scripts/architecture/policy.json`, the single
source of truth for source checks, JVM dependency checks, protocol ownership, and this table.
They describe permitted dependencies, not a claim that every edge is currently used:

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

Arrows mean “may depend on”. The most specific registered group owns a symbol: allowing `i18n`
does not also allow `i18n.lyrics`. A symbol allowance includes nested types and members, but not
similarly named siblings. Generated `R`/`BuildConfig` and external platform/library types are omitted.

The important boundary rules are:

- `core/` and `feedback/` do not depend on other project packages; `design/` depends only on stable
  `core/` models.
- `lyrics/` and `media/` remain independent of each other and do not depend on `app/`, `floating/`,
  `settings/`, or `ui/`.
- `ui/` does not import concrete media, lyrics, settings, or floating implementations. Feedback
  visibility and palette policy are injected by `app/`; pages receive
  UI-facing data and actions through interfaces under `ui/model/`.
- `floating/` may coordinate media, lyrics and settings and use the feature-independent feedback
  host, but it does not depend on the main app shell or UI pages.
- `app/` is the composition layer that is allowed to connect all feature packages.

`displayscope` has no project dependencies and is consumed by `app` and `floating`. Common `i18n`
cannot depend on lyrics; `i18n.lyrics` can access only the listed lyric error types and storage facade.
The facade allowance supports its nested data types and source constants; it is a class-level
allowance, not a guarantee that every method call on that facade is appropriate. Review still needs
to keep these adapters limited to text conversion. `lyrics` cannot depend on either i18n group.
UI can use stable value models and feedback contracts, but not preference stores or concrete Toast
hosts. Interfaces must not expose forbidden implementation types through their signatures.

Run the complete check with JDK 17 and Python 3.10+:

```bash
./scripts/check_architecture_boundaries.sh
# Equivalent Gradle entry; also included in :app:check
./gradlew :app:checkArchitecture -Pairlyrics.skipRustBuild=true
# Fast preflight without compilation (does not replace the complete check)
./scripts/check_architecture_boundaries.sh --source-only
```

The fast check tokenizes Kotlin/Java imports and fully qualified code references, including aliases
and Kotlin string-template expressions. Comments and literal text do not create dependency edges.
The complete check builds the debug project classes and uses JDK `jdeps` to check resolved class
references, including inferred types, generic signatures, and inheritance. It reads the producing
Gradle task's outputs, never an arbitrary cached jar. Tests and third-party classes are excluded;
missing project classes, unregistered packages, and empty analysis inputs fail the check. Compiler
synthetic classes remain included. Source errors report file/line; JVM errors report the referring
class. Both actual dependency cycles and cycles introduced by policy allowances are rejected.

The two checks are complementary: `jdeps` does not report every annotation attribute or source-only
type alias, so source checks remain mandatory. Reflection strings, native code, and external-library
implementation details are outside this checker’s scope. The check enforces static boundaries; it
does not replace review of responsibilities or data flow.

When changing boundaries, register the group and its explicit consumers, narrow allowances to
subpackages or symbols where useful, and give each allowance a reason. New top-level packages are
rejected until registered. Main/debug Java and Kotlin roots are registered; new production source
roots (for example a release-only source set) fail until added. `test` and `androidTest` are explicitly
excluded because tests may assemble multiple layers. There is no violation baseline or source-level suppression. Run checker
fixtures and the full check, then synchronize both language versions of the table:

```bash
python3 -B -m unittest discover -s scripts/architecture -p 'test_*.py'
./scripts/check_architecture_boundaries.sh --update-docs
```

Normal checks are read-only and fail if either generated documentation block is stale. The complete
Gradle check runs the checker fixtures too; CI invokes it before JVM unit tests.

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

LanguageChangedBroadcast
Refreshes running services after an AppCompat language change on Android 12L and earlier.
```

The broadcasts are package-scoped and registered as not exported. The foreground notification is
created by `FloatingServiceNotification`; its actions create `PendingIntent`s through
`FloatingServiceCommand` and return to `FloatingLyricsService.handleCommand`.

`AppLocalProtocolGuardTest` reads the protocol-owner files from the same architecture policy.

## App Shell and UI Boundary

`MainActivity` creates `MainGraph` and obtains `MainViewModel` through `viewModels`.

`MainViewModel` owns `MainScreenState` and uses `SavedStateHandle` for navigation, search, and pending
lyrics imports. Lyrics import, deletion, online lookup, directory validation, and font import run in
`viewModelScope`. Permission requests, document pickers, and dialogs are sent to `MainGraph` as
`MainUiEffect` values.

`MainGraph` assembles controllers, activity-result launchers, broadcast receivers, the UI host, and
the renderer. It updates the UI from ViewModel state and effects.

```text
MainUiActions -> MainViewModel / Controller
MainViewModel -> MainScreenState -> MainGraph -> Renderer
MainViewModel -> MainUiEffect    -> MainGraph -> Android UI
```

Application-layer packages:

```text
controller/       Media, lyrics, and floating-window operations
contracts/        Application-layer dependency interfaces
host/             Page data and action adapters
lifecycle/        Activity results and receiver registration
platform/         Android permissions and navigation
render/           Main view construction and updates
state/            Pending-operation models
viewmodel/        Main-screen state and one-off effects
workflow/         Dialog and document-permission flows
```

Screens under `ui/pages/` read page data and invoke `MainUiActions` through `MainUiHost`.
`app/host/` connects the screens to controllers and stores.

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

`LyricsRepository` is the unified lookup entry point. `MainViewModel` dispatches main-screen lyrics
operations, `LyricsController` performs storage and lookup, and `MainLyricsWorkflow` handles import
choices and overwrite confirmation. The floating service calls the repository when the selected
media changes or lyrics are manually reloaded.

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

On Android 10+, the optional Display scope filter reconstructs visible activities from
`UsageEvents`: resumed and paused activities remain visible until stopped. `DisplayScopePolicy`
allows the window when any selected package is visible. Missing usage access fails closed, while the
service preserves desired visibility so a matching app can restore the window automatically.

## Settings

Feature settings are read and written through dedicated stores under `settings/store/`:

```text
AppSettingsStore             Global app and status-popup behavior
FloatingLyricsStyleStore     Overlay appearance, behavior, position, and preview state
DisplayScopeStore            Optional app allowlist and enabled state
LyricsOffsetStore            Per-song timing offsets
LyricsSettingsStore          Lookup and lyrics-display preferences
QuickFloatingStore           Persisted desired overlay visibility
ThemeSettingsStore           Main UI theme
```

`AppNightMode` applies the stored light/dark override through AppCompat DayNight before an Activity
is created. With no override, the app follows the system. The effective DayNight configuration and
the branded `AirLyricsTheme` palette therefore use the same light/dark choice.

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

- `scripts/check_architecture_boundaries.sh` checks source/JVM dependencies, registered groups, cycles, and policy/documentation consistency.
- `AppLocalProtocolGuardTest` prevents app-local action strings from escaping protocol owners.
- Native-result contract tests keep Rust JSON and Kotlin parsing aligned.
- Storage atomicity, song identity, latest-result gating, and floating-service lifecycle behavior are
  covered by focused unit, Robolectric, and instrumentation tests.
