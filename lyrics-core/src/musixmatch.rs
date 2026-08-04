use crate::lrc;
use crate::{
    begin_lookup_cancellation, check_lookup_cancelled, normalize_optional_lrc, NativeResult,
};
use musixmatch_inofficial::models::{
    SortOrder, Subtitle, SubtitleFormat, Track, TrackId, TranslationList,
};
use musixmatch_inofficial::Musixmatch;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::OnceLock;

static MUSIXMATCH_CLIENT: OnceLock<Musixmatch> = OnceLock::new();

const MUSIXMATCH_LOOKUP_TIMEOUT_SECS: u64 = 8;

#[derive(Clone, Debug)]
struct ScoredTrack {
    track: Track,
    score: f64,
}

pub(crate) fn fetch_best_lyrics(
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    translation_language: &str,
    lookup_id: jni::sys::jlong,
    _reserved: bool,
) -> Result<NativeResult, String> {
    let _cancellation_guard = begin_lookup_cancellation(lookup_id)?;
    check_lookup_cancelled(lookup_id)?;

    if title.trim().is_empty() {
        return Err("empty title".into());
    }

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("failed to create tokio runtime: {e}"))?;

    runtime.block_on(async move {
        tokio::time::timeout(std::time::Duration::from_secs(MUSIXMATCH_LOOKUP_TIMEOUT_SECS), async move {
            let client = get_client()?;
            let title = clean_query_part(title);
            let artist = clean_query_part(artist);
            let album = clean_query_part(album);
            let duration_seconds = duration_ms
                .filter(|value| *value > 0)
                .map(|value| value as f32 / 1000.0);
            let translation_language = normalize_translation_language(translation_language);

            let (track, candidate_debug) =
                find_best_track(&client, &title, &artist, &album, duration_ms, lookup_id).await?;

            check_lookup_cancelled(lookup_id)?;
            let (subtitle, subtitle_debug) =
                match fetch_subtitle_for_track(&client, &track, duration_seconds, lookup_id).await {
                    Ok(value) => value,
                    Err(candidate_error) => {
                        check_lookup_cancelled(lookup_id)?;
                        // Keep the old matcher as a final fallback. Some Musixmatch tracks can be
                        // found by matcher.subtitle even when track.search gives an ID whose subtitle
                        // endpoint refuses the request.
                        match fetch_lrc_with_matcher_fallback(
                            &client,
                            &title,
                            &artist,
                            duration_seconds,
                            lookup_id,
                        )
                        .await
                        {
                            Ok(subtitle) => (
                                subtitle,
                                format!(
                                    "track subtitle failed: {candidate_error}; matcher fallback succeeded"
                                ),
                            ),
                            Err(matcher_error) => {
                                return Err(format!(
                                    "musixmatch found candidates but no usable subtitle; query=title='{title}' artist='{artist}' durationMs={}; candidates=[{}]; subtitleErrors=[{candidate_error}; matcherFallback={matcher_error}]",
                                    duration_ms.unwrap_or_default(),
                                    candidate_debug,
                                ));
                            }
                        }
                    }
                };

            let lrc = normalize_optional_lrc(subtitle.subtitle_body).ok_or_else(|| {
                format!(
                    "musixmatch returned empty subtitle; selectedTrack={} - {} ({}) subtitleId={} debug={subtitle_debug}",
                    track.track_name, track.artist_name, track.track_id, subtitle.subtitle_id,
                )
            })?;

            let translated_lrc = if translation_language.is_empty() {
                None
            } else {
                check_lookup_cancelled(lookup_id)?;
                match fetch_translation_for_track(&client, &track, &translation_language, lookup_id).await {
                    Ok(translation_list) => translation_list_to_lrc(&lrc, &translation_list),
                    Err(error) => {
                        eprintln!(
                            "AirLyricsLyrics: Musixmatch translation failed lang={} track={} common={} error={}",
                            translation_language, track.track_id, track.commontrack_id, error
                        );
                        None
                    }
                }
            };

            let merged_lrc = merge_lrc_like_netease(&lrc, translated_lrc.as_deref())
                .unwrap_or_else(|| lrc.clone());

            Ok(NativeResult {
                ok: true,
                source: "musixmatch-rust",
                id: Some(track.track_id.to_string()),
                title: Some(track.track_name),
                artist: Some(track.artist_name),
                album: if track.album_name.is_empty() {
                    None
                } else {
                    Some(track.album_name)
                },
                duration_ms: Some((track.track_length as u64) * 1000),
                lrc: Some(lrc),
                translated_lrc,
                merged_lrc: Some(merged_lrc),
                word_by_word_json: None,
                error_type: None,
                error: None,
            })
        })
        .await
        .map_err(|_| "musixmatch lookup timed out".to_string())?
    })
}

async fn find_best_track(
    client: &Musixmatch,
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
) -> Result<(Track, String), String> {
    let mut candidates = Vec::<Track>::new();
    let mut seen_track_ids = HashSet::<u64>::new();
    let mut search_errors = Vec::<String>::new();

    for attempt in build_track_search_attempts(title, artist) {
        check_lookup_cancelled(lookup_id)?;
        match attempt.search(client).await {
            Ok(tracks) => {
                check_lookup_cancelled(lookup_id)?;
                for track in tracks {
                    if seen_track_ids.insert(track.track_id) {
                        candidates.push(track);
                    }
                }
            }
            Err(error) => search_errors.push(format!("{}: {}", attempt.label, error)),
        }
    }

    // matcher.track is often stricter, but it can return a clean single match when
    // track.search is too broad or unordered.
    if !title.is_empty() || !artist.is_empty() {
        check_lookup_cancelled(lookup_id)?;
        match client
            .matcher_track(title, artist, album, false, false, false)
            .await
        {
            Ok(track) => {
                check_lookup_cancelled(lookup_id)?;
                if seen_track_ids.insert(track.track_id) {
                    candidates.push(track);
                }
            }
            Err(error) => search_errors.push(format!("matcher.track: {error}")),
        }
    }

    if candidates.is_empty() {
        let errors = if search_errors.is_empty() {
            "none".to_string()
        } else {
            search_errors.join(" | ")
        };
        return Err(format!(
            "musixmatch track search returned no candidates; query=title='{title}' artist='{artist}' album='{album}' durationMs={}; errors=[{errors}]",
            duration_ms.unwrap_or_default(),
        ));
    }

    let mut scored = candidates
        .into_iter()
        .map(|track| ScoredTrack {
            score: score_track(&track, title, artist, album, duration_ms),
            track,
        })
        .collect::<Vec<_>>();

    scored.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    let debug = scored
        .iter()
        .take(8)
        .map(|item| describe_track(&item.track, item.score))
        .collect::<Vec<_>>()
        .join(" | ");

    let best = scored
        .into_iter()
        .next()
        .ok_or_else(|| "musixmatch candidate scoring failed".to_string())?;

    Ok((best.track, debug))
}

struct SearchAttempt {
    label: String,
    title: Option<String>,
    artist: Option<String>,
    track_artist: Option<String>,
    q: Option<String>,
    require_lyrics: bool,
}

impl SearchAttempt {
    async fn search(&self, client: &Musixmatch) -> Result<Vec<Track>, String> {
        let mut query = client.track_search().s_track_rating(SortOrder::Desc);
        if let Some(title) = self.title.as_deref() {
            query = query.q_track(title);
        }
        if let Some(artist) = self.artist.as_deref() {
            query = query.q_artist(artist);
        }
        if let Some(track_artist) = self.track_artist.as_deref() {
            query = query.q_track_artist(track_artist);
        }
        if let Some(q) = self.q.as_deref() {
            query = query.q(q);
        }
        if self.require_lyrics {
            query = query.f_has_lyrics();
        }
        query.send(12, 1).await.map_err(|error| error.to_string())
    }
}

fn build_track_search_attempts(title: &str, artist: &str) -> Vec<SearchAttempt> {
    let joined = [title, artist]
        .iter()
        .filter(|part| !part.trim().is_empty())
        .copied()
        .collect::<Vec<_>>()
        .join(" ");
    let title_relaxed = relax_title(title);

    let mut attempts = Vec::new();
    if !title.is_empty() && !artist.is_empty() {
        attempts.push(SearchAttempt {
            label: "q_track+q_artist+lyrics".to_string(),
            title: Some(title.to_string()),
            artist: Some(artist.to_string()),
            track_artist: None,
            q: None,
            require_lyrics: true,
        });
    }
    if !title_relaxed.is_empty() && title_relaxed != title && !artist.is_empty() {
        attempts.push(SearchAttempt {
            label: "relaxedTitle+q_artist+lyrics".to_string(),
            title: Some(title_relaxed.clone()),
            artist: Some(artist.to_string()),
            track_artist: None,
            q: None,
            require_lyrics: true,
        });
    }
    if !joined.is_empty() {
        attempts.push(SearchAttempt {
            label: "q_track_artist+lyrics".to_string(),
            title: None,
            artist: None,
            track_artist: Some(joined.clone()),
            q: None,
            require_lyrics: true,
        });
        attempts.push(SearchAttempt {
            label: "q+lyrics".to_string(),
            title: None,
            artist: None,
            track_artist: None,
            q: Some(joined),
            require_lyrics: true,
        });
    }
    if !title.is_empty() {
        attempts.push(SearchAttempt {
            label: "q_track_only+lyrics".to_string(),
            title: Some(title.to_string()),
            artist: None,
            track_artist: None,
            q: None,
            require_lyrics: true,
        });
        attempts.push(SearchAttempt {
            label: "q_track_only".to_string(),
            title: Some(title.to_string()),
            artist: None,
            track_artist: None,
            q: None,
            require_lyrics: false,
        });
    }
    attempts
}

async fn fetch_subtitle_for_track(
    client: &Musixmatch,
    track: &Track,
    duration_seconds: Option<f32>,
    lookup_id: jni::sys::jlong,
) -> Result<(Subtitle, String), String> {
    let track_id = TrackId::TrackId(track.track_id);
    let mut errors = Vec::<String>::new();

    let duration_attempts = [
        (duration_seconds, Some(12.0_f32), "trackId+lrc+duration"),
        (None, None, "trackId+lrc"),
    ];

    for (duration, deviation, label) in duration_attempts {
        check_lookup_cancelled(lookup_id)?;
        match client
            .track_subtitle(track_id.clone(), SubtitleFormat::Lrc, duration, deviation)
            .await
        {
            Ok(subtitle) => {
                check_lookup_cancelled(lookup_id)?;
                return Ok((subtitle, label.to_string()));
            }
            Err(error) => errors.push(format!("{label}: {error}")),
        }
    }

    if track.commontrack_id != 0 {
        let commontrack_id = TrackId::Commontrack(track.commontrack_id);
        check_lookup_cancelled(lookup_id)?;
        match client
            .track_subtitle(commontrack_id, SubtitleFormat::Lrc, None, None)
            .await
        {
            Ok(subtitle) => {
                check_lookup_cancelled(lookup_id)?;
                return Ok((subtitle, "commontrackId+lrc".to_string()));
            }
            Err(error) => errors.push(format!("commontrackId+lrc: {error}")),
        }
    }

    Err(errors.join("; "))
}

async fn fetch_lrc_with_matcher_fallback(
    client: &Musixmatch,
    title: &str,
    artist: &str,
    duration_seconds: Option<f32>,
    lookup_id: jni::sys::jlong,
) -> Result<Subtitle, String> {
    if let Some(duration) = duration_seconds {
        check_lookup_cancelled(lookup_id)?;
        match client
            .matcher_subtitle(
                title,
                artist,
                SubtitleFormat::Lrc,
                Some(duration),
                Some(12.0),
            )
            .await
        {
            Ok(subtitle) => {
                check_lookup_cancelled(lookup_id)?;
                Ok(subtitle)
            }
            Err(first_error) => {
                check_lookup_cancelled(lookup_id)?;
                let subtitle = client
                    .matcher_subtitle(title, artist, SubtitleFormat::Lrc, None, None)
                    .await
                    .map_err(|second_error| {
                    format!(
                        "matcher.subtitle duration failed: {first_error}; matcher.subtitle no-duration failed: {second_error}"
                    )
                    })?;
                check_lookup_cancelled(lookup_id)?;
                Ok(subtitle)
            }
        }
    } else {
        check_lookup_cancelled(lookup_id)?;
        let subtitle = client
            .matcher_subtitle(title, artist, SubtitleFormat::Lrc, None, None)
            .await
            .map_err(|error| format!("matcher.subtitle failed: {error}"))?;
        check_lookup_cancelled(lookup_id)?;
        Ok(subtitle)
    }
}

async fn fetch_translation_for_track(
    client: &Musixmatch,
    track: &Track,
    language: &str,
    lookup_id: jni::sys::jlong,
) -> Result<TranslationList, String> {
    let mut errors = Vec::<String>::new();

    check_lookup_cancelled(lookup_id)?;
    match client
        .track_lyrics_translation(TrackId::TrackId(track.track_id), language)
        .await
    {
        Ok(list) if !list.is_empty() => {
            check_lookup_cancelled(lookup_id)?;
            return Ok(list);
        }
        Ok(_) => errors.push(format!("trackId+translation({language}): empty")),
        Err(error) => errors.push(format!("trackId+translation({language}): {error}")),
    }

    if track.commontrack_id != 0 {
        check_lookup_cancelled(lookup_id)?;
        match client
            .track_lyrics_translation(TrackId::Commontrack(track.commontrack_id), language)
            .await
        {
            Ok(list) if !list.is_empty() => {
                check_lookup_cancelled(lookup_id)?;
                return Ok(list);
            }
            Ok(_) => errors.push(format!("commontrackId+translation({language}): empty")),
            Err(error) => errors.push(format!("commontrackId+translation({language}): {error}")),
        }
    }

    Err(errors.join("; "))
}

fn translation_list_to_lrc(
    original_lrc: &str,
    translation_list: &TranslationList,
) -> Option<String> {
    if translation_list.is_empty() {
        return None;
    }

    let original_lines = lrc::parse_lrc_entries(original_lrc);
    if original_lines.is_empty() {
        return None;
    }

    let mut translations = HashMap::<String, VecDeque<String>>::new();
    for line in &translation_list.lines {
        let matched = normalize_translation_match_key(&line.matched_line);
        let translated = clean_lrc_text(&line.description);
        if matched.is_empty() || translated.is_empty() {
            continue;
        }
        translations
            .entry(matched)
            .or_default()
            .push_back(translated);
    }

    if translations.is_empty() {
        return None;
    }

    let mut output = String::new();
    let mut matched_count = 0_usize;
    for (time_ms, text) in original_lines {
        let key = normalize_translation_match_key(&text);
        let translated = translations
            .get_mut(&key)
            .and_then(|items| items.pop_front());

        if let Some(translated) = translated {
            if !translated.trim().is_empty() {
                output.push_str(&format!(
                    "[{}]{}\n",
                    lrc::format_lrc_time(time_ms),
                    translated.trim()
                ));
                matched_count += 1;
            }
        }
    }

    if matched_count == 0 || output.trim().is_empty() {
        None
    } else {
        Some(output)
    }
}

fn merge_lrc_like_netease(original_lrc: &str, translated_lrc: Option<&str>) -> Option<String> {
    let translated_lrc = translated_lrc.unwrap_or_default();
    if translated_lrc.trim().is_empty() {
        return Some(original_lrc.to_string());
    }

    let original_lines = lrc::parse_lrc_entries(original_lrc);
    let translated_lines = lrc::parse_lrc_entries(translated_lrc)
        .into_iter()
        .collect::<HashMap<_, _>>();

    if original_lines.is_empty() {
        return Some(original_lrc.to_string());
    }

    let mut merged = String::new();
    for (time_ms, text) in original_lines {
        let merged_text = if let Some(translated) = translated_lines.get(&time_ms) {
            if !translated.trim().is_empty() && translated.trim() != text.trim() {
                format!("{} / {}", text.trim(), translated.trim())
            } else {
                text.trim().to_string()
            }
        } else {
            text.trim().to_string()
        };
        merged.push_str(&format!(
            "[{}]{}\n",
            lrc::format_lrc_time(time_ms),
            merged_text
        ));
    }

    Some(merged)
}

fn normalize_translation_language(value: &str) -> String {
    let normalized = value.trim().to_lowercase();
    match normalized.as_str() {
        "" | "none" | "off" | "disabled" => "".to_string(),
        "zh-cn" | "zh_hans" | "zh-hans" | "cn" => "zh".to_string(),
        "en-us" | "en-gb" => "en".to_string(),
        other => other.chars().take(2).collect(),
    }
}

fn normalize_translation_match_key(value: &str) -> String {
    clean_lrc_text(value)
        .to_lowercase()
        .chars()
        .filter(|ch| !ch.is_whitespace() && !ch.is_ascii_punctuation())
        .collect()
}

fn clean_lrc_text(value: &str) -> String {
    value
        .replace(['\n', '\r', '\t'], " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn get_client() -> Result<Musixmatch, String> {
    if let Some(client) = MUSIXMATCH_CLIENT.get() {
        return Ok(client.clone());
    }

    let client = Musixmatch::builder()
        .no_storage()
        .build()
        .map_err(|e| format!("failed to create musixmatch client: {e}"))?;

    let _ = MUSIXMATCH_CLIENT.set(client.clone());
    Ok(client)
}

fn score_track(
    track: &Track,
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
) -> f64 {
    let mut score = 0.0;
    let query_title = normalize_for_score(title);
    let query_artist = normalize_for_score(artist);
    let query_album = normalize_for_score(album);
    let track_title = normalize_for_score(&track.track_name);
    let track_artist = normalize_for_score(&track.artist_name);
    let track_album = normalize_for_score(&track.album_name);

    score += text_similarity_score(&query_title, &track_title, 55.0);
    score += text_similarity_score(&query_artist, &track_artist, 25.0);
    if !query_album.is_empty() {
        score += text_similarity_score(&query_album, &track_album, 8.0);
    }

    if let Some(duration) = duration_ms {
        let track_duration = (track.track_length as i64) * 1000;
        let delta = (track_duration - duration as i64).abs();
        score += if delta <= 3_000 {
            12.0
        } else if delta <= 8_000 {
            8.0
        } else if delta <= 15_000 {
            4.0
        } else if delta <= 30_000 {
            1.0
        } else {
            -8.0
        };
    }

    if track.has_subtitles {
        score += 15.0;
    }
    if track.has_lyrics {
        score += 5.0;
    }
    if track.instrumental {
        score -= 15.0;
    }
    score += (track.track_rating as f64).min(100.0) / 20.0;
    score
}

fn text_similarity_score(query: &str, value: &str, weight: f64) -> f64 {
    if query.is_empty() || value.is_empty() {
        return 0.0;
    }
    if query == value {
        return weight;
    }
    if value.contains(query) || query.contains(value) {
        return weight * 0.72;
    }

    let query_tokens = query.split_whitespace().collect::<Vec<_>>();
    let value_tokens = value.split_whitespace().collect::<HashSet<_>>();
    if query_tokens.is_empty() || value_tokens.is_empty() {
        return 0.0;
    }
    let matched = query_tokens
        .iter()
        .filter(|token| value_tokens.contains(**token))
        .count();
    weight * (matched as f64 / query_tokens.len() as f64) * 0.55
}

fn describe_track(track: &Track, score: f64) -> String {
    format!(
        "id={} common={} score={:.1} title='{}' artist='{}' album='{}' len={}s hasLyrics={} hasSubtitles={} rating={}",
        track.track_id,
        track.commontrack_id,
        score,
        track.track_name,
        track.artist_name,
        track.album_name,
        track.track_length,
        track.has_lyrics,
        track.has_subtitles,
        track.track_rating,
    )
}

fn clean_query_part(value: &str) -> String {
    value
        .replace(['\n', '\r', '\t'], " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn relax_title(value: &str) -> String {
    let mut result = String::new();
    let mut depth = 0_u32;
    for ch in value.chars() {
        match ch {
            '(' | '[' | '（' | '【' => depth += 1,
            ')' | ']' | '）' | '】' => depth = depth.saturating_sub(1),
            _ if depth == 0 => result.push(ch),
            _ => {}
        }
    }
    clean_query_part(&result)
}

fn normalize_for_score(value: &str) -> String {
    let relaxed = relax_title(value);
    relaxed
        .to_lowercase()
        .chars()
        .map(|ch| {
            if ch.is_alphanumeric() || ch.is_whitespace() {
                ch
            } else {
                ' '
            }
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}
