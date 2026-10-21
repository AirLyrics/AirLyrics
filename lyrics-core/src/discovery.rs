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
pub(crate) fn discover_and_register(
    query: MatchMetadata<'_>,
    backend: &dyn CatalogueBackend,
) -> Option<AliasEntry> {
    if query.artist.trim().is_empty() {
        return None;
    }
    // Learn the recording's exact running time from the player's own storefront: the app's
    // reported duration can be rounded, and pairing storefronts is millisecond-sensitive.
    let anchored = SOURCE_STOREFRONTS
        .iter()
        .find_map(|storefront| {
            backend
                .search_track(query.title, query.artist, storefront)
                .ok()
                .flatten()
        })
        .and_then(|track| track.duration_ms)
        .map(|duration| MatchMetadata {
            duration_ms: Some(duration),
            ..query
        })
        .unwrap_or(query);

    for storefront in STOREFRONTS {
        let Ok(Some(artist)) = backend.search_artist(query.artist, storefront) else {
            continue;
        };
        // The catalogue mapping romanized → localized performer is itself useful: it steers the
        // lyrics search away from cover acts even when no localized title can be confirmed.
        if has_cjk(&artist.name) && !has_cjk(query.artist) {
            crate::aliases::register_artist_alias(query.artist, &artist.name);
        }
        let tracks = artist
            .id
            .as_deref()
            .and_then(|id| backend.search_artist_songs(id, storefront).ok())
            .filter(|tracks| !tracks.is_empty())
            .or_else(|| backend.search_songs(&artist.name, storefront).ok());
        let Some(tracks) = tracks else {
            continue;
        };
        let window = if anchored.duration_ms == query.duration_ms {
            UNANCHORED_WINDOW_MS
        } else {
            ANCHORED_WINDOW_MS
        };
        if let Some(entry) = pick_alias(anchored, &tracks, window) {
            return Some(AliasEntry {
                source: format!("catalogue:itunes-{storefront}"),
                ..entry
            });
        }
    }
    None
}

/// Registers a discovered alias for later lookups in this process, at most once per recording.
pub(crate) fn ensure_registered(title: &str, artist: &str, duration_ms: Option<u64>) {
    if title.trim().is_empty() || artist.trim().is_empty() {
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
    let discovered = discover_and_register(query, &backend)
        .or_else(|| crate::qq::resolve_alias(title, artist, duration_ms));

    if let Ok(mut cache) = cache().lock() {
        if cache.len() > 512 {
            cache.clear();
        }
        cache.insert(key, Instant::now());
    }
    if let Some(entry) = discovered {
        crate::aliases::register(entry);
    }
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
        super::ensure_registered("Shouldn't Be (feat. a-mei)", "Jay Chou", Some(291_407));
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
        let alias = discover_and_register(cyndi_query(), &cyndi_catalogue()).expect("alias");
        assert_eq!(alias.alias_title, "大眠");
        assert_eq!(alias.alias_artist.as_deref(), Some("王心凌"));
        assert_eq!(alias.original_title, "The Big Sleep");
        assert!(alias.source.starts_with("catalogue:itunes-"));
        assert!(
            alias.confidence < 1.0,
            "an alias is a hint, not a confirmation"
        );
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
        assert!(discover_and_register(cyndi_query(), &catalogue).is_none());
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
        assert!(discover_and_register(cyndi_query(), &catalogue).is_none());
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
        assert!(discover_and_register(query, &catalogue).is_none());
    }

    #[test]
    fn without_an_artist_discovery_is_skipped() {
        let no_artist = MatchMetadata {
            artist: "",
            ..cyndi_query()
        };
        assert!(discover_and_register(no_artist, &cyndi_catalogue()).is_none());
    }

    #[test]
    fn a_missing_player_duration_is_recovered_from_the_catalogue_entry() {
        // The player did not report a duration, but the catalogue entry for title+artist gives
        // the recording's exact length, which is enough to pair the storefronts.
        let no_duration = MatchMetadata {
            duration_ms: None,
            ..cyndi_query()
        };
        let alias = discover_and_register(no_duration, &cyndi_catalogue()).expect("alias");
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
        assert!(discover_and_register(already_localized, &cyndi_catalogue()).is_none());
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
        assert!(discover_and_register(cyndi_query(), &catalogue).is_none());
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
        let alias = discover_and_register(query, &catalogue);
        assert_eq!(
            crate::aliases::localized_artist("Jay Chou").as_deref(),
            Some("周杰倫")
        );
        if let Some(alias) = alias {
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
        let alias = discover_and_register(query, &backend).expect("live alias");
        println!("live alias: {alias:?}");
        assert_eq!(alias.alias_title, "大眠");
    }
}
