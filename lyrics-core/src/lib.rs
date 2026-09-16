use diagnostics::LookupTrace;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
#[cfg(test)]
use matching::EARLY_ACCEPT_SCORE;
use matching::{
    build_search_queries, is_confident_early_match, normalize_usable_lrc, score_candidate,
    MatchMetadata,
};
use ncmapi::types::{Album, Artist, LyricResp, SearchSongResp, Song};
use ncmapi::NcmApi;
use serde::Serialize;
use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::pin::Pin;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use unicode_normalization::UnicodeNormalization;

mod diagnostics;
mod lrc;
mod lrclib;
mod matching;
mod musixmatch;
#[cfg(test)]
mod native_result_contract_tests;

const NETEASE_LOOKUP_TIMEOUT: Duration = Duration::from_secs(12);
const NETEASE_MAX_REQUESTS: usize = 8;
const NETEASE_MAX_LYRIC_CANDIDATES: usize = 3;
const NETEASE_EARLY_LYRIC_CANDIDATES: usize = 2;

static LOOKUP_CANCELLATIONS: OnceLock<Mutex<HashMap<i64, bool>>> = OnceLock::new();

#[derive(Debug, Clone)]
struct Candidate {
    id: usize,
    title: String,
    artist: String,
    album: String,
    duration_ms: u64,
    score: f64,
    title_score: f64,
    artist_score: Option<f64>,
    confident_early_match: bool,
}

#[derive(Default)]
struct CandidateBatchStats {
    returned: usize,
    accepted: usize,
    duplicate: usize,
    rejected_title: usize,
    best_score: Option<f64>,
    best_title_score: Option<f64>,
    best_artist_score: Option<f64>,
    best_confident_early_match: bool,
}

#[derive(Debug)]
struct CandidateLyrics {
    lrc: Option<String>,
    translated_lrc: Option<String>,
    merged_lrc: String,
}

#[derive(Debug, Serialize)]
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
    lookup_id: jni::sys::jlong,
) -> jstring {
    fetch_netease_lyrics_json(env, this, title, artist, album, duration_ms, lookup_id)
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
    lookup_id: jni::sys::jlong,
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
            lookup_id,
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

#[no_mangle]
pub extern "system" fn Java_com_andsi_airlyrics_lyrics_providers_LrclibLyricsNative_fetchBestLyricsJson(
    mut env: JNIEnv,
    _this: JObject,
    title: JString,
    artist: JString,
    album: JString,
    duration_ms: jni::sys::jlong,
    lookup_id: jni::sys::jlong,
) -> jstring {
    let title = jstring_to_string(&mut env, title);
    let artist = jstring_to_string(&mut env, artist);
    let album = jstring_to_string(&mut env, album);
    let duration_ms = if duration_ms > 0 {
        Some(duration_ms as u64)
    } else {
        None
    };

    let result = std::panic::catch_unwind(|| {
        lrclib::fetch_best_lyrics(&title, &artist, &album, duration_ms, lookup_id)
    })
    .unwrap_or_else(|_| {
        Err(lrclib::LrclibError::native(
            "native panic while fetching LRCLIB lyrics",
        ))
    });

    let json = match result {
        Ok(value) => serde_json::to_string(&value).unwrap_or_else(|_| {
            fallback_error(
                "lrclib-rust",
                "SerializeError",
                "failed to serialize native result",
            )
        }),
        Err(err) => fallback_error("lrclib-rust", err.error_type(), err.message()),
    };

    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_andsi_airlyrics_lyrics_providers_LyricsNativeCancellation_cancelLookup(
    _env: JNIEnv,
    _this: JObject,
    lookup_id: jni::sys::jlong,
) {
    cancel_lookup(lookup_id);
}

#[no_mangle]
pub extern "system" fn Java_com_andsi_airlyrics_lyrics_providers_LyricsNativeCancellation_clearLookup(
    _env: JNIEnv,
    _this: JObject,
    lookup_id: jni::sys::jlong,
) {
    clear_lookup(lookup_id);
}

fn fetch_netease_lyrics_json(
    mut env: JNIEnv,
    _this: JObject,
    title: JString,
    artist: JString,
    album: JString,
    duration_ms: jni::sys::jlong,
    lookup_id: jni::sys::jlong,
) -> jstring {
    let title = jstring_to_string(&mut env, title);
    let artist = jstring_to_string(&mut env, artist);
    let album = jstring_to_string(&mut env, album);
    let duration_ms = if duration_ms > 0 {
        Some(duration_ms as u64)
    } else {
        None
    };

    let result = std::panic::catch_unwind(|| {
        fetch_best_lyrics(&title, &artist, &album, duration_ms, lookup_id)
    })
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
        error_type: Some(error_type),
        error: Some(message.to_string()),
    })
    .unwrap_or_else(|_| format!(r#"{{"ok":false,"source":"{source}","error_type":"SerializeError","error":"unknown native error"}}"#))
}

fn classify_error(message: &str) -> &'static str {
    let lower = message.to_lowercase();
    if lower.contains("missingcredentials")
        || lower.contains("credential")
        || lower.contains("token expired")
        || lower.contains("invalid token")
        || lower.contains("missing token")
        || lower.contains("access token")
        || lower.contains("token required")
    {
        "NeedCredential"
    } else if lower.contains("rate limit")
        || lower.contains("rate limited")
        || lower.contains("ratelimit")
        || lower.contains("429")
    {
        "RateLimited"
    } else if lower.contains("restricted") || lower.contains("copyright") {
        "RestrictedLyrics"
    } else if lower.contains("lookup canceled")
        || lower.contains("lookup cancelled")
        || lower.contains("native panic")
        || lower.contains("registry poisoned")
        || lower.contains("failed to create tokio runtime")
    {
        "NativeError"
    } else if lower.contains("network")
        || lower.contains("timeout")
        || lower.contains("timed out")
        || lower.contains("failed to connect")
        || lower.contains("dns")
    {
        "NetworkError"
    } else if lower.contains("parse") || lower.contains("decode") || lower.contains("deserialize") {
        "ParseError"
    } else if lower.contains("no subtitle")
        || lower.contains("no usable subtitle")
        || lower.contains("no candidates")
        || lower.contains("not found")
        || lower.contains("could not be found")
        || lower.contains("empty title")
        || lower.contains("404")
    {
        "NotFound"
    } else {
        "Unknown"
    }
}

pub(crate) struct LookupCancellationGuard {
    lookup_id: i64,
}

impl Drop for LookupCancellationGuard {
    fn drop(&mut self) {
        clear_lookup(self.lookup_id);
    }
}

pub(crate) fn begin_lookup_cancellation(
    lookup_id: jni::sys::jlong,
) -> Result<LookupCancellationGuard, String> {
    if lookup_id <= 0 {
        return Ok(LookupCancellationGuard { lookup_id: 0 });
    }

    let mut cancellations = lookup_cancellations()
        .lock()
        .map_err(|_| "native cancellation registry poisoned".to_string())?;
    let canceled = *cancellations.entry(lookup_id).or_insert(false);
    if canceled {
        cancellations.remove(&lookup_id);
        return Err("lookup canceled".to_string());
    }

    Ok(LookupCancellationGuard { lookup_id })
}

pub(crate) fn check_lookup_cancelled(lookup_id: jni::sys::jlong) -> Result<(), String> {
    if lookup_id <= 0 {
        return Ok(());
    }

    let cancellations = lookup_cancellations()
        .lock()
        .map_err(|_| "native cancellation registry poisoned".to_string())?;
    if cancellations.get(&lookup_id).copied().unwrap_or(false) {
        Err("lookup canceled".to_string())
    } else {
        Ok(())
    }
}

fn cancel_lookup(lookup_id: jni::sys::jlong) {
    if lookup_id <= 0 {
        return;
    }

    if let Ok(mut cancellations) = lookup_cancellations().lock() {
        cancellations.insert(lookup_id, true);
    }
}

fn clear_lookup(lookup_id: jni::sys::jlong) {
    if lookup_id <= 0 {
        return;
    }

    if let Ok(mut cancellations) = lookup_cancellations().lock() {
        cancellations.remove(&lookup_id);
    }
}

fn lookup_cancellations() -> &'static Mutex<HashMap<i64, bool>> {
    LOOKUP_CANCELLATIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

trait NeteaseBackend {
    fn search_songs<'a>(
        &'a self,
        keyword: &'a str,
    ) -> Pin<Box<dyn Future<Output = Result<SearchSongResp, String>> + 'a>>;

    fn fetch_lyrics(
        &self,
        song_id: usize,
    ) -> Pin<Box<dyn Future<Output = Result<LyricResp, String>> + '_>>;
}

impl NeteaseBackend for NcmApi {
    fn search_songs<'a>(
        &'a self,
        keyword: &'a str,
    ) -> Pin<Box<dyn Future<Output = Result<SearchSongResp, String>> + 'a>> {
        Box::pin(async move {
            let response = self
                .search(keyword, None)
                .await
                .map_err(|error| format!("netease network error during search request: {error}"))?;
            response
                .deserialize()
                .map_err(|error| format!("netease parse error decoding search response: {error}"))
        })
    }

    fn fetch_lyrics(
        &self,
        song_id: usize,
    ) -> Pin<Box<dyn Future<Output = Result<LyricResp, String>> + '_>> {
        Box::pin(async move {
            let response = self.lyric(song_id).await.map_err(|error| {
                format!("netease network error during lyric request for {song_id}: {error}")
            })?;
            response.deserialize().map_err(|error| {
                format!("netease parse error decoding lyric response for {song_id}: {error}")
            })
        })
    }
}

#[derive(Default)]
struct CandidatePool {
    candidates: HashMap<usize, Candidate>,
    attempted_ids: HashSet<usize>,
}

impl CandidatePool {
    fn add_songs(
        &mut self,
        songs: &[Song],
        target_title: &str,
        target_artist: &str,
        target_album: &str,
        target_duration_ms: Option<u64>,
    ) -> CandidateBatchStats {
        let mut stats = CandidateBatchStats {
            returned: songs.len(),
            ..CandidateBatchStats::default()
        };
        for song in songs {
            let Some(candidate) = map_song(
                song,
                target_title,
                target_artist,
                target_album,
                target_duration_ms,
            ) else {
                stats.rejected_title += 1;
                continue;
            };

            stats.accepted += 1;
            if stats.best_score.is_none_or(|score| candidate.score > score) {
                stats.best_score = Some(candidate.score);
                stats.best_title_score = Some(candidate.title_score);
                stats.best_artist_score = candidate.artist_score;
                stats.best_confident_early_match = candidate.confident_early_match;
            }
            if self.candidates.contains_key(&candidate.id) {
                stats.duplicate += 1;
            }

            self.candidates
                .entry(candidate.id)
                .and_modify(|current| {
                    if candidate.score > current.score {
                        *current = candidate.clone();
                    }
                })
                .or_insert(candidate);
        }
        stats
    }

    fn take_best(&mut self, early_only: bool) -> Option<Candidate> {
        let candidate = self
            .candidates
            .values()
            .filter(|candidate| !self.attempted_ids.contains(&candidate.id))
            .filter(|candidate| !early_only || candidate.confident_early_match)
            .max_by(|left, right| {
                left.score
                    .total_cmp(&right.score)
                    .then_with(|| right.id.cmp(&left.id))
            })
            .cloned()?;
        self.attempted_ids.insert(candidate.id);
        Some(candidate)
    }

    fn attempted_count(&self) -> usize {
        self.attempted_ids.len()
    }

    fn len(&self) -> usize {
        self.candidates.len()
    }
}

struct RequestBudget {
    remaining: usize,
}

impl RequestBudget {
    fn new(limit: usize) -> Self {
        Self { remaining: limit }
    }

    fn consume(&mut self) -> bool {
        if self.remaining == 0 {
            return false;
        }
        self.remaining -= 1;
        true
    }

    fn used(&self) -> usize {
        NETEASE_MAX_REQUESTS.saturating_sub(self.remaining)
    }
}

fn fetch_best_lyrics(
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
) -> Result<NativeResult, String> {
    let api = NcmApi::new(false, "");
    fetch_best_lyrics_with_backend(
        &api,
        title,
        artist,
        album,
        duration_ms,
        lookup_id,
        NETEASE_LOOKUP_TIMEOUT,
    )
}

fn fetch_best_lyrics_with_backend<B: NeteaseBackend>(
    backend: &B,
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
    timeout: Duration,
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
    let trace = LookupTrace::new("netease");

    runtime.block_on(async move {
        match tokio::time::timeout(
            timeout,
            fetch_netease_from_backend(
                backend,
                title,
                artist,
                album,
                duration_ms,
                lookup_id,
                &trace,
            ),
        )
        .await
        {
            Ok(result) => result,
            Err(_) => {
                trace.event("lookup_stop", "reason=timeout");
                Err("netease lookup timed out".to_string())
            }
        }
    })
}

async fn fetch_netease_from_backend<B: NeteaseBackend>(
    backend: &B,
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
    trace: &LookupTrace,
) -> Result<NativeResult, String> {
    let title = clean_query_part(title);
    let artist = clean_query_part(artist);
    let album = clean_query_part(album);
    let duration_ms = duration_ms.filter(|value| *value > 0);
    let keywords = build_search_keywords(&title, &artist, &album);
    let mut candidates = CandidatePool::default();
    let mut request_budget = RequestBudget::new(NETEASE_MAX_REQUESTS);
    let mut early_attempts = 0_usize;
    let mut errors = Vec::new();
    trace.event(
        "lookup_start",
        format_args!(
            "queryVariants={} artistPresent={} albumPresent={} durationPresent={}",
            keywords.len(),
            !artist.is_empty(),
            !album.is_empty(),
            duration_ms.is_some(),
        ),
    );

    for (round, keyword) in keywords.into_iter().enumerate() {
        check_lookup_cancelled(lookup_id)?;
        if !request_budget.consume() {
            break;
        }

        let request_started = Instant::now();
        match await_with_cancellation(backend.search_songs(&keyword), lookup_id).await? {
            Ok(response) if response.code == 200 => {
                let stats = response
                    .result
                    .map_or_else(CandidateBatchStats::default, |result| {
                        candidates.add_songs(&result.songs, &title, &artist, &album, duration_ms)
                    });
                trace.event(
                    "search_result",
                    format_args!(
                        "round={} requestMs={} returned={} accepted={} rejectedTitle={} duplicates={} pool={} bestScore={:.3} bestTitleScore={:.3} bestArtistScore={} bestEarly={} requestsUsed={}",
                        round + 1,
                        request_started.elapsed().as_millis(),
                        stats.returned,
                        stats.accepted,
                        stats.rejected_title,
                        stats.duplicate,
                        candidates.len(),
                        stats.best_score.unwrap_or_default(),
                        stats.best_title_score.unwrap_or_default(),
                        stats
                            .best_artist_score
                            .map(|score| format!("{score:.3}"))
                            .unwrap_or_else(|| "missing".to_string()),
                        stats.best_confident_early_match,
                        request_budget.used(),
                    ),
                );
            }
            Ok(response) if response.code == 404 => trace.event(
                "search_miss",
                format_args!(
                    "round={} requestMs={} requestsUsed={}",
                    round + 1,
                    request_started.elapsed().as_millis(),
                    request_budget.used(),
                ),
            ),
            Ok(response) => {
                trace.event(
                    "search_error",
                    format_args!(
                        "round={} code={} requestMs={} requestsUsed={}",
                        round + 1,
                        response.code,
                        request_started.elapsed().as_millis(),
                        request_budget.used(),
                    ),
                );
                errors.push(netease_api_code_error("search", response.code));
            }
            Err(error) => {
                trace.event(
                    "search_error",
                    format_args!(
                        "round={} kind={} requestMs={} requestsUsed={}",
                        round + 1,
                        classify_error(&error),
                        request_started.elapsed().as_millis(),
                        request_budget.used(),
                    ),
                );
                errors.push(error);
            }
        }

        while early_attempts < NETEASE_EARLY_LYRIC_CANDIDATES
            && candidates.attempted_count() < NETEASE_MAX_LYRIC_CANDIDATES
            && request_budget.remaining > 0
        {
            let Some(candidate) = candidates.take_best(true) else {
                break;
            };
            early_attempts += 1;
            request_budget.consume();
            let score = candidate.score;
            let title_score = candidate.title_score;
            let early_confident = candidate.confident_early_match;
            let request_started = Instant::now();
            match fetch_candidate_lyrics(backend, candidate, lookup_id).await {
                Ok(Some(result)) => {
                    trace.event(
                        "lyrics_found",
                        format_args!(
                            "phase=early score={score:.3} titleScore={title_score:.3} early={early_confident} requestMs={} requestsUsed={}",
                            request_started.elapsed().as_millis(),
                            request_budget.used(),
                        ),
                    );
                    return Ok(result);
                }
                Ok(None) => trace.event(
                    "lyrics_miss",
                    format_args!(
                        "phase=early score={score:.3} titleScore={title_score:.3} early={early_confident} requestMs={} requestsUsed={}",
                        request_started.elapsed().as_millis(),
                        request_budget.used(),
                    ),
                ),
                Err(error) => {
                    trace.event(
                        "lyrics_error",
                        format_args!(
                            "phase=early score={score:.3} titleScore={title_score:.3} early={early_confident} kind={} requestMs={} requestsUsed={}",
                            classify_error(&error),
                            request_started.elapsed().as_millis(),
                            request_budget.used(),
                        ),
                    );
                    errors.push(error);
                }
            }
        }
    }

    // Keep one or more attempts for the best related results collected across all query rounds.
    // The common scorer has already rejected titles below the minimum relation threshold, so a
    // score below the early-accept threshold remains eligible here.
    while candidates.attempted_count() < NETEASE_MAX_LYRIC_CANDIDATES
        && request_budget.remaining > 0
    {
        let Some(candidate) = candidates.take_best(false) else {
            break;
        };
        request_budget.consume();
        let score = candidate.score;
        let title_score = candidate.title_score;
        let early_confident = candidate.confident_early_match;
        let request_started = Instant::now();
        match fetch_candidate_lyrics(backend, candidate, lookup_id).await {
            Ok(Some(result)) => {
                trace.event(
                    "lyrics_found",
                    format_args!(
                        "phase=final score={score:.3} titleScore={title_score:.3} early={early_confident} requestMs={} requestsUsed={}",
                        request_started.elapsed().as_millis(),
                        request_budget.used(),
                    ),
                );
                return Ok(result);
            }
            Ok(None) => trace.event(
                "lyrics_miss",
                format_args!(
                    "phase=final score={score:.3} titleScore={title_score:.3} early={early_confident} requestMs={} requestsUsed={}",
                    request_started.elapsed().as_millis(),
                    request_budget.used(),
                ),
            ),
            Err(error) => {
                trace.event(
                    "lyrics_error",
                    format_args!(
                        "phase=final score={score:.3} titleScore={title_score:.3} early={early_confident} kind={} requestMs={} requestsUsed={}",
                        classify_error(&error),
                        request_started.elapsed().as_millis(),
                        request_budget.used(),
                    ),
                );
                errors.push(error);
            }
        }
    }

    check_lookup_cancelled(lookup_id)?;
    if errors.is_empty() {
        trace.event(
            "lookup_stop",
            format_args!(
                "reason=not_found pool={} candidatesTried={} requestsUsed={}",
                candidates.len(),
                candidates.attempted_count(),
                request_budget.used(),
            ),
        );
        Err(
            "netease lyrics not found: no related candidate contained usable timed lyrics"
                .to_string(),
        )
    } else {
        trace.event(
            "lookup_stop",
            format_args!(
                "reason=error kind={} pool={} candidatesTried={} requestsUsed={}",
                classify_error(&errors.join(" | ")),
                candidates.len(),
                candidates.attempted_count(),
                request_budget.used(),
            ),
        );
        Err(format!("netease lookup incomplete: {}", errors.join(" | ")))
    }
}

async fn fetch_candidate_lyrics<B: NeteaseBackend>(
    backend: &B,
    candidate: Candidate,
    lookup_id: jni::sys::jlong,
) -> Result<Option<NativeResult>, String> {
    check_lookup_cancelled(lookup_id)?;
    let lyric_resp =
        await_with_cancellation(backend.fetch_lyrics(candidate.id), lookup_id).await??;
    check_lookup_cancelled(lookup_id)?;

    if lyric_resp.code == 404 {
        return Ok(None);
    }
    if lyric_resp.code != 200 {
        return Err(netease_api_code_error("lyric", lyric_resp.code));
    }
    let Some(lyrics) = candidate_lyrics(lyric_resp) else {
        return Ok(None);
    };

    Ok(Some(NativeResult {
        ok: true,
        source: "netease-rust",
        id: Some(candidate.id.to_string()),
        title: Some(candidate.title),
        artist: Some(candidate.artist),
        album: Some(candidate.album),
        duration_ms: Some(candidate.duration_ms),
        lrc: lyrics.lrc,
        translated_lrc: lyrics.translated_lrc,
        merged_lrc: Some(lyrics.merged_lrc),
        error_type: None,
        error: None,
    }))
}

async fn await_with_cancellation<F, T>(future: F, lookup_id: jni::sys::jlong) -> Result<T, String>
where
    F: Future<Output = T>,
{
    tokio::pin!(future);
    loop {
        tokio::select! {
            result = &mut future => return Ok(result),
            _ = tokio::time::sleep(Duration::from_millis(50)) => {
                check_lookup_cancelled(lookup_id)?;
            }
        }
    }
}

fn build_search_keywords(title: &str, artist: &str, album: &str) -> Vec<String> {
    let mut keywords = Vec::new();
    let title = clean_query_part(title);
    let artist = clean_query_part(artist);
    let album = clean_query_part(album);

    if !title.is_empty() && !artist.is_empty() && !album.is_empty() {
        keywords.push(format!("{title} {album} {artist}"));
    }

    for query in build_search_queries(&title, &artist) {
        let keyword = match query.artist {
            Some(artist) => format!("{} {artist}", query.title),
            None => query.title,
        };
        keywords.push(keyword);
    }

    let mut seen = HashSet::new();
    keywords
        .into_iter()
        .filter(|keyword| seen.insert(clean_query_part(keyword).to_lowercase()))
        .take(5)
        .collect()
}

fn clean_query_part(value: &str) -> String {
    value
        .nfkc()
        .collect::<String>()
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

    let match_score = score_candidate(
        MatchMetadata {
            title: target_title,
            artist: target_artist,
            album: target_album,
            duration_ms: target_duration_ms,
        },
        MatchMetadata {
            title: name,
            artist: &artist,
            album: album_name,
            duration_ms: Some(*duration as u64).filter(|value| *value > 0),
        },
    )?;

    Some(Candidate {
        id: *id,
        title: name.clone(),
        artist,
        album: album_name.to_string(),
        duration_ms: *duration as u64,
        score: match_score.total,
        title_score: match_score.title_similarity,
        artist_score: match_score.artist_similarity,
        confident_early_match: is_confident_early_match(&match_score),
    })
}

fn candidate_lyrics(lyric_resp: LyricResp) -> Option<CandidateLyrics> {
    let lrc = lyric_resp
        .lrc
        .and_then(|lyrics| normalize_usable_lrc(lyrics.lyric));
    let translated_lrc = lyric_resp
        .tlyric
        .and_then(|lyrics| normalize_usable_lrc(lyrics.lyric));
    let merged_lrc = merge_lrc(lrc.as_deref(), translated_lrc.as_deref())
        .or_else(|| lrc.clone())
        .or_else(|| translated_lrc.clone())?;
    let merged_lrc = normalize_usable_lrc(merged_lrc)?;

    Some(CandidateLyrics {
        lrc,
        translated_lrc,
        merged_lrc,
    })
}

fn netease_api_code_error(operation: &str, code: usize) -> String {
    match code {
        401 => format!("netease credential required during {operation} request (API code 401)"),
        403 => format!("netease restricted lyrics during {operation} request (API code 403)"),
        429 => format!("netease rate limited during {operation} request (API code 429)"),
        500..=599 => {
            format!("netease network error during {operation} request (API code {code})")
        }
        _ => format!("netease {operation} request failed with API code {code}"),
    }
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

#[cfg(test)]
mod netease_tests {
    use super::*;
    use ncmapi::types::{Lyric, SearchResultSong};
    use std::collections::VecDeque;

    #[derive(Default)]
    struct FakeNeteaseBackend {
        searches: Mutex<VecDeque<Result<SearchSongResp, String>>>,
        lyrics: Mutex<HashMap<usize, Result<LyricResp, String>>>,
        search_calls: Mutex<Vec<String>>,
        lyric_calls: Mutex<Vec<usize>>,
    }

    impl FakeNeteaseBackend {
        fn new(
            searches: Vec<Result<SearchSongResp, String>>,
            lyrics: impl IntoIterator<Item = (usize, Result<LyricResp, String>)>,
        ) -> Self {
            Self {
                searches: Mutex::new(searches.into()),
                lyrics: Mutex::new(lyrics.into_iter().collect()),
                search_calls: Mutex::new(Vec::new()),
                lyric_calls: Mutex::new(Vec::new()),
            }
        }

        fn search_calls(&self) -> Vec<String> {
            self.search_calls.lock().unwrap().clone()
        }

        fn lyric_calls(&self) -> Vec<usize> {
            self.lyric_calls.lock().unwrap().clone()
        }
    }

    impl NeteaseBackend for FakeNeteaseBackend {
        fn search_songs<'a>(
            &'a self,
            keyword: &'a str,
        ) -> Pin<Box<dyn Future<Output = Result<SearchSongResp, String>> + 'a>> {
            Box::pin(async move {
                self.search_calls.lock().unwrap().push(keyword.to_string());
                self.searches
                    .lock()
                    .unwrap()
                    .pop_front()
                    .unwrap_or_else(|| Ok(search_response(Vec::new())))
            })
        }

        fn fetch_lyrics(
            &self,
            song_id: usize,
        ) -> Pin<Box<dyn Future<Output = Result<LyricResp, String>> + '_>> {
            Box::pin(async move {
                self.lyric_calls.lock().unwrap().push(song_id);
                self.lyrics
                    .lock()
                    .unwrap()
                    .get(&song_id)
                    .cloned()
                    .unwrap_or_else(|| Ok(lyric_response(None)))
            })
        }
    }

    struct HangingNeteaseBackend;

    impl NeteaseBackend for HangingNeteaseBackend {
        fn search_songs<'a>(
            &'a self,
            _keyword: &'a str,
        ) -> Pin<Box<dyn Future<Output = Result<SearchSongResp, String>> + 'a>> {
            Box::pin(std::future::pending())
        }

        fn fetch_lyrics(
            &self,
            _song_id: usize,
        ) -> Pin<Box<dyn Future<Output = Result<LyricResp, String>> + '_>> {
            Box::pin(std::future::pending())
        }
    }

    fn song(id: usize, title: &str, artist: &str, album: &str, duration_ms: usize) -> Song {
        Song {
            id,
            name: title.to_string(),
            artists: vec![Artist {
                id: id + 10_000,
                name: Some(artist.to_string()),
            }],
            album: Album {
                id: id + 20_000,
                name: Some(album.to_string()),
                ..Album::default()
            },
            duration: duration_ms,
            ..Song::default()
        }
    }

    fn search_response(songs: Vec<Song>) -> SearchSongResp {
        SearchSongResp {
            code: 200,
            result: Some(SearchResultSong {
                songs,
                has_more: false,
            }),
        }
    }

    fn lyric_response(lrc: Option<&str>) -> LyricResp {
        LyricResp {
            code: 200,
            lrc: lrc.map(|value| Lyric {
                version: 1,
                lyric: value.to_string(),
            }),
            ..LyricResp::default()
        }
    }

    fn run_fake_lookup(
        backend: &FakeNeteaseBackend,
        title: &str,
        artist: &str,
        album: &str,
        duration_ms: Option<u64>,
    ) -> Result<NativeResult, String> {
        fetch_best_lyrics_with_backend(
            backend,
            title,
            artist,
            album,
            duration_ms,
            0,
            Duration::from_secs(1),
        )
    }

    #[test]
    fn tries_second_candidate_when_first_has_no_timed_lyrics() {
        let backend = FakeNeteaseBackend::new(
            vec![Ok(search_response(vec![
                song(1, "Contract Song", "Contract Artist", "Album", 180_000),
                song(2, "Contract Song", "Contract Artist", "Album", 180_000),
            ]))],
            [
                (1, Ok(lyric_response(Some("plain text only")))),
                (2, Ok(lyric_response(Some("[00:01.00]second candidate")))),
            ],
        );

        let result = run_fake_lookup(
            &backend,
            "Contract Song",
            "Contract Artist",
            "Album",
            Some(180_000),
        )
        .expect("second candidate should provide usable lyrics");

        assert_eq!(result.id.as_deref(), Some("2"));
        assert_eq!(backend.lyric_calls(), vec![1, 2]);
        assert_eq!(backend.search_calls().len(), 1);
    }

    #[test]
    fn related_candidate_below_early_score_is_used_after_search_fallbacks() {
        let candidate = song(
            9,
            "Contract Song",
            "Different Performer",
            "Other Album",
            300_000,
        );
        let mapped = map_song(
            &candidate,
            "Contract Song",
            "Contract Artist",
            "Contract Album",
            Some(180_000),
        )
        .expect("the title remains related");
        assert!(mapped.score < EARLY_ACCEPT_SCORE);

        let backend = FakeNeteaseBackend::new(
            vec![Ok(search_response(vec![candidate]))],
            [(9, Ok(lyric_response(Some("[00:02.00]low score match"))))],
        );
        let result = run_fake_lookup(
            &backend,
            "Contract Song",
            "Contract Artist",
            "Contract Album",
            Some(180_000),
        )
        .expect("related low-score candidate should remain eligible");

        assert_eq!(result.id.as_deref(), Some("9"));
        assert_eq!(backend.lyric_calls(), vec![9]);
        assert!(backend.search_calls().len() > 1);
    }

    #[test]
    fn blank_artist_still_searches_by_title() {
        let backend = FakeNeteaseBackend::new(
            vec![Ok(search_response(vec![song(
                10,
                "Solo Song",
                "Stored Artist",
                "",
                200_000,
            )]))],
            [(10, Ok(lyric_response(Some("[00:03.00]found by title"))))],
        );

        let result = run_fake_lookup(&backend, "Solo Song", "", "", None)
            .expect("artist is optional for NetEase search");

        assert_eq!(result.id.as_deref(), Some("10"));
        assert_eq!(backend.search_calls(), vec!["Solo Song"]);
    }

    #[test]
    fn matching_version_with_large_duration_difference_remains_eligible() {
        let backend = FakeNeteaseBackend::new(
            vec![Ok(search_response(vec![song(
                11,
                "Anthem - Live",
                "Band",
                "Different Release",
                300_000,
            )]))],
            [(11, Ok(lyric_response(Some("[00:04.00]live lyric"))))],
        );

        let result = run_fake_lookup(
            &backend,
            "Anthem (Live)",
            "Band",
            "Original Release",
            Some(180_000),
        )
        .expect("duration mismatch only removes duration credit");

        assert_eq!(result.id.as_deref(), Some("11"));
    }

    #[test]
    fn clearly_different_title_is_never_downloaded() {
        let backend = FakeNeteaseBackend::new(
            vec![Ok(search_response(vec![song(
                12,
                "Completely Unrelated",
                "Same Artist",
                "",
                180_000,
            )]))],
            [(12, Ok(lyric_response(Some("[00:05.00]wrong song"))))],
        );

        let error = run_fake_lookup(&backend, "Blue Moon", "Same Artist", "", Some(180_000))
            .expect_err("an unrelated title must not be selected");

        assert_eq!(classify_error(&error), "NotFound");
        assert!(backend.lyric_calls().is_empty());
    }

    #[test]
    fn candidate_ids_are_not_retried_and_total_requests_are_bounded() {
        let repeated = vec![
            song(20, "Budget Song", "Artist", "Album", 180_000),
            song(20, "Budget Song", "Artist", "Album", 180_000),
            song(21, "Budget Song", "Artist", "Album", 180_000),
            song(22, "Budget Song", "Artist", "Album", 180_000),
            song(23, "Budget Song", "Artist", "Album", 180_000),
        ];
        let backend = FakeNeteaseBackend::new(
            vec![Ok(search_response(repeated))],
            [
                (20, Ok(lyric_response(Some("untimed")))),
                (21, Ok(lyric_response(Some("untimed")))),
                (22, Ok(lyric_response(Some("untimed")))),
                (23, Ok(lyric_response(Some("[00:06.00]fourth")))),
            ],
        );

        let error = run_fake_lookup(
            &backend,
            "Budget Song (Live)",
            "Artist",
            "Album",
            Some(180_000),
        )
        .expect_err("only three different candidate downloads are allowed");
        let search_count = backend.search_calls().len();
        let lyric_calls = backend.lyric_calls();

        assert_eq!(classify_error(&error), "NotFound");
        assert_eq!(lyric_calls, vec![20, 21, 22]);
        assert_eq!(search_count + lyric_calls.len(), NETEASE_MAX_REQUESTS);
    }

    #[test]
    fn network_and_parse_failures_are_not_collapsed_to_not_found() {
        let network_backend = FakeNeteaseBackend::new(
            vec![Err(
                "netease network error during search request: fixture".to_string()
            )],
            [],
        );
        let network_error = run_fake_lookup(&network_backend, "Song", "Artist", "", None)
            .expect_err("network fixture must fail");
        assert_eq!(classify_error(&network_error), "NetworkError");

        let parse_backend = FakeNeteaseBackend::new(
            vec![Err(
                "netease parse error decoding search response: fixture".to_string()
            )],
            [],
        );
        let parse_error = run_fake_lookup(&parse_backend, "Song", "Artist", "", None)
            .expect_err("parse fixture must fail");
        assert_eq!(classify_error(&parse_error), "ParseError");
        assert_eq!(
            classify_error("parse error: unexpected token in controlled JSON"),
            "ParseError"
        );

        assert_eq!(
            classify_error("no usable subtitle; later request timed out"),
            "NetworkError"
        );
        assert_eq!(
            classify_error(&netease_api_code_error("search", 500)),
            "NetworkError"
        );
        assert_eq!(
            classify_error(&netease_api_code_error("search", 429)),
            "RateLimited"
        );
    }

    #[test]
    fn cancellation_and_overall_timeout_still_end_lookup() {
        const LOOKUP_ID: i64 = i64::MAX - 700;
        let backend = FakeNeteaseBackend::default();
        cancel_lookup(LOOKUP_ID);
        let canceled = fetch_best_lyrics_with_backend(
            &backend,
            "Song",
            "Artist",
            "",
            None,
            LOOKUP_ID,
            Duration::from_secs(1),
        )
        .expect_err("pre-canceled lookup must stop before requests");
        assert_eq!(canceled, "lookup canceled");
        assert_eq!(classify_error(&canceled), "NativeError");
        assert!(backend.search_calls().is_empty());

        let timeout = fetch_best_lyrics_with_backend(
            &HangingNeteaseBackend,
            "Song",
            "Artist",
            "",
            None,
            0,
            Duration::from_millis(5),
        )
        .expect_err("hanging request must use the overall timeout");
        assert_eq!(classify_error(&timeout), "NetworkError");
    }

    #[test]
    fn search_keywords_preserve_exact_query_then_relax_without_duplicates() {
        assert_eq!(
            build_search_keywords("Song (Live)", "Artist", "Album"),
            vec![
                "Song (Live) Album Artist",
                "Song (Live) Artist",
                "Song Artist",
                "Song (Live)",
                "Song",
            ]
        );
        assert_eq!(build_search_keywords("Song", "", ""), vec!["Song"]);
    }
}
