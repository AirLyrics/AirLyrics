# Shared lyrics matching

`lyrics-core/src/matching.rs` owns the matching policy for NetEase, LRCLIB, Musixmatch and Spicy. Change a rule here and test it here; do not add title generators, eligibility thresholds or scoring weights to individual providers.

## Shared path

1. `build_search_queries` produces structured title/artist variants, from the original spelling to broader title-only queries. `build_search_keywords` projects that plan into free-text searches for NetEase and Spotify, prepending title/album/artist when available and limiting the plan to five rounds. The broadest query survives truncation.
2. Providers decode their API candidates into `MatchMetadata`, retaining original response metadata for display and IDs for retrieval.
3. `score_candidate` applies one title threshold and the existing NetEase title/artist/duration/album weights. `is_confident_early_match` determines whether a result can stop broader searches. Related lower-confidence results remain eligible at final fallback; version and duration differences reduce ranking rather than impose provider-specific rejection.
4. Providers deduplicate their native IDs and retrieve usable content in candidate order. Lyrics availability, pure-instrumental responses, parser errors, authentication and rate limits are distinct from metadata matching.

The shared policy handles Unicode compatibility, simplified/traditional Chinese metadata, multiple artists and trailing translated title annotations. Original titles are always queried first. Broader searches may omit a version suffix, but scoring always receives the original metadata and accounts for version differences. Default searches introduce no machine translation or unverified title alias. Manual international-title search is an explicit exception described below.

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

## Manual international-title search

The button below online search sets a per-request `LyricsSearchMode.International`. Repository resolves metadata once before calling the enabled providers in their configured order; automatic and ordinary searches still use the original title. Confirmation state persists the mode, with older state defaulting to `Original`.

`international.rs` searches Apple iTunes US and, for non-Latin input, a regional storefront (kana: JP, Hangul: KR, otherwise Han: CN). It ranks songs through `matching::score_metadata_alias`, reusing shared scoring without the title acceptance threshold. The best regional song is looked up by Apple ID in US; if that yields no song, the best US search candidate is used. Ties retain API order. A US title may remain untranslated. No candidate confidence gate is added.

The resolver has a 12-second cancellable budget, five-second HTTP timeouts, and a bounded 24-hour in-memory success cache. Empty results and errors fall back to the original request. Once an alias exists, providers search and score against that metadata with the original duration; a lyric miss does not trigger a second original-title attempt. Provider matching rules remain shared and unchanged.

Original song identity travels separately from lookup metadata. Local saves and Spicy document metadata bind to the original song. Spicy stores a search fingerprint on the canonical saved document, distinguishes negative lookups and Spotify ID caches by search mode, and never treats an original search result as a cached international attempt. The canonical document keeps existing playback, editor and deletion behavior; the most recently saved successful search replaces it. Existing JNI entry points remain available to the benchmark. Debug logs record alias selection and fallback reasons, without credentials or lyric bodies.

Manual search ownership is separate from the title mode. `LyricsSearchCoordinator` groups requests by normalized original title/artist, retires earlier requests when a manual search starts, and checks ownership at durable commit. Cancellation also invalidates already queued automatic UI callbacks. Automatic requests cannot supersede an active manual request. Manual completion reloads the saved choice on success and the previous content on failure/cancellation.

Spicy fetching returns an in-memory pending response; only the repository's chosen result may persist it. `ManualLyricsSelectionStore` commits the content and a private reference to the selected plain file or Spicy document under the storage lock, with snapshots for rollback on save failure. Selection survives playback changes and process restart. Playback and storage readers honor this reference before seeking automatic word upgrades; deleted, expired or replaced imports invalidate it. The reference contains no copied lyric text and does not extend Spicy retention. Ordinary and international manual searches can replace each other's successful result.

Search feedback follows the active request and distinguishes original-title and international-title attempts. Falling back from international metadata resolution updates the displayed mode to original-title search.
