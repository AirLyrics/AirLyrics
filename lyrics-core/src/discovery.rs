//! Catalogue-sourced alias discovery.
//!
//! A romanized title such as `Radio Love Song` cannot be related to 《电台情歌》by text
//! similarity, and machine translation is explicitly not allowed to decide song identity. What a
//! music catalogue *can* provide is the missing evidence: the same performer, under their
//! localized name, with a recording of the same length, released under a localized title.
//!
//! Everything here is evidence-gated and best-effort: no duration or no artist means no
//! discovery, and a discovered alias is still only a lookup hint that the confidence policy has
//! to corroborate with the actual lyrics source.
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use reqwest::Client;

use crate::aliases::AliasEntry;
use crate::matching::{text_similarity, MatchMetadata};
use crate::recording::{duration_close, version_conflict};

/// Storefronts that publish CJK titles through the public catalogue API.
const STOREFRONTS: [&str; 3] = ["tw", "hk", "sg"];
/// The catalogue's own artist mapping is only trusted when a few of that artist's tracks agree.
const MIN_ARTIST_TRACKS: usize = 3;
/// Two entries of the *same master* differ by milliseconds across storefronts, while different
/// songs of similar length differ by hundreds. Pairing across storefronts therefore needs an
/// exact-ish window, not a "musically close" one.
const ANCHORED_WINDOW_MS: u64 = 40;
/// Without an anchored reference the player's reported duration may be rounded, so the window
/// has to absorb that rounding — at the cost of needing a unique title to stay honest.
const UNANCHORED_WINDOW_MS: u64 = 600;
/// Storefronts that carry the player's own (usually romanized) metadata.
const SOURCE_STOREFRONTS: [&str; 2] = ["tr", "us"];
const CONFIDENCE: f64 = 0.85;
const CACHE_TTL: Duration = Duration::from_secs(86_400);

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CatalogueArtist {
    pub(crate) name: String,
    /// Storefront artist id; lets discovery read the artist's whole catalogue instead of relying
    /// on a relevance-ranked name search.
    pub(crate) id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CatalogueTrack {
    pub(crate) title: String,
    pub(crate) artist: String,
    pub(crate) album: String,
    pub(crate) duration_ms: Option<u64>,
}

pub(crate) trait CatalogueBackend {
    fn search_artist(
        &self,
        term: &str,
        storefront: &str,
    ) -> Result<Option<CatalogueArtist>, String>;
    fn search_songs(&self, artist: &str, storefront: &str) -> Result<Vec<CatalogueTrack>, String>;
    /// The artist's catalogue by id; preferred over `search_songs` when available because the
    /// name search only returns relevance-ranked rows.
    fn search_artist_songs(
        &self,
        _artist_id: &str,
        _storefront: &str,
    ) -> Result<Vec<CatalogueTrack>, String> {
        Err("artist-id lookup unsupported".to_owned())
    }
    /// The player's own catalogue entry (title + artist), used to learn the recording's exact
    /// running time before pairing storefronts.
    fn search_track(
        &self,
        title: &str,
        artist: &str,
        storefront: &str,
    ) -> Result<Option<CatalogueTrack>, String>;
}

fn has_cjk(text: &str) -> bool {
    text.chars()
        .any(|c| matches!(c as u32, 0x3400..=0x9fff | 0xf900..=0xfaff | 0x20000..=0x2fa1f))
}

/// Pick the alias a storefront's result set justifies, if any.
///
/// The evidence is "same master, localized title": an entry of the same performer whose running
/// time matches the player's recording to the millisecond, with a CJK title. Similar-but-different
/// lengths (ordinary pop songs cluster around the same 4 minutes) are not evidence.
pub(crate) fn pick_alias(
    query: MatchMetadata<'_>,
    tracks: &[CatalogueTrack],
    window_ms: u64,
) -> Option<AliasEntry> {
    // Nothing to localize when the player already shows a CJK title.
    if has_cjk(query.title) {
        return None;
    }
    let duration = query.duration_ms.filter(|value| *value > 0)?;
    if query.artist.trim().is_empty() {
        return None;
    }

    let mut counts: HashMap<&str, usize> = HashMap::new();
    for track in tracks {
        *counts.entry(track.artist.as_str()).or_default() += 1;
    }
    let (artist, count) = counts.into_iter().max_by_key(|(_, count)| *count)?;
    if count < MIN_ARTIST_TRACKS {
        return None;
    }
    // A localized title alone is not evidence: a CJK song by an unrelated performer would
    // otherwise be adopted whenever the running time happens to match. The catalogue's own
    // artist identity for this name must be CJK (that is exactly the romanization→localized
    // mapping), or the player's artist must already be CJK.
    if !has_cjk(artist) && !has_cjk(query.artist) {
        return None;
    }

    let mut titles: Vec<(&CatalogueTrack, u64)> = tracks
        .iter()
        .filter(|track| track.artist == artist)
        .filter(|track| has_cjk(&track.title))
        .filter(|track| duration_close(track.duration_ms, Some(duration), window_ms) == Some(true))
        .filter(|track| {
            !version_conflict(
                &query,
                &MatchMetadata {
                    title: &track.title,
                    artist: &track.artist,
                    album: &track.album,
                    duration_ms: track.duration_ms,
                },
            )
        })
        .filter_map(|track| {
            track
                .duration_ms
                .map(|candidate| (track, candidate.abs_diff(duration)))
        })
        .collect();
    let (picked, _) = *titles.iter().min_by_key(|(_, delta)| *delta)?;
    titles.dedup_by(|(left, _), (right, _)| left.title == right.title);
    // Two *different* titles at the same running time is a coin flip: refuse to localize.
    if titles.iter().any(|(track, _)| track.title != picked.title) {
        return None;
    }

    // The localized title must actually differ from the player's title, otherwise it adds nothing.
    if picked.title.trim().eq_ignore_ascii_case(query.title.trim()) {
        return None;
    }

    Some(AliasEntry {
        original_title: query.title.to_owned(),
        original_artist: query.artist.to_owned(),
        original_artist_aliases: vec![query.artist.to_owned(), picked.artist.clone()],
        alias_title: picked.title.clone(),
        alias_artist: Some(picked.artist.clone()),
        source: "catalogue".to_owned(),
        confidence: CONFIDENCE,
    })
}

/// Ask each storefront, best effort: learn the localized artist spelling, and return the first
/// title alias the evidence supports.
#[derive(Default)]
pub(crate) struct DiscoveryOutcome {
    alias: Option<AliasEntry>,
    cancelled: bool,
    transient_error: bool,
}

impl DiscoveryOutcome {
    fn check_cancelled(&mut self, lookup_id: jni::sys::jlong) -> bool {
        self.cancelled |= crate::check_lookup_cancelled(lookup_id).is_err();
        self.cancelled
    }

    fn record<T>(&mut self, result: Result<T, String>) -> Option<T> {
        match result {
            Ok(value) => Some(value),
            Err(_) => {
                self.transient_error = true;
                None
            }
        }
    }
}

pub(crate) fn discover_and_register(
    query: MatchMetadata<'_>,
    backend: &dyn CatalogueBackend,
    lookup_id: jni::sys::jlong,
) -> DiscoveryOutcome {
    let mut outcome = DiscoveryOutcome::default();
    if query.artist.trim().is_empty() {
        return outcome;
    }
    let mut anchored_duration = None;
    for storefront in SOURCE_STOREFRONTS {
        if outcome.check_cancelled(lookup_id) {
            return outcome;
        }
        let result = backend.search_track(query.title, query.artist, storefront);
        let track = outcome.record(result).flatten();
        if outcome.check_cancelled(lookup_id) {
            return outcome;
        }
        if let Some(track) = track {
            anchored_duration = track.duration_ms;
            break;
        }
    }
    let anchored = MatchMetadata {
        duration_ms: anchored_duration.or(query.duration_ms),
        ..query
    };

    for storefront in STOREFRONTS {
        if outcome.check_cancelled(lookup_id) {
            return outcome;
        }
        let result = backend.search_artist(query.artist, storefront);
        let artist = outcome.record(result).flatten();
        if outcome.check_cancelled(lookup_id) {
            return outcome;
        }
        let Some(artist) = artist else {
            continue;
        };
        if has_cjk(&artist.name) && !has_cjk(query.artist) {
            crate::aliases::register_artist_alias(query.artist, &artist.name);
        }
        let mut tracks = None;
        if let Some(id) = artist.id.as_deref() {
            tracks = outcome.record(backend.search_artist_songs(id, storefront));
            if outcome.check_cancelled(lookup_id) {
                return outcome;
            }
        }
        if tracks.as_ref().is_none_or(|tracks| tracks.is_empty()) {
            tracks = outcome.record(backend.search_songs(&artist.name, storefront));
            if outcome.check_cancelled(lookup_id) {
                return outcome;
            }
        }
        let Some(tracks) = tracks else {
            continue;
        };
        let window = if anchored_duration.is_some() {
            ANCHORED_WINDOW_MS
        } else {
            UNANCHORED_WINDOW_MS
        };
        if let Some(entry) = pick_alias(anchored, &tracks, window) {
            outcome.alias = Some(AliasEntry {
                source: format!("catalogue:itunes-{storefront}"),
                ..entry
            });
            return outcome;
        }
    }
    outcome
}

/// Registers a discovered alias for later lookups in this process, at most once per recording.
pub(crate) fn ensure_registered(
    title: &str,
    artist: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
) {
    if title.trim().is_empty() || artist.trim().is_empty() {
        return;
    }
    // A CJK title needs no romanised-to-localised resolution, and an alias that is already
    // registered makes the whole catalogue walk redundant.
    if crate::qq::has_cjk(title) || crate::aliases::lookup(title, artist).is_some() {
        return;
    }
    // Skip the catalogue walk as soon as the player moves on to another song.
    if crate::check_lookup_cancelled(lookup_id).is_err() {
        return;
    }
    let key = format!(
        "{}|{}|{}",
        title.trim().to_lowercase(),
        artist.trim().to_lowercase(),
        duration_ms.unwrap_or(0) / 1000
    );
    {
        let cache = cache().lock().expect("discovery cache");
        if cache.get(&key).is_some_and(|at| at.elapsed() < CACHE_TTL) {
            return;
        }
    }

    let query = MatchMetadata {
        title,
        artist,
        album: "",
        duration_ms,
    };
    let backend = ItunesBackend::new();
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(_) => return,
    };
    let _ = runtime; // the blocking backend keeps its own request timeouts
                     // The storefront catalogue is authoritative when it can pair the recording, but it keeps the
                     // same English title for many CJK releases. QQ Music's public smart-box knows the Chinese
                     // release title, which is what the NetEase / LRCLIB searches actually need.
    let discovered = discover_with_fallback(query, &backend, lookup_id, &key, || {
        crate::qq::resolve_alias(title, artist, duration_ms)
    });
    if let Some(entry) = discovered {
        crate::aliases::register(entry);
    }
}

/// Shared orchestration so cancellation, fallback and cache policy can be tested offline.
fn discover_with_fallback(
    query: MatchMetadata<'_>,
    backend: &dyn CatalogueBackend,
    lookup_id: jni::sys::jlong,
    key: &str,
    fallback: impl FnOnce() -> Option<AliasEntry>,
) -> Option<AliasEntry> {
    if cache()
        .lock()
        .expect("discovery cache")
        .get(key)
        .is_some_and(|at| at.elapsed() < CACHE_TTL)
    {
        return None;
    }
    let mut outcome = discover_and_register(query, backend, lookup_id);
    if outcome.check_cancelled(lookup_id) {
        return None;
    }
    if outcome.alias.is_none() {
        if outcome.check_cancelled(lookup_id) {
            return None;
        }
        outcome.alias = fallback();
    }
    if outcome.check_cancelled(lookup_id) {
        return None;
    }
    if !outcome.transient_error {
        if let Ok(mut cache) = cache().lock() {
            if cache.len() > 512 {
                cache.clear();
            }
            cache.insert(key.to_owned(), Instant::now());
        }
    }
    outcome.alias
}

fn cache() -> &'static Mutex<HashMap<String, Instant>> {
    static CACHE: OnceLock<Mutex<HashMap<String, Instant>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

struct ItunesBackend {
    client: Client,
}

impl ItunesBackend {
    fn new() -> Self {
        Self {
            client: Client::builder()
                .timeout(Duration::from_secs(5))
                .connect_timeout(Duration::from_secs(3))
                .build()
                .unwrap_or_else(|_| Client::new()),
        }
    }

    fn blocking_json(&self, request: reqwest::RequestBuilder) -> Result<serde_json::Value, String> {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| "runtime_error".to_owned())?;
        runtime.block_on(async {
            let response = request.send().await.map_err(|err| err.to_string())?;
            response
                .error_for_status()
                .map_err(|err| err.to_string())?
                .json::<serde_json::Value>()
                .await
                .map_err(|err| err.to_string())
        })
    }
}

impl CatalogueBackend for ItunesBackend {
    fn search_artist(
        &self,
        term: &str,
        storefront: &str,
    ) -> Result<Option<CatalogueArtist>, String> {
        let value =
            self.blocking_json(self.client.get("https://itunes.apple.com/search").query(&[
                ("term", term),
                ("country", storefront),
                ("media", "music"),
                ("entity", "musicArtist"),
                ("limit", "5"),
            ]))?;
        let found = value["results"].as_array().and_then(|rows| {
            rows.iter().find_map(|row| {
                row["artistName"].as_str().map(|name| CatalogueArtist {
                    name: name.to_owned(),
                    id: row["artistId"]
                        .as_u64()
                        .map(|id| id.to_string())
                        .or_else(|| row["artistId"].as_str().map(str::to_owned)),
                })
            })
        });
        Ok(found)
    }

    fn search_track(
        &self,
        title: &str,
        artist: &str,
        storefront: &str,
    ) -> Result<Option<CatalogueTrack>, String> {
        let value =
            self.blocking_json(self.client.get("https://itunes.apple.com/search").query(&[
                ("term", format!("{title} {artist}")),
                ("country", storefront.to_owned()),
                ("media", "music".to_owned()),
                ("entity", "song".to_owned()),
                ("limit", "10".to_owned()),
            ]))?;
        let rows = value["results"].as_array().cloned().unwrap_or_default();
        let best = rows
            .iter()
            .filter(|row| row["kind"].as_str() == Some("song"))
            .map(|row| CatalogueTrack {
                title: row["trackName"].as_str().unwrap_or_default().to_owned(),
                artist: row["artistName"].as_str().unwrap_or_default().to_owned(),
                album: row["collectionName"]
                    .as_str()
                    .unwrap_or_default()
                    .to_owned(),
                duration_ms: row["trackTimeMillis"].as_u64(),
            })
            .filter(|track| text_similarity(&track.title, title) >= 0.90)
            .filter(|track| track.artist.trim().eq_ignore_ascii_case(artist.trim()))
            .max_by(|left, right| {
                text_similarity(&left.title, title)
                    .partial_cmp(&text_similarity(&right.title, title))
                    .unwrap_or(std::cmp::Ordering::Equal)
            });
        Ok(best)
    }

    fn search_songs(&self, artist: &str, storefront: &str) -> Result<Vec<CatalogueTrack>, String> {
        let value =
            self.blocking_json(self.client.get("https://itunes.apple.com/search").query(&[
                ("term", artist),
                ("country", storefront),
                ("attribute", "artistTerm"),
                ("media", "music"),
                ("entity", "song"),
                ("limit", "200"),
            ]))?;
        Ok(catalogue_tracks(&value))
    }

    fn search_artist_songs(
        &self,
        artist_id: &str,
        storefront: &str,
    ) -> Result<Vec<CatalogueTrack>, String> {
        let value =
            self.blocking_json(self.client.get("https://itunes.apple.com/lookup").query(&[
                ("id", artist_id),
                ("country", storefront),
                ("media", "music"),
                ("entity", "song"),
                ("limit", "200"),
            ]))?;
        Ok(catalogue_tracks(&value))
    }
}

fn catalogue_tracks(value: &serde_json::Value) -> Vec<CatalogueTrack> {
    value["results"]
        .as_array()
        .map(|rows| {
            rows.iter()
                .filter(|row| row["kind"].as_str() == Some("song"))
                .map(|row| CatalogueTrack {
                    title: row["trackName"].as_str().unwrap_or_default().to_owned(),
                    artist: row["artistName"].as_str().unwrap_or_default().to_owned(),
                    album: row["collectionName"]
                        .as_str()
                        .unwrap_or_default()
                        .to_owned(),
                    duration_ms: row["trackTimeMillis"].as_u64(),
                })
                .collect()
        })
        .unwrap_or_default()
}

#[cfg(test)]
mod live_tests {
    /// Live check (network): the public catalogue must resolve the CJK release title.
    /// Run with `cargo test --lib -- --ignored live_discovery`.
    #[test]
    #[ignore]
    fn live_discovery_resolves_shouldnt_be() {
        super::ensure_registered("Shouldn't Be (feat. a-mei)", "Jay Chou", Some(291_407), 0);
        let hit = crate::aliases::lookup("Shouldn't Be (feat. a-mei)", "Jay Chou");
        println!("hit = {hit:?}");
        let hit = hit.expect("alias");
        assert!(hit.title.contains('不'), "got {}", hit.title);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(title: &str, artist: &str, duration_ms: u64) -> CatalogueTrack {
        CatalogueTrack {
            title: title.to_owned(),
            artist: artist.to_owned(),
            album: "Album".to_owned(),
            duration_ms: Some(duration_ms),
        }
    }

    struct FakeCatalogue {
        artist: Option<&'static str>,
        tracks: Vec<CatalogueTrack>,
        own: Option<CatalogueTrack>,
    }

    impl CatalogueBackend for FakeCatalogue {
        fn search_artist(
            &self,
            _term: &str,
            _storefront: &str,
        ) -> Result<Option<CatalogueArtist>, String> {
            Ok(self.artist.map(|name| CatalogueArtist {
                name: name.to_owned(),
                id: None,
            }))
        }

        fn search_songs(
            &self,
            _artist: &str,
            _storefront: &str,
        ) -> Result<Vec<CatalogueTrack>, String> {
            Ok(self.tracks.clone())
        }

        fn search_track(
            &self,
            _title: &str,
            _artist: &str,
            _storefront: &str,
        ) -> Result<Option<CatalogueTrack>, String> {
            Ok(self.own.clone())
        }
    }

    struct WalkBackend {
        calls: std::cell::Cell<usize>,
        cancel_id: Option<i64>,
        fail: bool,
        hit: bool,
    }

    impl CatalogueBackend for WalkBackend {
        fn search_track(
            &self,
            _: &str,
            _: &str,
            _: &str,
        ) -> Result<Option<CatalogueTrack>, String> {
            self.calls.set(self.calls.get() + 1);
            if self.fail {
                Err("HTTP 503 / transport failure".to_owned())
            } else if self.hit {
                Ok(cyndi_catalogue().own)
            } else {
                Ok(None)
            }
        }

        fn search_artist(&self, _: &str, _: &str) -> Result<Option<CatalogueArtist>, String> {
            self.calls.set(self.calls.get() + 1);
            if let Some(id) = self.cancel_id {
                crate::cancel_lookup(id);
            }
            if self.hit {
                Ok(Some(CatalogueArtist {
                    name: "王心凌".to_owned(),
                    id: None,
                }))
            } else {
                Ok(None)
            }
        }

        fn search_songs(&self, _: &str, _: &str) -> Result<Vec<CatalogueTrack>, String> {
            self.calls.set(self.calls.get() + 1);
            Ok(cyndi_catalogue().tracks)
        }
    }

    fn walk_backend() -> WalkBackend {
        WalkBackend {
            calls: std::cell::Cell::new(0),
            cancel_id: None,
            fail: false,
            hit: false,
        }
    }

    #[test]
    fn cancelled_walk_skips_qq_and_cache_and_later_retries() {
        let id = i64::MAX - 840;
        let mut backend = walk_backend();
        backend.cancel_id = Some(id);
        let key = "discovery-test-cancelled";
        let fallback = std::cell::Cell::new(0);
        let run = || {
            discover_with_fallback(cyndi_query(), &backend, id, key, || {
                fallback.set(fallback.get() + 1);
                None
            })
        };
        assert!(run().is_none());
        assert_eq!(backend.calls.get(), 3);
        assert_eq!(fallback.get(), 0);
        assert!(!cache().lock().unwrap().contains_key(key));
        crate::clear_lookup(id);
        assert!(run().is_none());
        assert_eq!(backend.calls.get(), 6);
        crate::clear_lookup(id);
    }

    #[test]
    fn transient_backend_error_is_not_cached() {
        let mut backend = walk_backend();
        backend.fail = true;
        let key = "discovery-test-transient";
        for _ in 0..2 {
            assert!(discover_with_fallback(cyndi_query(), &backend, 0, key, || None).is_none());
            assert!(!cache().lock().unwrap().contains_key(key));
        }
        assert_eq!(backend.calls.get(), 10);
    }

    #[test]
    fn empty_catalogue_uses_qq_and_caches_attempt() {
        let backend = walk_backend();
        let key = "discovery-test-empty";
        let fallback = std::cell::Cell::new(0);
        for _ in 0..2 {
            assert!(discover_with_fallback(cyndi_query(), &backend, 0, key, || {
                fallback.set(fallback.get() + 1);
                None
            })
            .is_none());
        }
        assert_eq!(backend.calls.get(), 5);
        assert_eq!(fallback.get(), 1);
        assert!(cache().lock().unwrap().remove(key).is_some());
    }

    #[test]
    fn normal_catalogue_hit_returns_alias_and_caches_attempt() {
        let mut backend = walk_backend();
        backend.hit = true;
        let key = "discovery-test-hit";
        let alias =
            discover_with_fallback(cyndi_query(), &backend, 0, key, || panic!("unexpected QQ"));
        assert_eq!(alias.unwrap().alias_title, "大眠");
        let calls = backend.calls.get();
        assert!(
            discover_with_fallback(cyndi_query(), &backend, 0, key, || panic!("cached")).is_none()
        );
        assert_eq!(backend.calls.get(), calls);
        assert!(cache().lock().unwrap().remove(key).is_some());
    }

    /// Real storefront data: the US entry "The Big Sleep" and the TW entry 《大眠》 are the same
    /// master (239,671 ms). 王心凌《這就是愛》is only 71 ms away — so only an exact pairing is
    /// allowed to decide.
    fn cyndi_catalogue() -> FakeCatalogue {
        FakeCatalogue {
            artist: Some("王心凌"),
            own: Some(track("The Big Sleep", "Cyndi Wang", 239_671)),
            tracks: vec![
                track("大眠", "王心凌", 239_671),
                track("這就是愛", "王心凌", 239_600),
                track("你在哪裡?", "王心凌", 239_933),
                track("BEAUTY", "王心凌", 239_800),
                track("別說", "王心凌", 241_293),
            ],
        }
    }

    fn cyndi_query() -> MatchMetadata<'static> {
        MatchMetadata {
            title: "The Big Sleep",
            artist: "Cyndi Wang",
            album: "CYNDILOVE2SING",
            duration_ms: Some(239_700),
        }
    }

    #[test]
    fn the_localized_title_of_the_same_master_is_discovered() {
        let alias = discover_and_register(cyndi_query(), &cyndi_catalogue(), 0)
            .alias
            .expect("alias");
        assert_eq!(alias.alias_title, "大眠");
        assert_eq!(alias.alias_artist.as_deref(), Some("王心凌"));
        assert_eq!(alias.original_title, "The Big Sleep");
        assert!(alias.source.starts_with("catalogue:itunes-"));
        assert!(
            alias.confidence < 1.0,
            "an alias is a hint, not a confirmation"
        );
    }

    fn catalogue_with_candidate_100_ms_off() -> FakeCatalogue {
        FakeCatalogue {
            artist: Some("王心凌"),
            own: Some(track("The Big Sleep", "Cyndi Wang", 239_700)),
            tracks: vec![
                track("大眠", "王心凌", 239_800),
                track("這就是愛", "王心凌", 200_000),
                track("別說", "王心凌", 210_000),
            ],
        }
    }

    #[test]
    fn anchor_equal_to_player_duration_rejects_candidate_100_ms_off() {
        let catalogue = catalogue_with_candidate_100_ms_off();
        assert!(discover_and_register(cyndi_query(), &catalogue, 0)
            .alias
            .is_none());
    }

    #[test]
    fn anchor_without_duration_accepts_candidate_100_ms_off() {
        let mut catalogue = catalogue_with_candidate_100_ms_off();
        catalogue.own.as_mut().expect("catalogue entry").duration_ms = None;
        let alias = discover_and_register(cyndi_query(), &catalogue, 0)
            .alias
            .expect("alias");
        assert_eq!(alias.alias_title, "大眠");
    }

    #[test]
    fn anchor_different_from_player_duration_rejects_candidate_100_ms_off() {
        let catalogue = catalogue_with_candidate_100_ms_off();
        let query = MatchMetadata {
            duration_ms: Some(239_800),
            ..cyndi_query()
        };
        assert!(discover_and_register(query, &catalogue, 0).alias.is_none());
    }

    #[test]
    fn two_titles_at_the_same_running_time_yield_no_alias() {
        let catalogue = FakeCatalogue {
            artist: Some("王心凌"),
            own: Some(track("The Big Sleep", "Cyndi Wang", 239_671)),
            tracks: vec![
                track("大眠", "王心凌", 239_671),
                // 20 ms apart: the running time cannot tell these two songs apart.
                track("另一個歌", "王心凌", 239_680),
            ],
        };
        assert!(discover_and_register(cyndi_query(), &catalogue, 0)
            .alias
            .is_none());
    }

    #[test]
    fn a_nearby_but_different_length_is_not_evidence() {
        // 1.6 s off: a different song that happens to be about as long, not another release.
        let catalogue = FakeCatalogue {
            artist: Some("王心凌"),
            own: Some(track("The Big Sleep", "Cyndi Wang", 239_671)),
            tracks: vec![
                track("別說", "王心凌", 241_293),
                track("心電心", "王心凌", 230_000),
            ],
        };
        assert!(discover_and_register(cyndi_query(), &catalogue, 0)
            .alias
            .is_none());
    }

    #[test]
    fn an_unrelated_performer_with_a_cjk_title_is_not_adopted() {
        let catalogue = FakeCatalogue {
            artist: Some("The Highways"),
            own: Some(track("Radio Love Song", "Karen Mok", 242_000)),
            tracks: vec![
                track("电台恋曲", "The Highways", 242_000),
                track("公路之歌", "The Highways", 242_000),
                track("远行", "The Highways", 242_000),
            ],
        };
        let query = MatchMetadata {
            title: "Radio Love Song",
            artist: "Karen Mok",
            album: "",
            duration_ms: Some(242_000),
        };
        assert!(discover_and_register(query, &catalogue, 0).alias.is_none());
    }

    #[test]
    fn without_an_artist_discovery_is_skipped() {
        let no_artist = MatchMetadata {
            artist: "",
            ..cyndi_query()
        };
        assert!(discover_and_register(no_artist, &cyndi_catalogue(), 0)
            .alias
            .is_none());
    }

    #[test]
    fn a_missing_player_duration_is_recovered_from_the_catalogue_entry() {
        // The player did not report a duration, but the catalogue entry for title+artist gives
        // the recording's exact length, which is enough to pair the storefronts.
        let no_duration = MatchMetadata {
            duration_ms: None,
            ..cyndi_query()
        };
        let alias = discover_and_register(no_duration, &cyndi_catalogue(), 0)
            .alias
            .expect("alias");
        assert_eq!(alias.alias_title, "大眠");
    }

    #[test]
    fn a_cjk_query_title_is_left_alone() {
        let already_localized = MatchMetadata {
            title: "大眠",
            artist: "王心凌",
            album: "",
            duration_ms: Some(239_671),
        };
        assert!(
            discover_and_register(already_localized, &cyndi_catalogue(), 0)
                .alias
                .is_none()
        );
    }

    #[test]
    fn a_live_only_match_is_not_adopted_as_the_studio_recording() {
        let catalogue = FakeCatalogue {
            artist: Some("王心凌"),
            own: Some(track("The Big Sleep", "Cyndi Wang", 239_671)),
            tracks: vec![
                track("大眠 (Live)", "王心凌", 239_671),
                track("這就是愛", "王心凌", 200_000),
                track("BEAUTY", "王心凌", 210_000),
            ],
        };
        assert!(discover_and_register(cyndi_query(), &catalogue, 0)
            .alias
            .is_none());
    }

    #[test]
    fn the_localized_artist_spelling_is_learned_even_without_a_title_alias() {
        crate::aliases::clear_artist_aliases();
        let catalogue = FakeCatalogue {
            artist: Some("周杰倫"),
            own: Some(track("Shouldn't Be (feat. a-mei)", "Jay Chou", 291_407)),
            tracks: vec![
                track("不該", "周杰倫", 291_407),
                track("青花瓷", "周杰倫", 239_000),
                track("稻香", "周杰倫", 223_000),
            ],
        };
        let query = MatchMetadata {
            title: "Shouldn't Be (feat. a-mei)",
            artist: "Jay Chou",
            album: "",
            duration_ms: Some(291_000),
        };
        let alias = discover_and_register(query, &catalogue, 0);
        assert_eq!(
            crate::aliases::localized_artist("Jay Chou").as_deref(),
            Some("周杰倫")
        );
        if let Some(alias) = alias.alias {
            assert_eq!(alias.alias_title, "不該");
        }
        crate::aliases::clear_artist_aliases();
    }

    #[test]
    #[ignore = "hits the real iTunes catalogue"]
    fn live_discovery_finds_the_localized_release() {
        let backend = ItunesBackend::new();
        let query = MatchMetadata {
            title: "The Big Sleep",
            artist: "Cyndi Wang",
            album: "",
            duration_ms: Some(239_671),
        };
        let alias = discover_and_register(query, &backend, 0)
            .alias
            .expect("live alias");
        println!("live alias: {alias:?}");
        assert_eq!(alias.alias_title, "大眠");
    }
}
