# Spicy Lyrics integration

Spicy is an optional, independent lyrics source. It is not enabled automatically for existing installations. Enable the source in the horizontally scrolling source row in Lyrics settings, then enable word-by-word lyrics to prefer syllable timing. The row keeps a single line and accommodates additional providers without adding columns or rows. A `Syllable` response supplies both timed segments and the ordinary line display; a `Line` response supplies ordinary synced lyrics.

Manual imports retain priority. With word timing enabled and Spicy selected, an ordinary downloaded result can be displayed while Spicy is checked. A successful word result replaces the entire payload, including its translations and credits. Failure keeps the existing ordinary result or falls back to the other selected sources. Valid private cache entries also work offline. Existing providers keep their own storage behavior. Lookup selection uses provider capabilities (`supportsWordTiming`, `cached`, `continueOnFailure`), rather than source-name branches. Cache ownership and access metadata carry editing and retention rules into the UI.

## Build configuration

A distributed Android app needs a **publishable** `sl_pk_` key with client access enabled in the Spicy dashboard. Supply `airlyrics.spicy.clientKey` in an untracked `local.properties` or user Gradle properties file, or set `AIRLYRICS_SPICY_CLIENT_KEY` in the build environment. Never commit a real key. The publishable key is included in the APK; it is not a secret. Rate limits and availability apply to the application key shared by its users.

The build rejects secret `sl_sk_` keys. Without a publishable key, the app builds normally and shows Spicy as unavailable. The local `.spicy-lyrics-key` validation file is not read by the build and must remain excluded from Git. There is no key entry form for end users and no AirLyrics backend.

The Rust resolver uses the [shared matching policy](lyrics-matching.md), also used by NetEase, LRCLIB and Musixmatch. Spotify and NetEase share the same bounded free-text query plan: title, album and artist when available, then progressively broader title variants with and without artist. Title-only input remains supported. Candidate metadata is preserved for display. Search results from all rounds are merged and deduplicated before selecting up to four track IDs; search has a 12-second budget so lyric retrieval retains time within the overall lookup deadline. A failed optional search preserves earlier candidates, while authentication and rate-limit refusals stop lookup.

Spicy has no separate title threshold, artist gate, duration cutoff or scoring weights. It uses the common NetEase-style related-candidate fallback, with shared safeguards for cross-script title inference. Same-title alternate recordings can remain eligible at a lower rank; this is matching coverage, not proof of recording identity. Equal scores retain Spotify result order.

Selected IDs are sent to `https://api.spicylyrics.org/v1/lyrics/{id}`. Spotify's anonymous web-player endpoint is unofficial and may change. An unavailable sync or a line-only response can fall through to another ID, while a usable line response remains available if no word response succeeds. Rate limits stop further requests. Debug logs distinguish a Spotify matching miss from a lyrics availability miss without logging response bodies or keys. Network timeouts, cancellation, short negative caching and rate-limit cooldowns limit repeat work. Keys and response bodies are not logged.

## Storage and editing

Spicy responses live only in `noBackupFilesDir/spicy-lyrics`. Each document contains its unmodified raw response, acquisition time, source attribution, and optional edits. The original response is retained so that provider marks remain intact. Text-only edits preserve unchanged millisecond timing; the existing enhanced-LRC editor defines newly edited timings. Spicy editor drafts are kept in memory, not the ordinary persistent draft store.

All versions of a response expire 30 days after acquisition. Reading or editing does not restart that deadline. Reads, editor sessions and active lyric rendering enforce expiry; startup and a periodic Android job remove expired disk documents. Android can delay background jobs while the app is stopped, so physical deletion is best effort until it runs again; expired content is never returned by cache reads. Clock rollback also invalidates the cache.

Spicy content is not written to the selected lyrics folder, exported, shared as a file, or backed up. Restrictions follow its origin even when its `source` names Apple Music or Spotify. Existing imports and other providers are unaffected. Users can delete an entry and search again to replace their edits.

Attribution appears below displayed lyrics and in the editor. It names the actual response source and links community uploaders and makers where supplied. The overlay's contributor links open when the window is unlocked.

## Validation

Use synthetic responses for committed tests; do not commit real lyrics, response marks or credentials. Unit coverage includes source parsing, invalid timestamps, line fallback, cache expiry, edits and repository priority/cancellation. Build the Android native library rather than relying solely on `airlyrics.skipRustBuild` before shipping. A configured publishable key and device playback are still needed for release smoke tests, including overlay readability and link interaction.

References: [API terms](https://developers.spicylyrics.org/terms), [attribution](https://developers.spicylyrics.org/attribution), [key types](https://developers.spicylyrics.org/keys), [lyrics endpoint](https://developers.spicylyrics.org/reference/get.lyrics).
