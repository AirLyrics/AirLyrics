//! QQ Music as a *title resolver* for romanised / English player metadata.
//!
//! The public QQ Music endpoints that need no signature are the smart-box search and the
//! single-song detail call; the lyric endpoint is signed. That is enough to answer the question
//! the other sources cannot: given "Shouldn't Be / Jay Chou", what does the Chinese release call
//! this recording? The answer is registered as an alias, and the existing NetEase / LRCLIB
//! providers then search with the localised title.
//!
//! The alias is only a search hint: acceptance still runs through the confidence policy, so a
//! wrong hint costs one request rather than a wrong lyric.

use std::time::Duration;

use reqwest::Client;

use crate::aliases::AliasEntry;

const SMARTBOX_URL: &str = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg";
const SONG_DETAIL_URL: &str = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg";
const USER_AGENT: &str =
    "AirLyrics/1.2.7 (+https://github.com/AirLyrics/AirLyrics; lyrics title resolver)";
const MAX_SMARTBOX_CANDIDATES: usize = 4;
const DURATION_WINDOW_MS: u64 = 2_000;
const ALIAS_CONFIDENCE: f64 = 0.8;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct QqCandidate {
    pub(crate) title: String,
    pub(crate) artists: String,
    pub(crate) duration_ms: u64,
}

/// Resolves a romanised title to its CJK release title. Returns `None` when the title already
/// carries CJK text (nothing to resolve) or when no candidate is close enough in running time.
pub(crate) fn resolve_alias(
    title: &str,
    artist: &str,
    duration_ms: Option<u64>,
) -> Option<AliasEntry> {
    let title = title.trim();
    let artist = artist.trim();
    if title.is_empty() || artist.is_empty() || has_cjk(title) {
        return None;
    }

    let candidates = fetch_candidates(title)?;
    let picked = pick_candidate(duration_ms?, &candidates)?;
    // Duration alone cannot tell same-titled recordings by different acts apart, and a wrong
    // alias would be re-confirmed by the confidence pass (it scores against the alias itself).
    // Only register the mapping when the candidate's artist credibly matches the player's.
    if !artist_matches(artist, &picked.artists) {
        return None;
    }
    Some(AliasEntry {
        original_title: title.to_owned(),
        original_artist: artist.to_owned(),
        original_artist_aliases: vec![artist.to_owned()],
        alias_title: picked.title.clone(),
        alias_artist: Some(picked.artists.clone()),
        source: "qq:smartbox".to_owned(),
        confidence: ALIAS_CONFIDENCE,
    })
}

/// Accepts the candidate only when its artist credibly matches the player's artist, so a
/// same-titled recording by another act cannot seed a wrong alias. The two sides are usually
/// written in different scripts, so this is a conservative normalised containment check rather
/// than an equality test; unresolvable pairs return `false` and no alias is registered.
fn artist_matches(query_artist: &str, candidate_artists: &str) -> bool {
    fn normalise(value: &str) -> String {
        value
            .chars()
            .filter(|character| character.is_alphanumeric())
            .flat_map(|character| character.to_lowercase())
            .collect()
    }

    let query = normalise(query_artist);
    let candidate = normalise(candidate_artists);
    if query.is_empty() || candidate.is_empty() {
        return false;
    }
    candidate.contains(&query) || query.contains(&candidate)
}

/// Chooses the candidate whose running time is closest to the player's, within the window.
fn pick_candidate(duration_ms: u64, candidates: &[QqCandidate]) -> Option<&QqCandidate> {
    candidates
        .iter()
        .filter_map(|candidate| {
            let delta = candidate.duration_ms.abs_diff(duration_ms);
            (delta <= DURATION_WINDOW_MS).then_some((delta, candidate))
        })
        .min_by_key(|(delta, _)| *delta)
        .map(|(_, candidate)| candidate)
}

fn fetch_candidates(title: &str) -> Option<Vec<QqCandidate>> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .ok()?;
    runtime.block_on(fetch_candidates_async(title)).ok()
}

async fn fetch_candidates_async(title: &str) -> Result<Vec<QqCandidate>, String> {
    let client = Client::builder()
        .timeout(Duration::from_secs(6))
        .connect_timeout(Duration::from_secs(3))
        .user_agent(USER_AGENT)
        .build()
        .map_err(|error| error.to_string())?;

    let response = client
        .get(SMARTBOX_URL)
        .query(&[
            ("key", title),
            ("format", "json"),
            ("g_tk", "5381"),
            ("inCharset", "utf-8"),
            ("outCharset", "utf-8"),
            ("platform", "yqq"),
        ])
        .header("Referer", "https://y.qq.com/")
        .send()
        .await
        .map_err(|error| error.to_string())?;
    let body: serde_json::Value = response.json().await.map_err(|error| error.to_string())?;

    let mids: Vec<String> = body
        .pointer("/data/song/itemlist")
        .and_then(|value| value.as_array())
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.get("mid").and_then(|mid| mid.as_str()))
                .take(MAX_SMARTBOX_CANDIDATES)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default();

    let mut candidates = Vec::new();
    for mid in mids {
        if let Ok(Some(candidate)) = fetch_detail(&client, &mid).await {
            candidates.push(candidate);
        }
    }
    Ok(candidates)
}

async fn fetch_detail(client: &Client, mid: &str) -> Result<Option<QqCandidate>, String> {
    let response = client
        .get(SONG_DETAIL_URL)
        .query(&[("songmid", mid), ("format", "json"), ("platform", "yqq")])
        .header("Referer", "https://y.qq.com/")
        .send()
        .await
        .map_err(|error| error.to_string())?;
    let body: serde_json::Value = response.json().await.map_err(|error| error.to_string())?;

    let Some(track) = body.pointer("/data/0") else {
        return Ok(None);
    };
    let Some(title) = track.get("title").and_then(|value| value.as_str()) else {
        return Ok(None);
    };
    let Some(interval) = track.get("interval").and_then(|value| value.as_u64()) else {
        return Ok(None);
    };
    if interval == 0 {
        return Ok(None);
    }
    let artists = track
        .get("singer")
        .and_then(|value| value.as_array())
        .map(|singers| {
            singers
                .iter()
                .filter_map(|singer| singer.get("name").and_then(|name| name.as_str()))
                .collect::<Vec<_>>()
                .join("/")
        })
        .unwrap_or_default();

    Ok(Some(QqCandidate {
        title: title.to_owned(),
        artists,
        duration_ms: interval * 1_000,
    }))
}

pub(crate) fn has_cjk(value: &str) -> bool {
    value.chars().any(|c| {
        matches!(
            c as u32,
            0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF | 0x3040..=0x30FF | 0xAC00..=0xD7AF
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(title: &str, artists: &str, duration_ms: u64) -> QqCandidate {
        QqCandidate {
            title: title.to_owned(),
            artists: artists.to_owned(),
            duration_ms,
        }
    }

    #[test]
    fn picks_the_candidate_inside_the_duration_window() {
        let candidates = [
            candidate("不該 (Live)", "周杰伦/张惠妹", 301_000),
            candidate("不該 (with aMEI)", "周杰伦/张惠妹", 291_000),
            candidate("不該用情", "莫叫姐姐", 216_000),
        ];
        let picked = pick_candidate(291_407, &candidates).expect("candidate");
        assert_eq!(picked.title, "不該 (with aMEI)");
    }

    #[test]
    fn rejects_everything_outside_the_window() {
        let candidates = [
            candidate("不該 (Live)", "周杰伦/张惠妹", 301_000),
            candidate("不該用情", "莫叫姐姐", 216_000),
        ];
        assert!(pick_candidate(291_407, &candidates).is_none());
    }

    #[test]
    #[ignore]
    fn live_debug_candidates() {
        let candidates = fetch_candidates("Shouldn't Be").expect("candidates");
        println!("count = {}", candidates.len());
        for candidate in &candidates {
            println!("  {candidate:?}");
        }
    }

    /// Live check against the public endpoints (network); run with
    /// `cargo test --lib -- --ignored live_smartbox`.
    #[test]
    #[ignore]
    fn live_smartbox_resolves_shouldnt_be() {
        let alias =
            resolve_alias("Shouldn't Be (feat. a-mei)", "Jay Chou", Some(291_407)).expect("alias");
        println!("alias = {alias:?}");
        assert!(
            alias.alias_title.contains('不'),
            "got {}",
            alias.alias_title
        );
        assert!(alias.alias_artist.unwrap_or_default().contains("周杰伦"));
    }

    #[test]
    fn a_cjk_title_needs_no_resolution() {
        assert!(has_cjk("不該"));
        assert!(!has_cjk("Shouldn't Be (feat. a-mei)"));
    }

    #[test]
    fn artist_gate_accepts_matching_scripts() {
        assert!(artist_matches("Jay Chou", "周杰伦/Jay Chou"));
        assert!(artist_matches("周杰伦", "周杰伦"));
        assert!(artist_matches("Taylor Swift", "taylor  swift"));
    }

    #[test]
    fn artist_gate_rejects_unresolvable_scripts() {
        // Same title, same duration, but the acts cannot be tied together: refuse.
        assert!(!artist_matches("Jay Chou", "莫叫姐姐"));
        assert!(!artist_matches("", "周杰伦"));
        assert!(!artist_matches("Jay Chou", ""));
    }
}
