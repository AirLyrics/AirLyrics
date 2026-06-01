use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use ncmapi::types::{Album, Artist, LyricResp, SearchSongResp, Song};
use ncmapi::NcmApi;
use serde::Serialize;

mod lrc;
mod musixmatch;

#[derive(Debug, Clone)]
struct Candidate {
    id: String,
    title: String,
    artist: String,
    album: String,
    duration_ms: u64,
    score: f64,
}

#[derive(Serialize)]
pub(crate) struct NativeResult {
    ok: bool,
    source: &'static str,
    id: Option<String>,
    title: Option<String>,
    artist: Option<String>,
    album: Option<String>,
    duration_ms: Option<u64>,
    lrc: Option<String>,
    translated_lrc: Option<String>,
    merged_lrc: Option<String>,
    karaoke_json: Option<String>,
    error_type: Option<&'static str>,
    error: Option<String>,
}

#[no_mangle]
pub extern "system" fn Java_com_andsi_airlyrics_lyrics_providers_NeteaseLyricsNative_fetchBestLyricsJson(
    env: JNIEnv,
    this: JObject,
    title: JString,
    artist: JString,
    album: JString,
    duration_ms: jni::sys::jlong,
    _reserved: jni::sys::jboolean,
) -> jstring {
    fetch_netease_lyrics_json(env, this, title, artist, album, duration_ms)
}

// Keep the old root-package symbol as a compatibility alias for older APKs/builds.
#[no_mangle]
pub extern "system" fn Java_com_andsi_airlyrics_NeteaseLyricsNative_fetchBestLyricsJson(
    env: JNIEnv,
    this: JObject,
    title: JString,
    artist: JString,
    album: JString,
    duration_ms: jni::sys::jlong,
    _reserved: jni::sys::jboolean,
) -> jstring {
    fetch_netease_lyrics_json(env, this, title, artist, album, duration_ms)
}

#[no_mangle]
pub extern "system" fn Java_com_andsi_airlyrics_lyrics_providers_MusixmatchLyricsNative_fetchBestLyricsJson(
    mut env: JNIEnv,
    _this: JObject,
    title: JString,
    artist: JString,
    album: JString,
    duration_ms: jni::sys::jlong,
    translation_language: JString,
    _reserved: jni::sys::jboolean,
) -> jstring {
    let title = jstring_to_string(&mut env, title);
    let artist = jstring_to_string(&mut env, artist);
    let album = jstring_to_string(&mut env, album);
    let translation_language = jstring_to_string(&mut env, translation_language);
    let duration_ms = if duration_ms > 0 {
        Some(duration_ms as u64)
    } else {
        None
    };

    let result = std::panic::catch_unwind(|| {
        musixmatch::fetch_best_lyrics(
            &title,
            &artist,
            &album,
            duration_ms,
            &translation_language,
            false,
        )
    })
    .unwrap_or_else(|_| Err("native panic while fetching musixmatch lyrics".to_string()));

    let json = match result {
        Ok(value) => serde_json::to_string(&value).unwrap_or_else(|_| {
            fallback_error(
                "musixmatch-rust",
                "SerializeError",
                "failed to serialize native result",
            )
        }),
        Err(err) => fallback_error("musixmatch-rust", classify_error(&err), &err),
    };

    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn fetch_netease_lyrics_json(
    mut env: JNIEnv,
    _this: JObject,
    title: JString,
    artist: JString,
    album: JString,
    duration_ms: jni::sys::jlong,
) -> jstring {
    let title = jstring_to_string(&mut env, title);
    let artist = jstring_to_string(&mut env, artist);
    let album = jstring_to_string(&mut env, album);
    let duration_ms = if duration_ms > 0 {
        Some(duration_ms as u64)
    } else {
        None
    };

    let result =
        std::panic::catch_unwind(|| fetch_best_lyrics(&title, &artist, &album, duration_ms))
            .unwrap_or_else(|_| Err("native panic while fetching lyrics".to_string()));

    let json = match result {
        Ok(value) => serde_json::to_string(&value).unwrap_or_else(|_| {
            fallback_error(
                "netease-rust",
                "SerializeError",
                "failed to serialize native result",
            )
        }),
        Err(err) => fallback_error("netease-rust", classify_error(&err), &err),
    };

    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn jstring_to_string(env: &mut JNIEnv, value: JString) -> String {
    env.get_string(&value)
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default()
        .trim()
        .to_string()
}

fn fallback_error(source: &'static str, error_type: &'static str, message: &str) -> String {
    serde_json::to_string(&NativeResult {
        ok: false,
        source,
        id: None,
        title: None,
        artist: None,
        album: None,
        duration_ms: None,
        lrc: None,
        translated_lrc: None,
        merged_lrc: None,
        karaoke_json: None,
        error_type: Some(error_type),
        error: Some(message.to_string()),
    })
    .unwrap_or_else(|_| format!(r#"{{"ok":false,"source":"{source}","error_type":"SerializeError","error":"unknown native error"}}"#))
}

fn classify_error(message: &str) -> &'static str {
    let lower = message.to_lowercase();
    if lower.contains("missingcredentials")
        || lower.contains("credential")
        || lower.contains("token")
    {
        "NeedCredential"
    } else if lower.contains("rate") || lower.contains("429") {
        "RateLimited"
    } else if lower.contains("restricted") || lower.contains("copyright") {
        "RestrictedLyrics"
    } else if lower.contains("no subtitle")
        || lower.contains("no usable subtitle")
        || lower.contains("no candidates")
        || lower.contains("not found")
        || lower.contains("could not be found")
        || lower.contains("404")
    {
        "NotFound"
    } else if lower.contains("network")
        || lower.contains("timeout")
        || lower.contains("failed to connect")
        || lower.contains("dns")
    {
        "NetworkError"
    } else {
        "Unknown"
    }
}

fn fetch_best_lyrics(
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
) -> Result<NativeResult, String> {
    if title.trim().is_empty() {
        return Err("empty title".into());
    }

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("failed to create tokio runtime: {e}"))?;

    runtime.block_on(async move {
        tokio::time::timeout(std::time::Duration::from_secs(12), async move {
            let api = NcmApi::new(false, "");
            let keywords = build_search_keywords(title, artist, album);
            let mut best_candidates = Vec::new();

            for keyword in keywords {
                let response = api
                    .search(&keyword, None)
                    .await
                    .map_err(|e| format!("netease search failed: {e}"))?;
                let search_resp: SearchSongResp = response
                    .deserialize()
                    .map_err(|e| format!("failed to decode search response: {e}"))?;

                let Some(result) = search_resp.result else {
                    continue;
                };

                for song in result.songs.iter() {
                    if let Some(candidate) = map_song(song, title, artist, album, duration_ms) {
                        best_candidates.push(candidate);
                    }
                }

                if !best_candidates.is_empty() {
                    break;
                }
            }

            let best = best_candidates
                .into_iter()
                .max_by(|a, b| {
                    a.score
                        .partial_cmp(&b.score)
                        .unwrap_or(std::cmp::Ordering::Equal)
                })
                .ok_or_else(|| "netease returned no song candidates".to_string())?;

            let song_id = best
                .id
                .parse::<usize>()
                .map_err(|e| format!("invalid netease song id {}: {e}", best.id))?;
            let lyric_response = api
                .lyric(song_id)
                .await
                .map_err(|e| format!("netease lyric query failed: {e}"))?;
            let lyric_resp: LyricResp = lyric_response
                .deserialize()
                .map_err(|e| format!("failed to decode lyric response: {e}"))?;

            let lrc = lyric_resp.lrc.and_then(|v| normalize_optional_lrc(v.lyric));
            let translated_lrc = lyric_resp
                .tlyric
                .and_then(|v| normalize_optional_lrc(v.lyric));
            let merged_lrc = merge_lrc(lrc.as_deref(), translated_lrc.as_deref())
                .or_else(|| lrc.clone())
                .or_else(|| translated_lrc.clone());

            if merged_lrc.as_deref().unwrap_or_default().trim().is_empty() {
                return Err("netease lyric is empty".to_string());
            }

            Ok(NativeResult {
                ok: true,
                source: "netease-rust",
                id: Some(best.id),
                title: Some(best.title),
                artist: Some(best.artist),
                album: Some(best.album),
                duration_ms: Some(best.duration_ms),
                lrc,
                translated_lrc,
                merged_lrc,
                karaoke_json: None,
                error_type: None,
                error: None,
            })
        })
        .await
        .map_err(|_| "netease lookup timed out".to_string())?
    })
}

fn build_search_keywords(title: &str, artist: &str, album: &str) -> Vec<String> {
    let mut keywords = Vec::new();
    let title = clean_query_part(title);
    let artist = clean_query_part(artist);
    let album = clean_query_part(album);

    if !title.is_empty() && !artist.is_empty() && !album.is_empty() {
        keywords.push(format!("{title} {album} {artist}"));
    }
    if !title.is_empty() && !artist.is_empty() {
        keywords.push(format!("{title} {artist}"));
    }
    if !title.is_empty() {
        keywords.push(title);
    }

    keywords.dedup();
    keywords
}

fn clean_query_part(value: &str) -> String {
    value
        .replace(['\n', '\r', '\t'], " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn map_song(
    song: &Song,
    target_title: &str,
    target_artist: &str,
    target_album: &str,
    target_duration_ms: Option<u64>,
) -> Option<Candidate> {
    let Song {
        name,
        id,
        artists,
        duration,
        album: Album { name: album, .. },
        ..
    } = song;

    let artist = artists
        .iter()
        .filter_map(|Artist { name, .. }| name.as_ref())
        .fold(String::new(), |mut acc, item| {
            if !acc.is_empty() {
                acc.push(',');
            }
            acc.push_str(item);
            acc
        });

    let album_name = album.as_deref().unwrap_or("");

    let title_score = similarity(target_title, name);
    let artist_score = if target_artist.trim().is_empty() {
        0.5
    } else {
        similarity(target_artist, &artist)
    };
    let album_score = if target_album.trim().is_empty() || album_name.trim().is_empty() {
        0.0
    } else {
        similarity(target_album, album_name)
    };
    let duration_score = target_duration_ms
        .map(|target| duration_similarity(target, *duration as u64))
        .unwrap_or(0.0);

    let mut score =
        title_score * 0.50 + artist_score * 0.28 + duration_score * 0.17 + album_score * 0.05;
    score -= version_penalty(name);

    if title_score < 0.18 && artist_score < 0.18 {
        return None;
    }

    Some(Candidate {
        id: id.to_string(),
        title: name.clone(),
        artist,
        album: album_name.to_string(),
        duration_ms: *duration as u64,
        score,
    })
}

fn normalize_optional_lrc(value: String) -> Option<String> {
    let value = value.replace("\r\n", "\n").replace('\r', "\n");
    if value.trim().is_empty() {
        None
    } else {
        Some(value)
    }
}

fn duration_similarity(target: u64, candidate: u64) -> f64 {
    if target == 0 || candidate == 0 {
        return 0.0;
    }

    let diff = target.abs_diff(candidate) as f64;
    if diff <= 1_500.0 {
        1.0
    } else if diff >= 45_000.0 {
        0.0
    } else {
        1.0 - (diff / 45_000.0)
    }
}

fn version_penalty(title: &str) -> f64 {
    let lower = title.to_lowercase();
    let bad_words = [
        "live",
        "remix",
        "cover",
        "instrumental",
        "伴奏",
        "现场",
        "现场版",
        "翻唱",
        "纯音乐",
        "dj版",
    ];

    bad_words
        .iter()
        .filter(|word| lower.contains(**word))
        .count() as f64
        * 0.08
}

fn similarity(a: &str, b: &str) -> f64 {
    let a = normalize_for_match(a);
    let b = normalize_for_match(b);

    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    if a == b {
        return 1.0;
    }
    if a.contains(&b) || b.contains(&a) {
        return 0.86;
    }

    dice_coefficient(&a, &b)
}

fn normalize_for_match(value: &str) -> String {
    value
        .to_lowercase()
        .chars()
        .filter(|ch| ch.is_alphanumeric() || is_cjk(*ch))
        .collect()
}

fn is_cjk(ch: char) -> bool {
    ('\u{4e00}'..='\u{9fff}').contains(&ch)
        || ('\u{3040}'..='\u{30ff}').contains(&ch)
        || ('\u{ac00}'..='\u{d7af}').contains(&ch)
}

fn dice_coefficient(a: &str, b: &str) -> f64 {
    let a_chars: Vec<char> = a.chars().collect();
    let b_chars: Vec<char> = b.chars().collect();

    if a_chars.len() == 1 || b_chars.len() == 1 {
        return if a_chars.iter().any(|c| b_chars.contains(c)) {
            0.5
        } else {
            0.0
        };
    }

    let a_bigrams = bigrams(&a_chars);
    let mut b_bigrams = bigrams(&b_chars);
    let mut intersection = 0usize;

    for gram in a_bigrams.iter() {
        if let Some(index) = b_bigrams.iter().position(|item| item == gram) {
            intersection += 1;
            b_bigrams.remove(index);
        }
    }

    (2.0 * intersection as f64) / ((a_bigrams.len() + bigrams(&b_chars).len()) as f64)
}

fn bigrams(chars: &[char]) -> Vec<(char, char)> {
    chars.windows(2).map(|w| (w[0], w[1])).collect()
}

fn merge_lrc(original: Option<&str>, translated: Option<&str>) -> Option<String> {
    let original = original?;
    let translated = translated.unwrap_or_default();

    if translated.trim().is_empty() {
        return Some(original.to_string());
    }

    let original_lines = lrc::parse_lrc_lines(original);
    let translated_lines = lrc::parse_lrc_lines(translated);

    if original_lines.is_empty() {
        return Some(translated.to_string());
    }

    let mut merged = String::new();
    for (time, text) in original_lines {
        let mut text = text;
        if let Some(translated_text) = translated_lines.get(&time) {
            if !translated_text.trim().is_empty() && translated_text.trim() != text.trim() {
                text = format!("{} / {}", text.trim(), translated_text.trim());
            }
        }
        merged.push_str(&format!("[{}]{}\n", lrc::format_lrc_time(time), text));
    }

    Some(merged)
}
