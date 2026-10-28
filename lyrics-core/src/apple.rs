//! Apple Music catalogue search and syllable (word-timed) lyrics.
//!
//! Uses the web-player API: a developer token plus the listener's media user token. Never log either.
use crate::matching::MatchMetadata;
use regex::Regex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::OnceLock;
use std::time::Duration;

const API: &str = "https://amp-api.music.apple.com";
const UA: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
/// Apple's word timings are exact; anything further apart is a different recording.
const DURATION_TOLERANCE_MS: u64 = 2_500;

#[derive(Debug, Clone)]
struct Word {
    text: String,
    start_ms: u64,
    end_ms: u64,
}

#[derive(Debug, Clone)]
pub(crate) struct Line {
    source_index: usize,
    begin: Option<String>,
    end: Option<String>,
    raw_text: String,
    key: Option<String>,
    start_ms: u64,
    end_ms: u64,
    text: String,
    words: Vec<Word>,
}

#[derive(Debug, Default)]
pub(crate) struct ParsedTtml {
    lines: Vec<Line>,
    translations: HashMap<String, String>,
    translation_misses: HashMap<String, &'static str>,
}

fn time_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r#"begin="([^"]+)"[^>]*end="([^"]+)""#).expect("time regex"))
}

fn paragraph_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r#"(?s)<p\b([^>]*)>(.*?)</p>"#).expect("paragraph regex"))
}

fn translation_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r#"(?s)<translation\b([^>]*)>(.*?)</translation>"#).expect("translation regex")
    })
}

fn text_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r#"(?s)<text\b[^>]*for="([^"]+)"[^>]*>(.*?)</text>"#).expect("text regex")
    })
}

fn key_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r#"itunes:key="([^"]+)""#).expect("key regex"))
}

/// Parses `12.345`, `1:02.345` and `1:02:03.456`.
pub(crate) fn parse_time(value: &str) -> Option<u64> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    let mut millis: f64 = 0.0;
    for part in trimmed.split(':') {
        let piece: f64 = part.parse().ok()?;
        millis = millis * 60.0 + piece;
    }
    if !millis.is_finite() || millis < 0.0 {
        return None;
    }
    Some((millis * 1000.0).round() as u64)
}

fn decode_entities(value: &str) -> String {
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}

fn strip_tags(value: &str) -> String {
    let stripped = strip_tags_raw(value);
    decode_entities(stripped.trim())
}

/// Removes tags but keeps the interior spacing, so text split across several spans still
/// renders with the whitespace between its words.
fn strip_tags_raw(value: &str) -> String {
    static TAGS: OnceLock<Regex> = OnceLock::new();
    let regex = TAGS.get_or_init(|| Regex::new(r"(?s)<[^>]+>").expect("tag regex"));
    decode_entities(&regex.replace_all(value, ""))
}

/// Parses Apple's lyric TTML into timed lines, word spans and per-line translations.
pub(crate) fn parse_ttml(ttml: &str) -> ParsedTtml {
    let mut parsed = ParsedTtml::default();
    for (source_index, paragraph) in paragraph_regex().captures_iter(ttml).enumerate() {
        let attributes = paragraph.get(1).map(|m| m.as_str()).unwrap_or_default();
        let body = paragraph.get(2).map(|m| m.as_str()).unwrap_or_default();
        let key = key_regex()
            .captures(attributes)
            .and_then(|capture| capture.get(1).map(|m| m.as_str().to_string()));

        let mut words = Vec::new();
        collect_timed_spans(body, &mut words);

        let (start_ms, end_ms) = match time_regex().captures(attributes) {
            Some(capture) => (
                capture.get(1).and_then(|m| parse_time(m.as_str())),
                capture.get(2).and_then(|m| parse_time(m.as_str())),
            ),
            None => (None, None),
        };
        let line_start = start_ms.or_else(|| words.first().map(|w| w.start_ms));
        let Some(line_start) = line_start else {
            continue;
        };
        let line_end = end_ms
            .or_else(|| words.last().map(|w| w.end_ms))
            .unwrap_or(line_start);
        // Derive the line text from the body instead of the timed spans: inter-span
        // whitespace and separator text nodes would be lost in the join.
        let body_text = strip_tags(body);
        let text = if body_text.is_empty() {
            words.iter().map(|w| w.text.as_str()).collect::<String>()
        } else {
            body_text
        };
        if text.trim().is_empty() {
            continue;
        }
        let timing = time_regex().captures(attributes);
        parsed.lines.push(Line {
            source_index,
            begin: timing
                .as_ref()
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().to_owned()),
            end: timing
                .as_ref()
                .and_then(|c| c.get(2))
                .map(|m| m.as_str().to_owned()),
            raw_text: strip_tags(body),
            key,
            start_ms: line_start,
            end_ms: line_end.max(line_start),
            text,
            words,
        });
    }

    let mut blocks: Vec<(String, String)> = Vec::new();
    for translation in translation_regex().captures_iter(ttml) {
        let attributes = translation.get(1).map(|m| m.as_str()).unwrap_or_default();
        let body = translation.get(2).map(|m| m.as_str()).unwrap_or_default();
        blocks.push((attributes.to_string(), body.to_string()));
    }
    // Only the simplified-Chinese translation satisfies the feature requirement. When the
    // document does not tag any language, a single translation block is still accepted;
    // multiple untagged or differently tagged languages are ambiguous and dropped.
    let zh_hans: Vec<&(String, String)> = blocks
        .iter()
        .filter(|(attributes, _)| attributes.to_ascii_lowercase().contains("zh-hans"))
        .collect();
    let untagged: Vec<&(String, String)> = blocks
        .iter()
        .filter(|(attributes, _)| !attributes.to_ascii_lowercase().contains("xml:lang"))
        .collect();
    let chosen: Vec<&(String, String)> = if !zh_hans.is_empty() {
        zh_hans
    } else if blocks.len() == 1 {
        blocks.iter().collect()
    } else {
        untagged
    };
    for (_, body) in chosen {
        for entry in text_regex().captures_iter(body) {
            let key = entry.get(1).map(|m| m.as_str()).unwrap_or_default();
            let text = strip_tags(entry.get(2).map(|m| m.as_str()).unwrap_or_default());
            if !key.is_empty() && !text.is_empty() {
                parsed.translations.insert(key.to_string(), text);
            } else if !key.is_empty() {
                parsed
                    .translation_misses
                    .insert(key.to_string(), "empty_translation_discarded");
            }
        }
    }
    parsed
}

/// Collects timed word spans, recursing into untimed containers: Apple nests the timed spans
/// of background vocals inside an outer span without its own timing.
fn collect_timed_spans(fragment: &str, words: &mut Vec<Word>) {
    let mut rest = fragment;
    while let Some(open) = rest.find("<span") {
        let after_open = &rest[open + "<span".len()..];
        let Some(attributes_end) = after_open.find('>') else {
            break;
        };
        let attributes = &after_open[..attributes_end];
        let inner_start = attributes_end + 1;
        let Some(inner_end) = find_span_close(inner_start, after_open) else {
            break;
        };
        let inner = &after_open[inner_start..inner_end];
        rest = &after_open[inner_end..];

        let times = time_regex().captures(attributes);
        let (Some(start), Some(end)) = (
            times
                .as_ref()
                .and_then(|capture| capture.get(1))
                .and_then(|m| parse_time(m.as_str())),
            times
                .as_ref()
                .and_then(|capture| capture.get(2))
                .and_then(|m| parse_time(m.as_str())),
        ) else {
            collect_timed_spans(inner, words);
            continue;
        };
        let text = strip_tags_raw(inner);
        if text.trim().is_empty() {
            continue;
        }
        words.push(Word {
            text,
            start_ms: start,
            end_ms: end.max(start),
        });
    }
}

/// Finds the `</span>` that closes a span whose inner content starts at `from`, skipping over
/// nested spans. Returns the offset just past the closing tag.
fn find_span_close(from: usize, fragment: &str) -> Option<usize> {
    let mut depth = 1usize;
    let mut cursor = from;
    while cursor <= fragment.len() {
        let open = fragment[cursor..]
            .find("<span")
            .map(|offset| cursor + offset);
        let close = fragment[cursor..]
            .find("</span>")
            .map(|offset| cursor + offset);
        match (open, close) {
            (Some(open_at), Some(close_at)) if open_at < close_at => {
                depth += 1;
                cursor = open_at + "<span".len();
            }
            (_, Some(close_at)) => {
                depth -= 1;
                cursor = close_at + "</span>".len();
                if depth == 0 {
                    return Some(cursor);
                }
            }
            _ => return None,
        }
    }
    None
}

fn lrc_timestamp(millis: u64) -> String {
    let total_seconds = millis / 1000;
    let minutes = total_seconds / 60;
    let seconds = total_seconds % 60;
    let centis = (millis % 1000) / 10;
    format!("[{minutes:02}:{seconds:02}.{centis:02}]")
}

pub(crate) fn to_lrc(lines: &[Line]) -> String {
    let mut out = String::new();
    for line in lines {
        out.push_str(&lrc_timestamp(line.start_ms));
        out.push_str(line.text.trim());
        out.push('\n');
    }
    out
}

fn to_translated_lrc(lines: &[Line], translations: &HashMap<String, String>) -> Option<String> {
    let mut out = String::new();
    for line in lines {
        let Some(key) = line.key.as_ref() else {
            continue;
        };
        let Some(text) = translations.get(key) else {
            continue;
        };
        out.push_str(&lrc_timestamp(line.start_ms));
        out.push_str(text.trim());
        out.push('\n');
    }
    if out.trim().is_empty() {
        None
    } else {
        Some(out)
    }
}

fn word_by_word_json(lines: &[Line], translations: &HashMap<String, String>) -> Value {
    Value::Array(
        lines
            .iter()
            .filter(|line| !line.words.is_empty())
            .map(|line| {
                json!({
                    "start_ms": line.start_ms,
                    "end_ms": line.end_ms,
                    "text": line.text,
                    "translated_text": line.key.as_ref().and_then(|key| translations.get(key)).map(|text| text.trim()),
                    "segments": line.words.iter().map(|word| json!({
                        "text": word.text,
                        "start_ms": word.start_ms,
                        "end_ms": word.end_ms,
                    })).collect::<Vec<_>>(),
                })
            })
            .collect(),
    )
}

fn failure(error_type: &str, message: impl Into<String>) -> Value {
    json!({
        "ok": false,
        "source": "apple",
        "error_type": error_type,
        "error": message.into(),
    })
}

struct Candidate {
    id: String,
    title: String,
    artist: String,
    album: String,
    duration_ms: u64,
}

fn select_candidate(
    candidates: &[Candidate],
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: u64,
) -> Option<usize> {
    let query = MatchMetadata {
        title,
        artist,
        album,
        duration_ms: Some(duration_ms),
    };
    let mut scored: Vec<(usize, u64, f64)> = candidates
        .iter()
        .enumerate()
        .filter_map(|(index, candidate)| {
            let difference = candidate.duration_ms.abs_diff(duration_ms);
            if difference > DURATION_TOLERANCE_MS {
                return None;
            }
            let metadata = MatchMetadata {
                title: &candidate.title,
                artist: &candidate.artist,
                album: &candidate.album,
                duration_ms: Some(candidate.duration_ms),
            };
            let assessment = crate::confidence::assess(query, metadata)?;
            Some((index, difference, assessment.score.total))
        })
        .collect();
    // The match score is the primary signal; the duration difference only breaks ties. A
    // closer-but-wrong release must not outrank the best-scoring candidate.
    scored.sort_by(|left, right| {
        right
            .2
            .partial_cmp(&left.2)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| left.1.cmp(&right.1))
    });
    scored.first().map(|entry| entry.0)
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn fetch(
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: u64,
    media_user_token: &str,
    developer_token: &str,
    storefront: &str,
    lookup_id: jni::sys::jlong,
) -> Value {
    let result = (|| -> Result<Value, String> {
        let _guard = crate::begin_lookup_cancellation(lookup_id)?;
        if media_user_token.trim().is_empty() || developer_token.trim().is_empty() {
            return Err("NeedCredential".to_string());
        }
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| "NativeError".to_string())?;
        runtime.block_on(async {
            crate::await_with_cancellation(
                async {
                    tokio::time::timeout(
                        Duration::from_secs(20),
                        resolve(
                            title,
                            artist,
                            album,
                            duration_ms,
                            media_user_token,
                            developer_token,
                            storefront,
                            lookup_id,
                            API,
                        ),
                    )
                    .await
                    .map_err(|_| "NetworkError".to_string())?
                },
                lookup_id,
            )
            .await?
        })
    })();

    resolve_result(result)
}

fn resolve_result(result: Result<Value, String>) -> Value {
    match result {
        Ok(value) => value,
        Err(error) if error == "NeedCredential" => {
            failure("NeedCredential", "Apple Music 账号未登录")
        }
        Err(error) if error == "NetworkError" => failure("NetworkError", "Apple Music 请求超时"),
        Err(error) if matches!(error.as_str(), "ParseError" | "NotFound" | "RateLimited") => {
            failure(&error, &error)
        }
        Err(error) if error == "Cancelled" => failure("Cancelled", error),
        Err(error) => failure("NativeError", error),
    }
}

#[allow(clippy::too_many_arguments)]
async fn resolve(
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: u64,
    media_user_token: &str,
    developer_token: &str,
    storefront: &str,
    lookup_id: jni::sys::jlong,
    api: &str,
) -> Result<Value, String> {
    let client = AppleClient::new(media_user_token, developer_token, storefront, api)?;
    let candidates = client.search(title, artist, album, 10, lookup_id).await?;
    let Some(index) = select_candidate(&candidates, title, artist, album, duration_ms) else {
        return Ok(failure("NotFound", "Apple catalogue has no reliable match"));
    };
    client
        .lyrics(&candidates[index], album, lookup_id, false)
        .await
}

struct AppleClient<'a> {
    client: reqwest::Client,
    user: &'a str,
    developer: &'a str,
    storefront: &'a str,
    api: &'a str,
}

impl<'a> AppleClient<'a> {
    fn new(
        user: &'a str,
        developer: &'a str,
        storefront: &'a str,
        api: &'a str,
    ) -> Result<Self, String> {
        let storefront = if storefront.trim().is_empty() {
            "us"
        } else {
            storefront.trim()
        };
        if storefront.len() != 2 || !storefront.bytes().all(|b| b.is_ascii_lowercase()) {
            return Err("ParseError".into());
        }
        #[cfg(test)]
        let client = crate::test_http::client();
        #[cfg(not(test))]
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|_| "NativeError")?;
        Ok(Self {
            client,
            user,
            developer,
            storefront,
            api,
        })
    }

    fn headers(&self, request: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        request
            .header("Authorization", format!("Bearer {}", self.developer))
            .header("media-user-token", self.user)
            .header("Origin", "https://music.apple.com")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
    }

    async fn search(
        &self,
        title: &str,
        artist: &str,
        album: &str,
        limit: usize,
        lookup_id: i64,
    ) -> Result<Vec<Candidate>, String> {
        crate::check_lookup_cancelled(lookup_id)?;
        let term = if album.trim().is_empty() {
            format!("{title} {artist}")
        } else {
            format!("{title} {artist} {album}")
        }
        .trim()
        .to_string();
        let search_url = format!("{}/v1/catalog/{}/search", self.api, self.storefront);
        let response = self
            .headers(self.client.get(&search_url))
            .query(&[
                ("term", term.as_str()),
                ("types", "songs"),
                ("limit", limit.to_string().as_str()),
            ])
            .send()
            .await
            .map_err(|_| "NetworkError".to_string())?;
        check_status(&response)?;
        let body: Value = response
            .json()
            .await
            .map_err(|_| "ParseError".to_string())?;

        let songs = body
            .pointer("/results/songs/data")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let candidates: Vec<Candidate> = songs
            .iter()
            .filter_map(|song| {
                let id = song.get("id")?.as_str()?.to_string();
                let attributes = song.get("attributes")?;
                Some(Candidate {
                    id,
                    title: attributes.get("name")?.as_str()?.to_string(),
                    artist: attributes.get("artistName")?.as_str()?.to_string(),
                    album: attributes
                        .get("albumName")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_string(),
                    duration_ms: attributes.get("durationInMillis")?.as_u64()?,
                })
            })
            .collect();

        Ok(candidates)
    }

    async fn lyrics(
        &self,
        candidate: &Candidate,
        album: &str,
        lookup_id: i64,
        manual: bool,
    ) -> Result<Value, String> {
        if candidate.id.is_empty() || !candidate.id.bytes().all(|b| b.is_ascii_digit()) {
            return Err("ParseError".into());
        }
        crate::check_lookup_cancelled(lookup_id)?;
        let song_url = format!(
            "{}/v1/catalog/{}/songs/{}",
            self.api, self.storefront, candidate.id
        );
        let response = self
            .headers(self.client.get(&song_url))
            .query(&[
                ("include", "syllable-lyrics,lyrics"),
                ("extend", "ttmlLocalizations"),
                ("l", "zh-Hans"),
            ])
            .send()
            .await
            .map_err(|_| "NetworkError".to_string())?;
        check_status(&response)?;
        let body: Value = response
            .json()
            .await
            .map_err(|_| "ParseError".to_string())?;
        if manual
            && body.pointer("/data/0/id").and_then(Value::as_str) != Some(candidate.id.as_str())
        {
            return Err("ParseError".into());
        }
        let relationships = body
            .pointer("/data/0/relationships")
            .cloned()
            .unwrap_or(Value::Null);
        let ttml = ["syllable-lyrics", "lyrics"]
            .iter()
            .find_map(|key| {
                relationships
                    .pointer(&format!("/{key}/data/0/attributes"))
                    .and_then(|attributes| {
                        attributes
                            .get("ttmlLocalizations")
                            .or_else(|| attributes.get("ttml"))
                    })
                    .and_then(Value::as_str)
            })
            .map(str::to_string);

        let Some(ttml) = ttml else {
            return Ok(json!({
                "ok": false,
                "source": "apple",
                "id": candidate.id,
                "title": candidate.title,
                "artist": candidate.artist,
                "error_type": "NotFound",
                "error": "该歌曲没有可用歌词",
            }));
        };

        let (mut result, _) = parse_native(&ttml);
        if manual && result["ok"] != true {
            return Ok(crate::manual::unavailable(
                crate::manual::UnavailableReason::NoUsableLines,
            ));
        }
        if result["ok"] == true {
            for (key, value) in [
                ("id", json!(candidate.id)),
                ("title", json!(candidate.title)),
                ("artist", json!(candidate.artist)),
                ("album", json!(candidate.album)),
                ("duration_ms", json!(candidate.duration_ms)),
                ("album_query", json!(album)),
            ] {
                result[key] = value;
            }
        }
        Ok(result)
    }
}

fn check_status(response: &reqwest::Response) -> Result<(), String> {
    match response.status().as_u16() {
        200..=299 => Ok(()),
        401 | 403 => Err("NeedCredential".into()),
        404 => Err("NotFound".into()),
        429 => Err("RateLimited".into()),
        _ => Err("NetworkError".into()),
    }
}

/// Manual choices are ranked, never filtered by the automatic acceptance policy.
pub(crate) fn manual(
    raw: &str,
    user: &str,
    developer: &str,
    storefront: &str,
    lookup_id: i64,
) -> Value {
    let result = (|| -> Result<Value, String> {
        let _guard = crate::begin_lookup_cancellation(lookup_id)?;
        if user.trim().is_empty() || developer.trim().is_empty() {
            return Err("NeedCredential".into());
        }
        let request: crate::manual::Request =
            serde_json::from_str(raw).map_err(|_| "ParseError")?;
        if request.provider != "apple" || !matches!(request.operation.as_str(), "search" | "fetch")
        {
            return Err("ParseError".into());
        }
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| "NativeError")?;
        runtime.block_on(async {
            crate::await_with_cancellation(
                async {
                    tokio::time::timeout(
                        Duration::from_secs(20),
                        manual_request(&request, user, developer, storefront, lookup_id, API),
                    )
                    .await
                    .map_err(|_| "NetworkError".to_string())?
                },
                lookup_id,
            )
            .await?
        })
    })();
    resolve_result(result)
}

async fn manual_request(
    request: &crate::manual::Request,
    user: &str,
    developer: &str,
    storefront: &str,
    lookup_id: i64,
    api: &str,
) -> Result<Value, String> {
    let client = AppleClient::new(user, developer, storefront, api)?;
    let track = &request.track;
    if request.operation == "search" {
        let candidates = client
            .search(&track.title, &track.artist, &track.album, 25, lookup_id)
            .await?;
        let tracks = candidates
            .into_iter()
            .map(|c| crate::manual::Track {
                id: c.id,
                title: c.title,
                artist: c.artist,
                album: c.album,
                duration_ms: c.duration_ms,
                score: 0.0,
            })
            .collect();
        let mut candidates =
            serde_json::to_value(crate::manual::ranked(tracks, track)).map_err(|_| "ParseError")?;
        for candidate in candidates.as_array_mut().ok_or("ParseError")? {
            candidate["storefront"] = json!(client.storefront);
        }
        Ok(json!({"ok":true,"candidates":candidates}))
    } else {
        let candidate = Candidate {
            id: track.id.clone(),
            title: track.title.clone(),
            artist: track.artist.clone(),
            album: track.album.clone(),
            duration_ms: track.duration_ms,
        };
        client
            .lyrics(&candidate, &track.album, lookup_id, true)
            .await
    }
}

/// Shared offline/network TTML conversion. Trace fields describe the parser output, not UI policy.
pub(crate) fn parse_native(ttml: &str) -> (Value, Value) {
    let parsed = parse_ttml(ttml);
    let has_translation_blocks = translation_regex().is_match(ttml);
    let trace = parsed
        .lines
        .iter()
        .enumerate()
        .map(|(index, line)| {
            let translation = line
                .key
                .as_ref()
                .and_then(|key| parsed.translations.get(key));
            let reason = if line.key.is_none() {
                "missing_line_key"
            } else if translation.is_some() {
                "matched_by_key"
            } else if !has_translation_blocks {
                "no_translation_blocks"
            } else {
                parsed
                    .translation_misses
                    .get(line.key.as_deref().unwrap_or_default())
                    .copied()
                    .unwrap_or("key_absent_from_selected_translation_blocks")
            };
            json!({
                "lineIndex": index + 1, "sourceIndex": line.source_index + 1,
                "key": line.key, "begin": line.begin, "end": line.end,
                "startMs": line.start_ms, "endMs": line.end_ms,
                "rawText": line.raw_text, "echoText": line.text,
                "translatedText": translation,
                "translationState": if line.words.is_empty() { "missing" }
                    else if translation.is_some() { "value" } else { "null" },
                "reason": reason,
            })
        })
        .collect::<Vec<_>>();
    let result = if parsed.lines.is_empty() {
        failure("ParseError", "TTML 里没有可解析的歌词行")
    } else {
        json!({
            "ok": true, "source": "apple",
            "lrc": to_lrc(&parsed.lines),
            "translated_lrc": to_translated_lrc(&parsed.lines, &parsed.translations),
            "merged_lrc": Value::Null,
            "word_by_word": word_by_word_json(&parsed.lines, &parsed.translations),
        })
    };
    (result, json!(trace))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn audit_trace_preserves_non_word_lines_and_source_indices() {
        let (native, trace) = parse_native(
            r#"<tt><body>
            <p>untimed</p>
            <p begin="1" end="2" itunes:key="L2">plain</p>
            <p begin="3" end="4" itunes:key="L3"><span begin="3" end="4">word(echo)</span></p>
            </body><translation xml:lang="zh-Hans"><text for="L2">译文</text>
            <text for="L3"></text></translation></tt>"#,
        );
        assert_eq!(native["word_by_word"].as_array().unwrap().len(), 1);
        assert_eq!(trace.as_array().unwrap().len(), 2);
        assert_eq!(trace[0]["sourceIndex"], 2);
        assert_eq!(trace[0]["translationState"], "missing");
        assert_eq!(trace[1]["rawText"], "word(echo)");
        assert_eq!(trace[1]["translationState"], "null");
        assert_eq!(trace[1]["reason"], "empty_translation_discarded");
        assert!(native["word_by_word"][0]["translated_text"].is_null());
    }

    fn manual_input(operation: &str) -> crate::manual::Request {
        serde_json::from_value(json!({"provider":"apple","operation":operation,
            "track":{"id":"42","title":"Song","artist":"Artist","album":"","duration_ms":180000}}))
        .unwrap()
    }

    fn http_json(body: Value) -> &'static str {
        Box::leak(format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{body}").into_boxed_str())
    }

    fn details(id: &str, ttml: Option<&str>) -> Value {
        let relationships = ttml
            .map(|text| json!({"syllable-lyrics":{"data":[{"attributes":{"ttml":text}}]}}))
            .unwrap_or(json!({}));
        json!({"data":[{"id":id,"relationships":relationships}]})
    }

    #[tokio::test]
    async fn manual_search_keeps_variants_and_weak_matches_and_ranks_and_deduplicates() {
        let songs: Vec<Value> = [("2", "Unrelated", 240000), ("3", "Song (Live)", 200000), ("1", "Song", 180000), ("1", "Song", 180000)]
            .into_iter().map(|(id, name, duration)| json!({"id":id,"attributes":{"name":name,"artistName":"Artist","durationInMillis":duration}})).collect();
        let server = crate::test_http::Server::new(vec![http_json(
            json!({"results":{"songs":{"data":songs}}}),
        )]);
        let result = manual_request(
            &manual_input("search"),
            "user",
            "developer",
            "tr",
            0,
            &server.url,
        )
        .await
        .unwrap();
        let candidates = result["candidates"].as_array().unwrap();
        assert_eq!(candidates.len(), 3);
        assert_eq!(candidates[0]["id"], "1");
        assert!(candidates.iter().all(|c| c["storefront"] == "tr"));
        let requests = server.requests();
        assert_eq!(requests.len(), 1);
        assert!(requests[0].contains("/v1/catalog/tr/search?"));
        assert!(requests[0].contains("limit=25"));
        server.finish();
    }

    #[tokio::test]
    async fn manual_fetch_uses_exact_id_and_keeps_words_and_translation() {
        let server = crate::test_http::Server::new(vec![http_json(details("42", Some(SAMPLE)))]);
        let result = manual_request(
            &manual_input("fetch"),
            "user",
            "developer",
            "hk",
            0,
            &server.url,
        )
        .await
        .unwrap();
        assert_eq!(result["ok"], true);
        assert_eq!(result["id"], "42");
        assert!(!result["word_by_word"].as_array().unwrap().is_empty());
        assert!(!result["translated_lrc"].as_str().unwrap().is_empty());
        let requests = server.requests();
        assert_eq!(requests.len(), 1);
        assert!(requests[0].contains("/v1/catalog/hk/songs/42?"));
        server.finish();
    }

    #[tokio::test]
    async fn manual_fetch_rejects_wrong_identity_and_unusable_content() {
        for (body, error, reason) in [
            (details("99", Some(SAMPLE)), Some("ParseError"), None),
            (details("42", None), None, Some("NotFound")),
            (
                details("42", Some("<tt><p>untimed</p></tt>")),
                None,
                Some("no_usable_lines"),
            ),
        ] {
            let server = crate::test_http::Server::new(vec![http_json(body)]);
            let result = manual_request(
                &manual_input("fetch"),
                "user",
                "developer",
                "us",
                0,
                &server.url,
            )
            .await;
            if let Some(error) = error {
                assert_eq!(result.unwrap_err(), error);
            } else {
                let result = result.unwrap();
                assert_eq!(
                    result["reason"].as_str().or(result["error_type"].as_str()),
                    reason
                );
            }
            server.finish();
        }
    }

    #[tokio::test]
    async fn manual_http_errors_are_classified_for_search_and_fetch() {
        for operation in ["search", "fetch"] {
            for (status, expected) in [
                (401, "NeedCredential"),
                (403, "NeedCredential"),
                (404, "NotFound"),
                (429, "RateLimited"),
                (500, "NetworkError"),
            ] {
                let response = Box::leak(
                    format!("HTTP/1.1 {status} Error\r\nContent-Length: 0\r\n\r\n")
                        .into_boxed_str(),
                );
                let server = crate::test_http::Server::new(vec![response]);
                let result = manual_request(
                    &manual_input(operation),
                    "user",
                    "developer",
                    "us",
                    0,
                    &server.url,
                )
                .await;
                assert_eq!(result.unwrap_err(), expected);
                server.finish();
            }
        }
    }

    #[test]
    fn manual_validation_and_cancellation_do_not_require_network() {
        let raw = json!({"provider":"apple","operation":"search","track":{"id":"","title":"Song","artist":"Artist","album":"","duration_ms":0}}).to_string();
        assert_eq!(
            manual(&raw, "", "developer", "us", 0)["error_type"],
            "NeedCredential"
        );
        assert_eq!(
            manual(
                &raw.replace("search", "invalid"),
                "user",
                "developer",
                "us",
                0
            )["error_type"],
            "ParseError"
        );
        let lookup = 849182;
        crate::cancel_lookup(lookup);
        assert_eq!(manual(&raw, "user", "developer", "us", lookup)["ok"], false);
        crate::clear_lookup(lookup);
    }

    const SEARCH_HIT: &str = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{\"results\":{\"songs\":{\"data\":[{\"id\":\"1\",\"attributes\":{\"name\":\"Song\",\"artistName\":\"Artist\",\"durationInMillis\":180000}}]}}}";

    #[tokio::test]
    async fn search_and_details_classify_http_and_parse_failures() {
        for details in [false, true] {
            for (response, expected) in [
                (
                    "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n",
                    "NeedCredential",
                ),
                (
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n",
                    "NeedCredential",
                ),
                (
                    "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n",
                    "NetworkError",
                ),
                (
                    "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\ninvalid json",
                    "ParseError",
                ),
            ] {
                let responses = if details {
                    vec![SEARCH_HIT, response]
                } else {
                    vec![response]
                };
                let server = crate::test_http::Server::new(responses);
                let result = resolve(
                    "Song",
                    "Artist",
                    "",
                    180000,
                    "user",
                    "developer",
                    "us",
                    0,
                    &server.url,
                )
                .await;
                assert_eq!(resolve_result(result)["error_type"], expected);
                server.finish();
            }
        }
    }

    const SAMPLE: &str = r#"<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word" xml:lang="zh-Hant">
<body><div><p begin="22.186" end="26.552" itunes:key="L1"><span begin="22.186" end="23.000">攔路雨</span><span begin="23.000" end="26.552">偏似雪花</span></p>
<p begin="26.744" end="31.200" itunes:key="L2"><span begin="26.744" end="31.200">這風褸我給你磨到有襟花</span></p>
</div></body>
<head><translations><translation type="subtitle" xml:lang="zh-Hans"><text for="L1">拦路雨偏似雪花</text><text for="L2">这风衣我给你磨到有襟花</text></translation></translations></head></tt>"#;

    #[test]
    fn parses_line_and_word_times() {
        let parsed = parse_ttml(SAMPLE);
        assert_eq!(parsed.lines.len(), 2);
        assert_eq!(parsed.lines[0].start_ms, 22_186);
        assert_eq!(parsed.lines[0].words.len(), 2);
        assert_eq!(parsed.lines[0].text, "攔路雨偏似雪花");
        assert_eq!(parsed.lines[1].words[0].end_ms, 31_200);
    }

    #[test]
    fn builds_lrc_and_translated_lrc() {
        let parsed = parse_ttml(SAMPLE);
        let lrc = to_lrc(&parsed.lines);
        assert!(lrc.starts_with("[00:22.18]攔路雨偏似雪花"));
        let translated = to_translated_lrc(&parsed.lines, &parsed.translations).unwrap();
        assert!(translated.contains("[00:22.18]拦路雨偏似雪花"));
    }

    const PARTIAL_TRANSLATIONS: &str = r#"<tt><body><div>
<p begin="1.009" end="2" itunes:key="L1"><span begin="1.009" end="2">one</span></p>
<p begin="2.019" end="3" itunes:key="L2"><span begin="2.019" end="3">two</span></p>
<p begin="3.029" end="4" itunes:key="L3">three</p>
</div></body><head><translations><translation type="subtitle" xml:lang="zh-Hans">
<text for="L2">二</text><text for="L3">三</text>
</translation></translations></head></tt>"#;

    #[test]
    fn word_translations_follow_keys_instead_of_filtered_positions() {
        let parsed = parse_ttml(PARTIAL_TRANSLATIONS);
        let words = word_by_word_json(&parsed.lines, &parsed.translations);
        assert_eq!(words.as_array().unwrap().len(), 2);
        assert!(words[0].get("translated_text").unwrap().is_null());
        assert_eq!(words[1]["translated_text"], "二");
        assert_eq!(
            to_translated_lrc(&parsed.lines, &parsed.translations).as_deref(),
            Some("[00:02.01]二\n[00:03.02]三\n")
        );
    }

    #[test]
    fn missing_keys_skip_only_the_affected_line() {
        for missing in 0..3 {
            let mut parsed = parse_ttml(PARTIAL_TRANSLATIONS);
            parsed.translations.insert("L1".into(), "一".into());
            parsed.lines[missing].key = None;
            let expected = ["[00:01.00]一\n", "[00:02.01]二\n", "[00:03.02]三\n"]
                .iter()
                .enumerate()
                .filter_map(|(index, text)| (index != missing).then_some(*text))
                .collect::<String>();
            assert_eq!(
                to_translated_lrc(&parsed.lines, &parsed.translations),
                Some(expected)
            );
            let words = word_by_word_json(&parsed.lines, &parsed.translations);
            for index in 0..2 {
                if index == missing {
                    assert!(words[index]["translated_text"].is_null());
                } else {
                    assert_eq!(words[index]["translated_text"], ["一", "二"][index]);
                }
            }
        }
    }

    #[test]
    fn no_valid_translation_returns_none() {
        let mut parsed = parse_ttml(PARTIAL_TRANSLATIONS);
        assert_eq!(to_translated_lrc(&parsed.lines, &HashMap::new()), None);
        assert!(word_by_word_json(&parsed.lines, &HashMap::new())
            .as_array()
            .unwrap()
            .iter()
            .all(|line| line["translated_text"].is_null()));
        for line in &mut parsed.lines {
            line.key = None;
        }
        assert_eq!(to_translated_lrc(&parsed.lines, &parsed.translations), None);
        assert!(word_by_word_json(&parsed.lines, &parsed.translations)
            .as_array()
            .unwrap()
            .iter()
            .all(|line| line["translated_text"].is_null()));
        assert_eq!(
            resolve_result(Err("ParseError".into()))["error_type"],
            "ParseError"
        );
    }

    #[test]
    fn parses_clock_style_times() {
        assert_eq!(parse_time("22.186"), Some(22_186));
        assert_eq!(parse_time("1:02.500"), Some(62_500));
        assert_eq!(parse_time("1:02:03.250"), Some(3_723_250));
    }

    #[test]
    #[ignore = "needs APPLE_MEDIA_TOKEN and APPLE_DEV_TOKEN"]
    fn live_apple_fetch_returns_word_timing() {
        let media = std::env::var("APPLE_MEDIA_TOKEN").expect("media token");
        let developer = std::env::var("APPLE_DEV_TOKEN").expect("developer token");
        let value = fetch(
            "富士山下",
            "陈奕迅",
            "",
            259_133,
            &media,
            &developer,
            "tr",
            0,
        );
        assert_eq!(value["ok"], true, "{value}");
        let lrc = value["lrc"].as_str().unwrap_or_default();
        assert!(lrc.contains("攔路雨") || lrc.contains("拦路雨"), "{}", lrc);
        let words = value["word_by_word"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        assert!(!words.is_empty());
        assert!(words[0]["segments"].as_array().unwrap().len() > 1);
    }

    fn catalogue_candidate(title: &str, artist: &str, duration_ms: u64) -> Candidate {
        Candidate {
            id: "test-track".into(),
            title: title.into(),
            artist: artist.into(),
            album: "Studio Album".into(),
            duration_ms,
        }
    }

    #[test]
    fn rejects_live_and_remix_candidates_even_with_matching_duration() {
        for variant in ["Song (Live)", "Song (Remix)"] {
            let candidates = [
                catalogue_candidate(variant, "Artist", 200_000),
                catalogue_candidate("Song", "Artist", 201_000),
            ];
            assert_eq!(
                select_candidate(&candidates, "Song", "Artist", "Studio Album", 200_000),
                Some(1),
                "{variant}"
            );
            assert_eq!(
                select_candidate(&candidates[..1], "Song", "Artist", "Studio Album", 200_000),
                None,
                "{variant}"
            );
        }
    }

    #[test]
    fn cross_script_candidate_requires_strong_artist_and_matching_duration() {
        // The localized annotation supplies title evidence while the full query and
        // candidate still differ in script. Duration alone must not corroborate it.
        let mut candidate = catalogue_candidate("Sunny Day", "无关歌手", 200_000);
        assert_eq!(
            select_candidate(
                std::slice::from_ref(&candidate),
                "Sunny Day (晴天)",
                "周杰伦",
                "",
                200_000
            ),
            None
        );
        candidate.artist = "周杰伦".into();
        assert_eq!(
            select_candidate(
                std::slice::from_ref(&candidate),
                "Sunny Day (晴天)",
                "周杰伦",
                "",
                200_000
            ),
            Some(0)
        );
        candidate.duration_ms = 202_500;
        assert_eq!(
            select_candidate(&[candidate], "Sunny Day (晴天)", "周杰伦", "", 200_000),
            None
        );
    }

    #[test]
    fn selects_exact_match_within_duration_window() {
        for duration_ms in [200_000, 202_500] {
            let candidates = [catalogue_candidate("Song", "Artist", duration_ms)];
            assert_eq!(
                select_candidate(&candidates, "Song", "Artist", "Studio Album", 200_000),
                Some(0)
            );
        }
    }

    #[test]
    fn rejects_candidates_outside_the_duration_window() {
        let candidates = vec![Candidate {
            id: "1".into(),
            title: "晴天".into(),
            artist: "周杰伦".into(),
            album: "".into(),
            duration_ms: 202_501,
        }];
        assert!(select_candidate(&candidates, "晴天", "周杰伦", "", 200_000).is_none());
    }
}
