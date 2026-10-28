# Shared lyrics matching

`lyrics-core/src/matching.rs` owns the matching policy for NetEase, LRCLIB, Musixmatch and Spicy. Change a rule here and test it here; do not add title generators, eligibility thresholds or scoring weights to individual providers.

## Shared path

1. `build_search_queries` produces structured title/artist variants, from the original spelling to broader title-only queries. `build_search_keywords` projects that plan into free-text searches for NetEase and Spotify, prepending title/album/artist when available and limiting the plan to five rounds. The broadest query survives truncation.
2. Providers decode their API candidates into `MatchMetadata`, retaining original response metadata for display and IDs for retrieval.
3. `score_candidate` applies one title threshold and the existing NetEase title/artist/duration/album weights. `is_confident_early_match` determines whether a result can stop broader searches. Related lower-confidence results remain eligible at final fallback; version and duration differences reduce ranking rather than impose provider-specific rejection.
4. Providers deduplicate their native IDs and retrieve usable content in candidate order. Lyrics availability, pure-instrumental responses, parser errors, authentication and rate limits are distinct from metadata matching.

The shared policy handles Unicode compatibility, simplified/traditional Chinese metadata, multiple artists and trailing translated title annotations. Original titles are always queried first. Broader searches may omit a version suffix, but scoring always receives the original metadata and accounts for version differences. Default searches introduce no machine translation or unverified title alias.

A cross-script title with insufficient text similarity requires a strong artist match, known durations within two seconds, and no version disagreement. It remains a final fallback rather than claiming a strong textual match. A localized artist name can gain supporting confidence only when title and duration agree. These are heuristics, not recording-identity guarantees.

## Provider boundaries

| Provider | Query adaptation | Candidate handling / content |
| --- | --- | --- |
| NetEase | Shared free-text plan | Common scoring; early lyric attempts, then remaining ranked candidates |
| LRCLIB | Exact metadata request, then shared structured plan | Both exact and search results use common scoring; timed lyrics arrive with candidates |
| Musixmatch | Native matcher, then shared structured plan | Track results use common scoring; final subtitle matcher follows shared query variants, but exposes no candidate metadata to rescore |
| Spicy | Shared free-text plan sent to Spotify | Common scoring; merge rounds, preserve relevance on ties, try at most four IDs for line/word lyrics |

Transport deadlines, cancellation, rate-limit cooldowns, endpoint parameter names and lyric decoding stay with their providers. Do not force these protocols into a single network loop. Source-specific tie breakers may use native IDs or API relevance only after shared metadata scoring.

## Validation

`cargo test --manifest-path lyrics-core/Cargo.toml` covers shared language/annotation cases and provider fallback behavior, including malformed content, request budgets and cancellation. Spicy adapter tests cover actual Spotify JSON metadata; LRCLIB exact-endpoint tests guard against bypassing the shared scorer.

Use the existing benchmark with a fixed input to measure availability separately from correctness. A NetEase pure-music marker is a valid recognition result; it does not provide timed vocal lyrics. The historical benchmark's lyric-coverage percentage excludes those markers and must not be presented as the metadata-match success rate. Preserve old runs when comparing changes, and manually review newly accepted alternate recordings.

## Explicit candidate search

The current-lyrics settings card opens the dedicated search activity. Its retained
session owns query edits, source selection, independent source results, preview, and cancellation.
The initial source selection and order follow automatic search settings; changes in the manual
source sheet apply only to the current window. Rotation retains the session;
a playback change does not silently change the target. Each submitted source owns a separate pager page
and scroll position; ranking stays within that source. Draft source choices take effect on the next search.
Preview navigation is keyed by the candidate, independent of whether fetching succeeds. Successful empty responses are selectable blank previews and can replace existing lyrics, with the same
replacement confirmation as other choices. Their persisted manual selection prevents automatic or word-track
fallbacks from restoring old lyrics. No instrumental label is inferred or displayed; provider text is preserved.
Missing Musixmatch subtitles are also selectable empty content: the subtitle endpoint reports missing lyrics,
not a missing song. Other NotFound responses remain unavailable; network, access and parse failures cannot be applied.

The search query may be edited without changing the original song used for storage. `LyricsSearchCoordinator`
retires earlier requests when a manual search starts and checks ownership at commit, so automatic results
cannot overwrite an active manual choice. Cancellation also invalidates queued automatic UI callbacks.

`manual.rs` exposes `ManualLyricsNative.requestJson` with `search` and `fetch` operations. Providers
return native IDs and metadata, ranked with the shared relaxed scorer without automatic acceptance gates.
Search returns up to 50 candidates per source and deduplicates within each source. Provider-native result
limits can be smaller. Fetch uses exactly the selected ID: Musixmatch does not use commontrack or matcher
fallback, LRCLIB verifies the returned ID, and Spicy validates the response recording ID. Search and preview
never persist song lyrics; Spicy preview payloads retain their original access/expiry policy.

Apple uses `AppleLyricsNative.requestManualJson` with credentials passed separately from the request.
Manual catalogue search requests up to 25 candidates without the automatic confidence or duration gates.
Each candidate carries its storefront; preview fetches that exact catalogue ID in that storefront and
checks the response ID. Missing lyrics remain unavailable. The existing Apple parser preserves word timing
and translations, and the normal manual selection transaction saves them together for later playback.
An Apple account is required for both search and preview; credentials are read again for each operation.

Applying obtains a manual commit lease, rechecks protected file revisions under the storage lock, and
uses `ManualLyricsSelectionStore` to save and publish a target-specific change. Imported, edited and legacy
local text require explicit replacement confirmation. Edited plain files now carry `manual_edit` provenance.
Independent word files survive replacement, but the selected version is loaded without attaching old word
tracks. Failed or cancelled requests do not change the selection. Previously edited files without edit
provenance cannot be identified retroactively.

Spicy preview data remains in memory until the chosen result is applied. The selection store records a
reference to the saved plain file or Spicy document rather than copying its lyrics. Playback readers honor
that reference until the content is deleted, expired or replaced; Spicy's existing retention period still applies.

The optional device diagnostic `ManualSearchLiveInstrumentedTest` runs only with instrumentation argument
`liveLyricsSearch=true`. It reports per-source candidate/preview availability and compares failed
Musixmatch/Spicy requests with the existing automatic interface; it does not save or apply lyrics.
Remote availability is reported separately from the offline correctness tests.
