//! Apple Music catalogue search and syllable (word-timed) lyrics.
//!
//! Uses the web-player API: a developer token plus the listener's media user token. Never log either.
use crate::matching::{is_confident_early_match, score_candidate, MatchMetadata};
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
    for paragraph in paragraph_regex().captures_iter(ttml) {
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
        parsed.lines.push(Line {
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
        let key = line.key.as_ref()?;
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

fn word_by_word_json(lines: &[Line]) -> Value {
    Value::Array(
        lines
            .iter()
            .filter(|line| !line.words.is_empty())
            .map(|line| {
                json!({
                    "start_ms": line.start_ms,
                    "end_ms": line.end_ms,
                    "text": line.text,
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

fn pick_candidate(
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
            let score = score_candidate(query, metadata)?;
            if !is_confident_early_match(&score) {
                return None;
            }
            Some((index, difference, score.total))
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
        Err(error) if error == "ParseError" => failure("ParseError", error),
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
    crate::check_lookup_cancelled(lookup_id)?;
    #[cfg(test)]
    let client = crate::test_http::client();
    #[cfg(not(test))]
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|_| "NativeError".to_string())?;
    let headers = |request: reqwest::RequestBuilder| {
        request
            .header("Authorization", format!("Bearer {developer_token}"))
            .header("media-user-token", media_user_token)
            .header("Origin", "https://music.apple.com")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
    };

    let storefront = if storefront.trim().is_empty() {
        "us"
    } else {
        storefront.trim()
    };
    let term = if album.trim().is_empty() {
        format!("{title} {artist}")
    } else {
        format!("{title} {artist} {album}")
    }
    .trim()
    .to_string();
    let search_url = format!("{api}/v1/catalog/{storefront}/search");
    let response = headers(client.get(&search_url))
        .query(&[("term", term.as_str()), ("types", "songs"), ("limit", "10")])
        .send()
        .await
        .map_err(|_| "NetworkError".to_string())?;
    if response.status().as_u16() == 401 || response.status().as_u16() == 403 {
        return Ok(failure("NeedCredential", "Apple Music 凭据无效或已过期"));
    }
    if !response.status().is_success() {
        return Ok(failure(
            "NetworkError",
            format!("Apple 搜索 HTTP {}", response.status()),
        ));
    }
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

    let Some(index) = pick_candidate(&candidates, title, artist, album, duration_ms) else {
        return Ok(json!({
            "ok": false,
            "source": "apple",
            "error_type": "NotFound",
            "error": "Apple 目录没有可靠匹配",
        }));
    };
    let candidate = &candidates[index];

    crate::check_lookup_cancelled(lookup_id)?;
    let song_url = format!("{}/v1/catalog/{}/songs/{}", api, storefront, candidate.id);
    let response = headers(client.get(&song_url))
        .query(&[
            ("include", "syllable-lyrics,lyrics"),
            ("extend", "ttmlLocalizations"),
            ("l", "zh-Hans"),
        ])
        .send()
        .await
        .map_err(|_| "NetworkError".to_string())?;
    if response.status().as_u16() == 401 || response.status().as_u16() == 403 {
        return Ok(failure("NeedCredential", "Apple Music 凭据无效或已过期"));
    }
    if !response.status().is_success() {
        return Ok(failure(
            "NetworkError",
            format!("Apple 歌词 HTTP {}", response.status()),
        ));
    }
    let body: Value = response
        .json()
        .await
        .map_err(|_| "ParseError".to_string())?;
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

    let parsed = parse_ttml(&ttml);
    if parsed.lines.is_empty() {
        return Ok(failure("ParseError", "TTML 里没有可解析的歌词行"));
    }
    let lrc = to_lrc(&parsed.lines);
    let translated_lrc = to_translated_lrc(&parsed.lines, &parsed.translations);

    Ok(json!({
        "ok": true,
        "source": "apple",
        "id": candidate.id,
        "title": candidate.title,
        "artist": candidate.artist,
        "album": candidate.album,
        "duration_ms": candidate.duration_ms,
        "lrc": lrc,
        "translated_lrc": translated_lrc,
        "merged_lrc": Value::Null,
        "word_by_word": word_by_word_json(&parsed.lines),
        "album_query": album,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

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

    #[test]
    fn rejects_candidates_outside_the_duration_window() {
        let candidates = vec![Candidate {
            id: "1".into(),
            title: "晴天".into(),
            artist: "周杰伦".into(),
            album: "".into(),
            duration_ms: 269_000,
        }];
        assert!(pick_candidate(&candidates, "晴天", "周杰伦", "", 200_000).is_none());
    }
}
