//! Spotify identity resolution and Spicy developer API. Never log credentials or response bodies.
use crate::matching::{
    score_candidate, simplify_title, text_similarity, version_difference_penalty, MatchMetadata,
};
use openssl::{hash::MessageDigest, pkey::PKey, sign::Signer};
use regex::Regex;
use reqwest::{Client, Response};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const SEARCH_HASH: &str = "3c9d3f60dac5dea3876b6db3f534192b1c1d90032c4233c1bbaba526db41eb31";
const UA: &str = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
const MAX_SEARCH_ROUNDS: usize = 4;
const MAX_TRACK_CANDIDATES: usize = 4;
static TOKEN: OnceLock<Mutex<Option<(String, u64)>>> = OnceLock::new();
static TRACKS: OnceLock<Mutex<HashMap<String, (Vec<Value>, u64)>>> = OnceLock::new();
static COOLDOWN: OnceLock<Mutex<u64>> = OnceLock::new();
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
fn err(kind: &str) -> String {
    kind.to_owned()
}

pub(crate) fn fetch(
    title: &str,
    artist: &str,
    album: &str,
    duration: u64,
    key: &str,
    lookup: i64,
) -> Value {
    let result = (|| -> Result<Value, String> {
        let _guard = crate::begin_lookup_cancellation(lookup)?;
        if !key.starts_with("sl_pk_") {
            return Err(err("NeedCredential"));
        }
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| err("NativeError"))?;
        runtime.block_on(async {
            crate::await_with_cancellation(
                async {
                    tokio::time::timeout(
                        Duration::from_secs(25),
                        resolve(title, artist, album, duration, key),
                    )
                    .await
                    .map_err(|_| err("NetworkError"))?
                },
                lookup,
            )
            .await?
        })
    })();
    match result {
        Ok(v) => v,
        Err(e) => json!({"ok":false,"error_type":e,"error":"Spicy Lyrics lookup failed"}),
    }
}

async fn checked(response: Result<Response, reqwest::Error>) -> Result<Response, String> {
    let r = response.map_err(|_| err("NetworkError"))?;
    match r.status().as_u16() {
        200 => Ok(r),
        401 | 403 => Err(err("NeedCredential")),
        404 => Err(err("NotFound")),
        429 => {
            let seconds = r
                .headers()
                .get("retry-after")
                .and_then(|h| h.to_str().ok())
                .and_then(|s| s.parse::<u64>().ok())
                .unwrap_or(60)
                .clamp(1, 86400);
            if let Ok(mut until) = COOLDOWN.get_or_init(|| Mutex::new(0)).lock() {
                *until = now().saturating_add(seconds);
            }
            Err(err("RateLimited"))
        }
        _ => Err(err("NetworkError")),
    }
}
async fn json_response(r: Result<Response, reqwest::Error>) -> Result<Value, String> {
    let response = checked(r).await?;
    let text = bounded_text(response, 2_000_000).await?;
    serde_json::from_str(&text).map_err(|_| err("ParseError"))
}
async fn bounded_text(mut response: Response, max: usize) -> Result<String, String> {
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| err("NetworkError"))? {
        if bytes.len() + chunk.len() > max {
            return Err(err("ParseError"));
        }
        bytes.extend_from_slice(&chunk);
    }
    String::from_utf8(bytes).map_err(|_| err("ParseError"))
}
fn totp(secret: &str, seconds: u64) -> Result<String, String> {
    let key: String = secret
        .encode_utf16()
        .enumerate()
        .map(|(i, c)| (c ^ ((i % 33 + 9) as u16)).to_string())
        .collect();
    let pkey = PKey::hmac(key.as_bytes()).map_err(|_| err("NativeError"))?;
    let mut signer = Signer::new(MessageDigest::sha1(), &pkey).map_err(|_| err("NativeError"))?;
    signer
        .update(&(seconds / 30).to_be_bytes())
        .map_err(|_| err("NativeError"))?;
    let digest = signer.sign_to_vec().map_err(|_| err("NativeError"))?;
    let offset = (digest[digest.len() - 1] & 15) as usize;
    let code = u32::from_be_bytes(
        digest[offset..offset + 4]
            .try_into()
            .map_err(|_| err("NativeError"))?,
    ) & 0x7fffffff;
    Ok(format!("{:06}", code % 1_000_000))
}
async fn token(client: &Client) -> Result<String, String> {
    if let Some((value, expiry)) = TOKEN
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| err("NativeError"))?
        .as_ref()
    {
        if *expiry > now() + 60 {
            return Ok(value.clone());
        }
    }
    let clock = json_response(
        client
            .get("https://open.spotify.com/api/server-time")
            .send()
            .await,
    )
    .await?;
    let seconds = clock["serverTime"]
        .as_u64()
        .ok_or_else(|| err("ParseError"))?;
    let html = bounded_text(
        checked(client.get("https://open.spotify.com/").send().await).await?,
        2_000_000,
    )
    .await?;
    let re = Regex::new(r#""(https://[^" ]+/(?:mobile-)?web-player\.[0-9a-f]+\.js)""#).unwrap();
    let url = re
        .captures(&html)
        .and_then(|c| c.get(1))
        .ok_or_else(|| err("ParseError"))?
        .as_str();
    let parsed = reqwest::Url::parse(url).map_err(|_| err("ParseError"))?;
    if parsed.scheme() != "https"
        || !parsed.host_str().is_some_and(|s| {
            s == "scdn.co" || s.ends_with(".scdn.co") || s.ends_with(".spotifycdn.com")
        })
    {
        return Err(err("ParseError"));
    }
    let js = bounded_text(checked(client.get(url).send().await).await?, 8_000_000).await?;
    let re =
        Regex::new(r#"\{\s*secret\s*:\s*["']([^"']+)["']\s*,\s*version\s*:\s*(\d+)\s*\}"#).unwrap();
    let (version, secret) = re
        .captures_iter(&js)
        .filter_map(|c| Some((c[2].parse::<u32>().ok()?, c[1].to_owned())))
        .max_by_key(|x| x.0)
        .ok_or_else(|| err("ParseError"))?;
    let data = json_response(
        client
            .get("https://open.spotify.com/api/token")
            .query(&[
                ("reason", "init".to_owned()),
                ("productType", "web-player".to_owned()),
                ("totp", totp(&secret, seconds)?),
                ("totpVer", version.to_string()),
                ("ts", seconds.to_string()),
            ])
            .header("Referer", "https://open.spotify.com/")
            .header("Origin", "https://open.spotify.com")
            .send()
            .await,
    )
    .await?;
    let value = data["accessToken"]
        .as_str()
        .ok_or_else(|| err("ParseError"))?
        .to_owned();
    let expiry = data["accessTokenExpirationTimestampMs"]
        .as_u64()
        .ok_or_else(|| err("ParseError"))?
        / 1000;
    *TOKEN
        .get()
        .unwrap()
        .lock()
        .map_err(|_| err("NativeError"))? = Some((value.clone(), expiry));
    Ok(value)
}

// Normalize only metadata used for matching; never transform lyric text or the stored API response.
fn match_text(text: &str) -> String {
    if text.is_ascii() {
        return text.to_owned();
    }
    static CHARACTERS: OnceLock<HashMap<char, char>> = OnceLock::new();
    let characters = CHARACTERS.get_or_init(|| {
        include_str!("../data/opencc/TSCharacters.txt")
            .lines()
            .filter(|line| !line.starts_with('#'))
            .filter_map(|line| {
                let (source, targets) = line.split_once('\t')?;
                let mut source = source.chars();
                let from = source.next()?;
                if source.next().is_some() {
                    return None;
                }
                let to = targets.split_whitespace().next()?.chars().next()?;
                Some((from, to))
            })
            .collect()
    });
    text.chars()
        .map(|c| characters.get(&c).copied().unwrap_or(c))
        .collect()
}

fn has_cjk(value: &str) -> bool {
    value
        .chars()
        .any(|c| matches!(c, '\u{3040}'..='\u{30ff}' | '\u{3400}'..='\u{9fff}'))
}

fn different_scripts(left: &str, right: &str) -> bool {
    has_cjk(left) != has_cjk(right)
        && left.chars().any(char::is_alphabetic)
        && right.chars().any(char::is_alphabetic)
}

// Queries contain only title text. Keep the original first and never remove a real
// recording-version marker merely because it appears in parentheses.
fn title_queries(title: &str) -> Vec<String> {
    let original = title.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut variants = vec![original.clone(), simplify_title(&original)];
    for (open, close) in [('(', ')'), ('（', '）'), ('[', ']'), ('【', '】')] {
        if let Some(without_close) = original.strip_suffix(close) {
            if let Some((base, annotation)) = split_annotation(without_close, open) {
                let has_kana = |s: &str| s.chars().any(|c| matches!(c, '\u{3040}'..='\u{30ff}'));
                if (different_scripts(base, annotation) || has_kana(base) != has_kana(annotation))
                    && version_difference_penalty(&original, base) == 0.0
                {
                    variants.push(base.to_owned());
                }
            }
        }
    }
    let normalized: Vec<_> = variants.iter().map(|s| match_text(s)).collect();
    variants.extend(normalized);
    let mut seen = std::collections::HashSet::new();
    variants
        .into_iter()
        .filter(|s| !s.is_empty() && seen.insert(s.clone()))
        .take(MAX_SEARCH_ROUNDS)
        .collect()
}

fn split_annotation(value: &str, open: char) -> Option<(&str, &str)> {
    let (base, annotation) = value.rsplit_once(open)?;
    let base = base.trim();
    let annotation = annotation.trim();
    (!base.is_empty() && !annotation.is_empty()).then_some((base, annotation))
}

fn candidate(track: &Value, query: MatchMetadata<'_>) -> Option<(f64, Value)> {
    let id = track["uri"].as_str()?.strip_prefix("spotify:track:")?;
    if id.len() != 22 || !id.bytes().all(|b| b.is_ascii_alphanumeric()) {
        return None;
    }
    let title = track["name"].as_str()?;
    let artists = track["artists"]["items"].as_array()?;
    let artist = artists
        .iter()
        .filter_map(|a| a["profile"]["name"].as_str())
        .collect::<Vec<_>>()
        .join(", ");
    let album = track["albumOfTrack"]["name"].as_str().unwrap_or("");
    let duration = track["duration"]["totalMilliseconds"].as_u64()?;
    let normalized_title = match_text(title);
    let normalized_query_title = match_text(query.title);
    let normalized_artist = match_text(&artist);
    let normalized_query_artist = match_text(query.artist);
    if version_difference_penalty(&normalized_query_title, &normalized_title) > 0.0 {
        return None;
    }
    let artist_similarity = (!query.artist.trim().is_empty()).then(|| {
        // Multiple artists are supporting metadata, not a single indivisible name.
        query
            .artist
            .split([';', ',', '、', '，'])
            .flat_map(|name| {
                artists
                    .iter()
                    .filter_map(|a| a["profile"]["name"].as_str())
                    .map(move |other| text_similarity(&match_text(name), &match_text(other)))
            })
            .fold(
                text_similarity(&normalized_query_artist, &normalized_artist),
                f64::max,
            )
    });
    let duration_delta = query
        .duration_ms
        .filter(|ms| *ms > 0)
        .map(|ms| ms.abs_diff(duration));
    let strong_artist = artist_similarity.is_some_and(|s| s >= 0.85);
    let title_score = title_queries(query.title)
        .iter()
        .flat_map(|left| {
            title_queries(title)
                .into_iter()
                .map(move |right| text_similarity(&match_text(left), &match_text(&right)))
        })
        .fold(0.0, f64::max);
    // Spotify can return an English/localized title for a Japanese title query.
    // Search relevance alone is insufficient to accept an unrelated recording.
    let localized_title = title_score < 0.80
        && different_scripts(&normalized_query_title, &normalized_title)
        && strong_artist
        && duration_delta.is_some_and(|ms| ms <= 2000);
    if title_score < 0.80 && !localized_title {
        return None;
    }
    if artist_similarity.is_some_and(|s| s < 0.5)
        && !(different_scripts(&normalized_query_artist, &normalized_artist)
            && title_score >= 0.95
            && duration_delta.is_some_and(|ms| ms <= 2000))
    {
        return None;
    }
    // Strong title and artist evidence tolerates small encoding/edition offsets.
    let duration_limit = if title_score >= 0.95 && strong_artist {
        10_000
    } else {
        5_000
    };
    if duration_delta
        .is_some_and(|delta| delta > duration_limit.max(query.duration_ms.unwrap_or(0) / 50))
    {
        return None;
    }
    let score = score_candidate(
        MatchMetadata {
            title: &normalized_title,
            artist: "",
            ..query
        },
        MatchMetadata {
            title: &normalized_title,
            artist: "",
            album,
            duration_ms: Some(duration),
        },
    )?;
    let title_confidence = if localized_title { 0.80 } else { title_score };
    let rank =
        score.total * 0.15 + title_confidence * 0.65 + artist_similarity.unwrap_or(0.5) * 0.20;
    Some((
        rank,
        json!({"id":id,"title":title,"artist":artist,"album":album,"duration_ms":duration}),
    ))
}

fn ranked_tracks(mut matches: Vec<(f64, Value)>) -> Vec<Value> {
    // Stable sorting preserves Spotify relevance when metadata does not distinguish
    // candidates, including title-only requests with no artist or duration.
    matches.sort_by(|a, b| b.0.total_cmp(&a.0));
    let mut ids = std::collections::HashSet::new();
    matches
        .into_iter()
        .map(|(_, track)| track)
        .filter(|track| ids.insert(track["id"].as_str().unwrap_or_default().to_owned()))
        .take(MAX_TRACK_CANDIDATES)
        .collect()
}

async fn search(client: &Client, access: &mut String, term: &str) -> Result<Value, String> {
    let body = json!({"operationName":"searchDesktop","variables":{"searchTerm":term,"offset":0,"limit":20,"numberOfTopResults":5,
        "includeAudiobooks":false,"includeArtistHasConcertsField":false,"includePreReleases":true,"includeLocalConcertsField":false},
        "extensions":{"persistedQuery":{"version":1,"sha256Hash":SEARCH_HASH}}});
    for attempt in 0..2 {
        let result = json_response(
            client
                .post("https://api-partner.spotify.com/pathfinder/v2/query")
                .bearer_auth(&*access)
                .header("app-platform", "WebPlayer")
                .header("spotify-app-version", "1.2.84.359.g17db506e")
                .json(&body)
                .send()
                .await,
        )
        .await;
        if result.as_ref().err().is_some_and(|e| e == "NeedCredential") && attempt == 0 {
            *TOKEN
                .get()
                .unwrap()
                .lock()
                .map_err(|_| err("NativeError"))? = None;
            *access = token(client).await?;
            continue;
        }
        let data = result?;
        if data
            .get("errors")
            .is_some_and(|v| !v.is_null() && v.as_array().is_none_or(|a| !a.is_empty()))
        {
            return Err(err("ParseError"));
        }
        return Ok(data);
    }
    Err(err("NeedCredential"))
}

async fn discover(client: &Client, query: MatchMetadata<'_>) -> Result<Vec<Value>, String> {
    let terms = title_queries(query.title);
    if terms.is_empty() {
        return Ok(Vec::new());
    }
    let mut access = token(client).await?;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(12);
    let mut matches = Vec::new();
    for term in terms {
        let result = tokio::time::timeout_at(deadline, search(client, &mut access, &term))
            .await
            .unwrap_or_else(|_| Err(err("NetworkError")));
        let data = match result {
            Ok(data) => data,
            // Keep useful earlier candidates when an optional broader search fails.
            // Authentication and rate-limit refusals must still stop this lookup.
            Err(e)
                if !matches.is_empty() && matches!(e.as_str(), "NetworkError" | "ParseError") =>
            {
                break
            }
            Err(e) => return Err(e),
        };
        let items = data["data"]["searchV2"]["tracksV2"]["items"]
            .as_array()
            .ok_or_else(|| err("ParseError"))?;
        matches.extend(
            items
                .iter()
                .filter_map(|item| candidate(&item["item"]["data"], query)),
        );
    }
    Ok(ranked_tracks(matches))
}

fn timed_response(response: &Value) -> bool {
    response["Status"].as_u64() == Some(200)
        && matches!(response["Body"]["Type"].as_str(), Some("Line" | "Syllable"))
        && response["Body"]["Content"]
            .as_array()
            .is_some_and(|lines| !lines.is_empty())
}

async fn resolve(
    title: &str,
    artist: &str,
    album: &str,
    duration: u64,
    key: &str,
) -> Result<Value, String> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(24);
    if *COOLDOWN
        .get_or_init(|| Mutex::new(0))
        .lock()
        .map_err(|_| err("NativeError"))?
        > now()
    {
        return Err(err("RateLimited"));
    }
    let client = Client::builder()
        .user_agent(UA)
        .timeout(Duration::from_secs(10))
        .connect_timeout(Duration::from_secs(5))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| err("NativeError"))?;
    let cache_key = json!([title, artist, album, duration]).to_string();
    let cached = TRACKS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .map_err(|_| err("NativeError"))?
        .get(&cache_key)
        .filter(|(_, at)| now().saturating_sub(*at) < 86400)
        .map(|(v, _)| v.clone());
    let tracks = if let Some(tracks) = cached {
        tracks
    } else {
        let tracks = discover(
            &client,
            MatchMetadata {
                title,
                artist,
                album,
                duration_ms: (duration > 0).then_some(duration),
            },
        )
        .await?;
        if !tracks.is_empty() {
            let mut cache = TRACKS
                .get()
                .unwrap()
                .lock()
                .map_err(|_| err("NativeError"))?;
            if cache.len() >= 256 {
                cache.clear();
            }
            cache.insert(cache_key, (tracks.clone(), now()));
        }
        tracks
    };
    if tracks.is_empty() {
        return Ok(json!({"ok":false,"error_type":"NotFound","stage":"spotify_match"}));
    }
    fetch_tracks(&tracks, deadline, |id| {
        let client = &client;
        async move {
            json_response(
                client
                    .get(format!("https://api.spicylyrics.org/v1/lyrics/{id}"))
                    .bearer_auth(key)
                    .send()
                    .await,
            )
            .await
        }
    })
    .await
}

async fn fetch_tracks<F, Fut>(
    tracks: &[Value],
    deadline: tokio::time::Instant,
    mut fetch: F,
) -> Result<Value, String>
where
    F: FnMut(String) -> Fut,
    Fut: std::future::Future<Output = Result<Value, String>>,
{
    let mut line_fallback = None;
    for (index, track) in tracks.iter().take(MAX_TRACK_CANDIDATES).enumerate() {
        let id = track["id"].as_str().ok_or_else(|| err("ParseError"))?;
        let response = match tokio::time::timeout_at(deadline, fetch(id.to_owned()))
            .await
            .unwrap_or_else(|_| Err(err("NetworkError")))
        {
            Ok(response) => response,
            Err(e) if e == "NotFound" => continue,
            Err(e) => return line_fallback.map(Ok).unwrap_or(Err(e)),
        };
        if !timed_response(&response) {
            continue;
        }
        let syllable = response["Body"]["Type"] == "Syllable";
        let result = json!({"ok":true,"track":track,"response":response,"lookup":{"candidates":tracks.len(),"attempted":index+1}});
        if syllable {
            return Ok(result);
        }
        if line_fallback.is_none() {
            line_fallback = Some(result);
        }
    }
    Ok(line_fallback.unwrap_or_else(|| json!({"ok":false,"error_type":"NotFound","stage":"lyrics_fetch","candidates":tracks.len()})))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn totp_decimal_key_vector() {
        // Independent HMAC/TOTP vector for this player's decimal key transform.
        assert_eq!(totp("test", 1234567890).unwrap(), "841667");
    }
    fn localized_track() -> Value {
        json!({"uri":"spotify:track:5l94Up12BJSjhH7Mhr2ji4","name":"紅豆",
            "artists":{"items":[{"uri":"spotify:artist:3df3XLKuqTQ6iOSmi0K3Wp","profile":{"name":"Faye Wong"}}]},
            "albumOfTrack":{"name":"唱遊"},"duration":{"totalMilliseconds":256026}})
    }
    #[test]
    fn localized_artist_needs_strong_title_and_duration() {
        let track = localized_track();
        let query = MatchMetadata {
            title: "红豆",
            artist: "王菲",
            album: "",
            duration_ms: Some(256000),
        };
        assert!(candidate(&track, query).is_some());
        assert!(candidate(
            &track,
            MatchMetadata {
                duration_ms: None,
                ..query
            }
        )
        .is_none());
        assert!(candidate(
            &track,
            MatchMetadata {
                title: "红豆 - Live",
                ..query
            }
        )
        .is_none());
        assert!(candidate(
            &track,
            MatchMetadata {
                duration_ms: Some(220000),
                ..query
            }
        )
        .is_none());
        assert!(candidate(
            &track,
            MatchMetadata {
                artist: "",
                duration_ms: None,
                ..query
            }
        )
        .is_some());
    }
    #[test]
    fn simplified_title_matches_without_changing_display_metadata() {
        let track = localized_track();
        let (_, selected) = candidate(
            &track,
            MatchMetadata {
                title: "红豆",
                artist: "Faye Wong",
                album: "",
                duration_ms: None,
            },
        )
        .unwrap();
        assert_eq!(selected["title"], "紅豆");
        assert_eq!(match_text("周杰倫"), match_text("周杰伦"));
        assert_eq!(match_text("夜に駆ける"), "夜に駆ける");
    }
    fn track(title: &str, artist: &str, duration: u64) -> Value {
        json!({"uri":"spotify:track:0X5X6D3kZyDEG8Im1LQaVf", "name":title,
            "artists":{"items":[{"profile":{"name":artist}}]},
            "albumOfTrack":{"name":""}, "duration":{"totalMilliseconds":duration}})
    }

    #[test]
    fn queries_use_title_and_remove_translated_annotation() {
        assert_eq!(title_queries("Faded"), vec!["Faded"]);
        assert!(title_queries("未だ、青い (湛蓝依旧)").contains(&"未だ、青い".to_owned()));
        assert!(title_queries("Song (Live)").contains(&"Song (Live)".to_owned()));
        assert!(title_queries("  ").is_empty());
        assert!(title_queries("紅豆（Red Bean）").len() <= MAX_SEARCH_ROUNDS);
    }

    #[test]
    fn title_only_lookup_works_without_artist_album_or_duration() {
        let query = MatchMetadata {
            title: "未だ、青い (湛蓝依旧)",
            artist: "",
            album: "",
            duration_ms: None,
        };
        assert!(candidate(&track("未だ、青い", "Singer", 221189), query).is_some());
        assert!(candidate(&track("Completely Different", "Singer", 221189), query).is_none());
        assert!(candidate(&track("未だ、青い - Live", "Singer", 221189), query).is_none());
    }

    #[test]
    fn localized_title_requires_artist_and_close_duration() {
        let query = MatchMetadata {
            title: "マイライフ",
            artist: "CY8ER",
            album: "",
            duration_ms: Some(198619),
        };
        assert!(candidate(&track("MyLife", "CY8ER", 198666), query).is_some());
        assert!(candidate(&track("MyLife", "Other", 198666), query).is_none());
        assert!(candidate(&track("MyLife", "CY8ER", 230000), query).is_none());
        assert!(candidate(&track("MyLife - Live", "CY8ER", 198666), query).is_none());
        assert!(candidate(
            &track("MyLife", "CY8ER", 198666),
            MatchMetadata {
                artist: "",
                ..query
            }
        )
        .is_none());
        assert!(candidate(
            &track("MyLife", "CY8ER", 198666),
            MatchMetadata {
                duration_ms: None,
                ..query
            }
        )
        .is_none());
    }

    #[test]
    fn multiple_artists_and_small_duration_offsets_are_supported() {
        let query = MatchMetadata {
            title: "守護霊",
            artist: "Yunomi; nicamoq",
            album: "",
            duration_ms: Some(198139),
        };
        assert!(candidate(&track("守護霊", "Yunomi", 204773), query).is_some());
        assert!(candidate(&track("守護霊", "Yunomi", 230000), query).is_none());
        let query = MatchMetadata {
            title: "サンダーソニア",
            artist: "PIKASONIC; iMeiden; 小花依ある",
            album: "",
            duration_ms: Some(171638),
        };
        assert!(candidate(&track("Sandersonia", "PIKASONIC", 171679), query).is_some());
    }

    #[test]
    fn merges_rounds_deduplicates_ids_and_preserves_relevance_on_ties() {
        let selected = ranked_tracks(vec![
            (0.8, json!({"id":"first"})),
            (0.9, json!({"id":"second"})),
            (0.9, json!({"id":"second"})),
            (0.8, json!({"id":"third"})),
            (0.7, json!({"id":"fourth"})),
            (0.6, json!({"id":"fifth"})),
        ]);
        assert_eq!(
            selected,
            vec![
                json!({"id":"second"}),
                json!({"id":"first"}),
                json!({"id":"third"}),
                json!({"id":"fourth"})
            ]
        );
    }
    #[test]
    fn static_or_empty_responses_do_not_stop_variant_fallback() {
        assert!(!timed_response(
            &json!({"Status":200,"Body":{"Type":"Static","Content":[{}]}})
        ));
        assert!(!timed_response(
            &json!({"Status":200,"Body":{"Type":"Syllable","Content":[]}})
        ));
        assert!(timed_response(
            &json!({"Status":200,"Body":{"Type":"Line","Content":[{}]}})
        ));
    }
    #[tokio::test]
    async fn tries_next_id_after_miss_and_stops_at_words() {
        let tracks = vec![
            json!({"id":"first"}),
            json!({"id":"second"}),
            json!({"id":"unused"}),
        ];
        let mut calls = Vec::new();
        let result = fetch_tracks(
            &tracks,
            tokio::time::Instant::now() + Duration::from_secs(1),
            |id| {
                calls.push(id.clone());
                std::future::ready(if id == "first" {
                    Err(err("NotFound"))
                } else {
                    Ok(json!({"Status":200,"Body":{"Type":"Syllable","Content":[{}]}}))
                })
            },
        )
        .await
        .unwrap();
        assert_eq!(calls, vec!["first", "second"]);
        assert_eq!(result["track"]["id"], "second");
        assert_eq!(result["lookup"]["attempted"], 2);
    }
    #[tokio::test]
    async fn line_fallback_survives_variant_timeout() {
        let tracks = vec![json!({"id":"line"}), json!({"id":"slow"})];
        let result = fetch_tracks(
            &tracks,
            tokio::time::Instant::now() + Duration::from_millis(10),
            |id| async move {
                if id == "slow" {
                    std::future::pending::<()>().await;
                }
                Ok(json!({"Status":200,"Body":{"Type":"Line","Content":[{}]}}))
            },
        )
        .await
        .unwrap();
        assert_eq!(result["track"]["id"], "line");
    }
    #[tokio::test]
    async fn bounds_requests_and_distinguishes_lyric_misses() {
        let tracks: Vec<_> = (0..6).map(|i| json!({"id":i.to_string()})).collect();
        let mut calls = 0;
        let result = fetch_tracks(
            &tracks,
            tokio::time::Instant::now() + Duration::from_secs(1),
            |_| {
                calls += 1;
                std::future::ready(Err(err("NotFound")))
            },
        )
        .await
        .unwrap();
        assert_eq!(calls, 4);
        assert_eq!(result["stage"], "lyrics_fetch");
    }
    #[tokio::test]
    async fn rate_limit_does_not_try_more_variants() {
        let tracks = vec![json!({"id":"first"}), json!({"id":"second"})];
        let mut calls = 0;
        let result = fetch_tracks(
            &tracks,
            tokio::time::Instant::now() + Duration::from_secs(1),
            |_| {
                calls += 1;
                std::future::ready(Err(err("RateLimited")))
            },
        )
        .await;
        assert_eq!(calls, 1);
        assert_eq!(result.unwrap_err(), "RateLimited");
    }
    #[test]
    fn reject_cover_and_wrong_duration() {
        let track = json!({"uri":"spotify:track:7gHs73wELdeycvS48JfIos","name":"Faded","artists":{"items":[{"profile":{"name":"Alan Walker"}}]},"albumOfTrack":{"name":"Faded"},"duration":{"totalMilliseconds":212626}});
        let query = MatchMetadata {
            title: "Faded",
            artist: "Alan Walker",
            album: "",
            duration_ms: Some(212000),
        };
        assert!(candidate(&track, query).is_some());
        assert!(candidate(
            &track,
            MatchMetadata {
                artist: "Other Artist",
                ..query
            }
        )
        .is_none());
        assert!(candidate(
            &track,
            MatchMetadata {
                duration_ms: Some(250000),
                ..query
            }
        )
        .is_none());
        assert!(candidate(
            &track,
            MatchMetadata {
                title: "Faded - Live",
                ..query
            }
        )
        .is_none());
    }
}
