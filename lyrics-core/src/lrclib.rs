#[cfg(test)]
use crate::matching::EARLY_ACCEPT_SCORE;
use crate::{
    begin_lookup_cancellation, check_lookup_cancelled,
    diagnostics::LookupTrace,
    matching::{
        build_search_queries, is_confident_early_match, normalize_usable_lrc, score_candidate,
        MatchMetadata, MatchScore,
    },
    NativeResult,
};
use reqwest::header::RETRY_AFTER;
use reqwest::{Client, RequestBuilder, StatusCode};
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;
use std::collections::HashSet;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Mutex, MutexGuard, OnceLock, TryLockError};
use std::time::{Duration, Instant};
use unicode_normalization::UnicodeNormalization;

const LRCLIB_GET_URL: &str = "https://lrclib.net/api/get";
const LRCLIB_SEARCH_URL: &str = "https://lrclib.net/api/search";
const LRCLIB_LOOKUP_TIMEOUT: Duration = Duration::from_secs(28);
const LRCLIB_REQUEST_TIMEOUT: Duration = Duration::from_secs(12);
const LRCLIB_QUEUE_WAIT_TIMEOUT: Duration = Duration::from_secs(8);
const LRCLIB_GATE_POLL_INTERVAL: Duration = Duration::from_millis(25);
const LRCLIB_CANCELLATION_POLL_INTERVAL: Duration = Duration::from_millis(50);
const LRCLIB_REQUEST_INTERVAL: Duration = Duration::from_millis(250);
const LRCLIB_DEFAULT_RETRY_AFTER: Duration = Duration::from_secs(60);
const LRCLIB_MAX_RETRY_AFTER: Duration = Duration::from_secs(24 * 60 * 60);
const LRCLIB_MAX_REQUESTS: usize = 5;
const LRCLIB_USER_AGENT: &str = concat!(
    "AirLyrics/",
    env!("CARGO_PKG_VERSION"),
    " (https://github.com/AirLyrics/AirLyrics)"
);

static LRCLIB_CLIENT: OnceLock<Client> = OnceLock::new();
static LRCLIB_REQUEST_SCHEDULE: OnceLock<Mutex<RequestSchedule>> = OnceLock::new();

#[derive(Default)]
struct RequestSchedule {
    next_request_at: Option<Instant>,
    retry_after_until: Option<Instant>,
}

#[derive(Debug)]
pub(crate) struct LrclibError {
    error_type: &'static str,
    message: String,
    retry_after: Option<Duration>,
}

impl LrclibError {
    fn new(error_type: &'static str, message: impl Into<String>) -> Self {
        Self {
            error_type,
            message: message.into(),
            retry_after: None,
        }
    }

    fn not_found(message: impl Into<String>) -> Self {
        Self::new("NotFound", message)
    }

    fn rate_limited(retry_after: Duration) -> Self {
        let seconds = duration_ceil_seconds(retry_after);
        Self {
            error_type: "RateLimited",
            message: format!("LRCLIB rate limited; retry after {seconds} seconds"),
            retry_after: Some(retry_after),
        }
    }

    fn network(message: impl Into<String>) -> Self {
        Self::new("NetworkError", message)
    }

    fn parse(message: impl Into<String>) -> Self {
        Self::new("ParseError", message)
    }

    pub(crate) fn native(message: impl Into<String>) -> Self {
        Self::new("NativeError", message)
    }

    fn unknown(message: impl Into<String>) -> Self {
        Self::new("Unknown", message)
    }

    pub(crate) fn error_type(&self) -> &'static str {
        self.error_type
    }

    pub(crate) fn message(&self) -> &str {
        &self.message
    }

    fn is_not_found(&self) -> bool {
        self.error_type == "NotFound"
    }
}

#[derive(Debug)]
struct LookupQuery {
    title: String,
    artist: String,
    album: Option<String>,
    duration_ms: Option<u64>,
    api_duration_seconds: Option<f64>,
}

impl LookupQuery {
    fn new(
        title: &str,
        artist: &str,
        album: &str,
        duration_ms: Option<u64>,
    ) -> Result<Self, LrclibError> {
        let title = normalize_query_part(title);
        let artist = normalize_query_part(artist);
        let album = non_empty(normalize_query_part(album));

        if title.is_empty() {
            return Err(LrclibError::not_found("LRCLIB lookup requires a title"));
        }

        let api_duration_seconds = duration_ms
            .filter(|value| (1_000..=3_600_000).contains(value))
            .map(|value| value as f64 / 1000.0);

        Ok(Self {
            title,
            artist,
            album,
            duration_ms: duration_ms.filter(|value| *value > 0),
            api_duration_seconds,
        })
    }

    fn api_query(&self) -> ApiQuery<'_> {
        ApiQuery {
            track_name: &self.title,
            artist_name: &self.artist,
            album_name: self.album.as_deref(),
            duration: self.api_duration_seconds,
        }
    }

    fn search_query<'a>(
        &'a self,
        track_name: &'a str,
        artist_name: Option<&'a str>,
    ) -> SearchApiQuery<'a> {
        SearchApiQuery {
            track_name,
            artist_name,
        }
    }

    fn match_metadata(&self) -> MatchMetadata<'_> {
        MatchMetadata {
            title: &self.title,
            artist: &self.artist,
            album: self.album.as_deref().unwrap_or_default(),
            duration_ms: self.duration_ms,
        }
    }
}

#[derive(Serialize)]
struct ApiQuery<'a> {
    track_name: &'a str,
    artist_name: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    album_name: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration: Option<f64>,
}

#[derive(Serialize)]
struct SearchApiQuery<'a> {
    track_name: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    artist_name: Option<&'a str>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApiTrack {
    id: u64,
    #[serde(default)]
    track_name: Option<String>,
    #[serde(default)]
    artist_name: Option<String>,
    #[serde(default)]
    album_name: Option<String>,
    #[serde(default)]
    duration: Option<f64>,
    #[serde(default)]
    instrumental: Option<bool>,
    #[serde(default)]
    synced_lyrics: Option<String>,
}

#[derive(Debug)]
struct ScoredApiTrack {
    track: ApiTrack,
    match_score: MatchScore,
    confident_early_match: bool,
    duration_delta_ms: u64,
}

#[derive(Default)]
struct SearchCandidateStats {
    returned: usize,
    accepted: usize,
    duplicate: usize,
    rejected_instrumental: usize,
    rejected_lyrics: usize,
    rejected_title: usize,
}

enum SearchCandidateRejection {
    Instrumental,
    NoUsableLyrics,
    Title,
}

#[derive(Debug, Eq, PartialEq)]
enum ResponseDisposition {
    ReadBody,
    NotFound,
}

trait LrclibBackend {
    fn request_interval(&self) -> Duration {
        LRCLIB_REQUEST_INTERVAL
    }

    fn fetch_metadata<'a>(
        &'a self,
        query: &'a LookupQuery,
        lookup_id: jni::sys::jlong,
        observed_retry_after: &'a mut Option<Duration>,
    ) -> Pin<Box<dyn Future<Output = Result<Option<ApiTrack>, LrclibError>> + 'a>>;

    fn search<'a>(
        &'a self,
        query: &'a LookupQuery,
        track_name: &'a str,
        artist_name: Option<&'a str>,
        lookup_id: jni::sys::jlong,
        observed_retry_after: &'a mut Option<Duration>,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<ApiTrack>, LrclibError>> + 'a>>;
}

struct HttpLrclibBackend;

impl LrclibBackend for HttpLrclibBackend {
    fn fetch_metadata<'a>(
        &'a self,
        query: &'a LookupQuery,
        lookup_id: jni::sys::jlong,
        observed_retry_after: &'a mut Option<Duration>,
    ) -> Pin<Box<dyn Future<Output = Result<Option<ApiTrack>, LrclibError>> + 'a>> {
        Box::pin(fetch_metadata_track(query, lookup_id, observed_retry_after))
    }

    fn search<'a>(
        &'a self,
        query: &'a LookupQuery,
        track_name: &'a str,
        artist_name: Option<&'a str>,
        lookup_id: jni::sys::jlong,
        observed_retry_after: &'a mut Option<Duration>,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<ApiTrack>, LrclibError>> + 'a>> {
        Box::pin(fetch_search_tracks(
            query,
            track_name,
            artist_name,
            lookup_id,
            observed_retry_after,
        ))
    }
}

pub(crate) fn fetch_best_lyrics(
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
) -> Result<NativeResult, LrclibError> {
    let _cancellation_guard = begin_lookup_cancellation(lookup_id).map_err(LrclibError::native)?;
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    let query = LookupQuery::new(title, artist, album, duration_ms)?;
    let trace = LookupTrace::new("lrclib");
    trace.event(
        "lookup_start",
        format_args!(
            "artistPresent={} albumPresent={} durationPresent={} requestLimit={}",
            !query.artist.is_empty(),
            query.album.is_some(),
            query.duration_ms.is_some(),
            LRCLIB_MAX_REQUESTS,
        ),
    );

    let queue_started = Instant::now();
    let mut schedule = match acquire_request_schedule(lookup_id) {
        Ok(schedule) => schedule,
        Err(error) => {
            trace.event(
                "queue",
                format_args!(
                    "outcome=error reason={} waitMs={}",
                    error.error_type(),
                    queue_started.elapsed().as_millis(),
                ),
            );
            return Err(error);
        }
    };
    if let Err(error) = enforce_request_schedule(&mut schedule, lookup_id) {
        trace.event(
            "queue",
            format_args!(
                "outcome=blocked reason={} waitMs={}",
                error.error_type(),
                queue_started.elapsed().as_millis(),
            ),
        );
        return Err(error);
    }
    trace.event(
        "queue",
        format_args!(
            "outcome=ready waitMs={}",
            queue_started.elapsed().as_millis()
        ),
    );

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| LrclibError::native(format!("failed to create tokio runtime: {error}")))?;

    let mut observed_retry_after = None;
    let result = runtime.block_on(fetch_with_fallback(
        &query,
        lookup_id,
        &mut observed_retry_after,
        &trace,
    ));

    let now = Instant::now();
    schedule.next_request_at = Some(now + LRCLIB_REQUEST_INTERVAL);
    let retry_after =
        observed_retry_after.or_else(|| result.as_ref().err().and_then(|error| error.retry_after));
    if let Some(retry_after) = retry_after {
        schedule.retry_after_until = Some(now + retry_after);
    }

    match &result {
        Ok(_) => trace.event("result", "outcome=usable"),
        Err(error) => trace.event(
            "result",
            format_args!("outcome=error reason={}", error.error_type()),
        ),
    }

    result
}

async fn fetch_with_fallback(
    query: &LookupQuery,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<Duration>,
    trace: &LookupTrace,
) -> Result<NativeResult, LrclibError> {
    fetch_with_fallback_backend_traced(
        &HttpLrclibBackend,
        query,
        lookup_id,
        observed_retry_after,
        trace,
    )
    .await
}

#[cfg(test)]
async fn fetch_with_fallback_backend<B: LrclibBackend>(
    backend: &B,
    query: &LookupQuery,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<Duration>,
) -> Result<NativeResult, LrclibError> {
    let trace = LookupTrace::new("lrclib-test");
    fetch_with_fallback_backend_traced(backend, query, lookup_id, observed_retry_after, &trace)
        .await
}

async fn fetch_with_fallback_backend_traced<B: LrclibBackend>(
    backend: &B,
    query: &LookupQuery,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<Duration>,
    trace: &LookupTrace,
) -> Result<NativeResult, LrclibError> {
    let deadline = tokio::time::Instant::now() + LRCLIB_LOOKUP_TIMEOUT;
    let mut request_count = 0usize;
    let mut seen_track_ids = HashSet::new();
    let mut candidates = Vec::new();
    let search_queries = build_search_queries(&query.title, &query.artist);

    // `/api/get` remains the cheapest and most precise path, but LRCLIB requires an artist for
    // this endpoint. An absent artist therefore skips directly to the title search fallbacks.
    if !query.artist.is_empty() {
        if let Err(error) = prepare_request(
            &mut request_count,
            deadline,
            lookup_id,
            backend.request_interval(),
        )
        .await
        {
            trace.event(
                "exact",
                format_args!(
                    "outcome=error reason={} requestsUsed={}",
                    error.error_type(),
                    request_count,
                ),
            );
            return Err(error);
        }
        let started = Instant::now();
        let full_match = match await_lookup_budget(
            deadline,
            backend.fetch_metadata(query, lookup_id, observed_retry_after),
        )
        .await
        {
            Ok(track) => track,
            Err(error) => {
                trace.event(
                    "exact",
                    format_args!(
                        "outcome=error reason={} requestMs={} requestsUsed={}",
                        error.error_type(),
                        started.elapsed().as_millis(),
                        request_count,
                    ),
                );
                // Exact endpoint failures keep their classification and do not trigger an
                // unconditional retry cascade.
                return Err(error);
            }
        };
        if let Some(track) = full_match {
            seen_track_ids.insert(track.id);
            match map_api_track(track, query) {
                Ok(result) => {
                    trace.event(
                        "lookup_stop",
                        format_args!(
                            "reason=exact_usable requestMs={} requestsUsed={}",
                            started.elapsed().as_millis(),
                            request_count,
                        ),
                    );
                    return Ok(result);
                }
                Err(error) if error.is_not_found() => {
                    trace.event(
                        "exact",
                        format_args!(
                            "outcome=no_usable_timed_lyrics requestMs={} requestsUsed={}",
                            started.elapsed().as_millis(),
                            request_count,
                        ),
                    );
                }
                Err(error) => {
                    trace.event(
                        "exact",
                        format_args!(
                            "outcome=error reason={} requestMs={} requestsUsed={}",
                            error.error_type(),
                            started.elapsed().as_millis(),
                            request_count,
                        ),
                    );
                    return Err(error);
                }
            }
        } else {
            trace.event(
                "exact",
                format_args!(
                    "outcome=not_found requestMs={} requestsUsed={}",
                    started.elapsed().as_millis(),
                    request_count,
                ),
            );
        }
    } else {
        trace.event(
            "exact",
            "outcome=skipped reason=blank_artist requestsUsed=0",
        );
    }

    for (index, search_query) in search_queries.iter().enumerate() {
        if request_count >= LRCLIB_MAX_REQUESTS {
            trace.event(
                "search_stop",
                format_args!("reason=request_budget requestsUsed={request_count}"),
            );
            break;
        }

        if let Err(error) = prepare_request(
            &mut request_count,
            deadline,
            lookup_id,
            backend.request_interval(),
        )
        .await
        {
            return finish_lrclib_after_error(
                candidates,
                query,
                error,
                lookup_id,
                trace,
                request_count,
                "request_preparation",
            );
        }
        let started = Instant::now();
        let search_result = await_lookup_budget(
            deadline,
            backend.search(
                query,
                &search_query.title,
                search_query.artist.as_deref(),
                lookup_id,
                observed_retry_after,
            ),
        )
        .await;
        let tracks = match search_result {
            Ok(tracks) => tracks,
            Err(error) => {
                trace.event(
                    "search",
                    format_args!(
                        "round={} outcome=error artistFilter={} reason={} requestMs={} requestsUsed={}",
                        index + 1,
                        search_query.artist.is_some(),
                        error.error_type(),
                        started.elapsed().as_millis(),
                        request_count,
                    ),
                );
                return finish_lrclib_after_error(
                    candidates,
                    query,
                    error,
                    lookup_id,
                    trace,
                    request_count,
                    "search_error",
                );
            }
        };

        let stats = add_search_candidates(tracks, query, &mut seen_track_ids, &mut candidates);
        trace_lrclib_search(
            trace,
            index + 1,
            search_query.artist.is_some(),
            started,
            &stats,
            &candidates,
            request_count,
        );
        if let Some(track) = take_best_candidate(&mut candidates, true) {
            trace.event(
                "lookup_stop",
                format_args!(
                    "reason=confident_search_candidate round={} requestsUsed={}",
                    index + 1,
                    request_count,
                ),
            );
            return map_api_track(track, query);
        }
    }

    if let Some(track) = take_best_candidate(&mut candidates, false) {
        trace.event(
            "lookup_stop",
            format_args!("reason=final_related_candidate requestsUsed={request_count}"),
        );
        return map_api_track(track, query);
    }

    trace.event(
        "lookup_stop",
        format_args!("reason=no_related_synced_lyrics requestsUsed={request_count}"),
    );
    Err(LrclibError::not_found(
        "LRCLIB returned no related synced lyrics",
    ))
}

async fn prepare_request(
    request_count: &mut usize,
    deadline: tokio::time::Instant,
    lookup_id: jni::sys::jlong,
    request_interval: Duration,
) -> Result<(), LrclibError> {
    if *request_count > 0 {
        await_lookup_budget(deadline, wait_between_requests(lookup_id, request_interval)).await?;
    }
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    *request_count += 1;
    Ok(())
}

async fn await_lookup_budget<F, T>(
    deadline: tokio::time::Instant,
    future: F,
) -> Result<T, LrclibError>
where
    F: Future<Output = Result<T, LrclibError>>,
{
    tokio::time::timeout_at(deadline, future)
        .await
        .unwrap_or_else(|_| Err(LrclibError::network("LRCLIB lookup timed out")))
}

async fn fetch_metadata_track(
    query: &LookupQuery,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<Duration>,
) -> Result<Option<ApiTrack>, LrclibError> {
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    let request = client()?.get(LRCLIB_GET_URL).query(&query.api_query());
    let Some(body) = fetch_response_body(request, lookup_id, observed_retry_after).await? else {
        return Ok(None);
    };

    serde_json::from_str(&body)
        .map(Some)
        .map_err(|error| LrclibError::parse(format!("failed to decode LRCLIB response: {error}")))
}

async fn fetch_search_tracks(
    query: &LookupQuery,
    track_name: &str,
    artist_name: Option<&str>,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<Duration>,
) -> Result<Vec<ApiTrack>, LrclibError> {
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    let request = client()?
        .get(LRCLIB_SEARCH_URL)
        .query(&query.search_query(track_name, artist_name));
    let Some(body) = fetch_response_body(request, lookup_id, observed_retry_after).await? else {
        return Ok(Vec::new());
    };

    serde_json::from_str(&body).map_err(|error| {
        LrclibError::parse(format!("failed to decode LRCLIB search response: {error}"))
    })
}

async fn fetch_response_body(
    request: RequestBuilder,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<Duration>,
) -> Result<Option<String>, LrclibError> {
    let response = await_reqwest(request.send(), lookup_id).await?;
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;

    let status = response.status();
    let retry_after = (status == StatusCode::TOO_MANY_REQUESTS).then(|| {
        response
            .headers()
            .get(RETRY_AFTER)
            .and_then(parse_retry_after)
            .unwrap_or(LRCLIB_DEFAULT_RETRY_AFTER)
    });
    if let Some(retry_after) = retry_after {
        *observed_retry_after = Some(retry_after);
    }
    if response_disposition(status, retry_after)? == ResponseDisposition::NotFound {
        return Ok(None);
    }

    let body = await_reqwest(response.text(), lookup_id).await?;
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    Ok(Some(body))
}

fn response_disposition(
    status: StatusCode,
    retry_after: Option<Duration>,
) -> Result<ResponseDisposition, LrclibError> {
    if status == StatusCode::NOT_FOUND {
        return Ok(ResponseDisposition::NotFound);
    }
    if status == StatusCode::TOO_MANY_REQUESTS {
        return Err(LrclibError::rate_limited(
            retry_after.unwrap_or(LRCLIB_DEFAULT_RETRY_AFTER),
        ));
    }
    if !status.is_success() {
        let message = format!("LRCLIB request failed with HTTP {status}");
        return if status.is_server_error() {
            Err(LrclibError::network(message))
        } else {
            Err(LrclibError::unknown(message))
        };
    }

    Ok(ResponseDisposition::ReadBody)
}

async fn wait_between_requests(
    lookup_id: jni::sys::jlong,
    request_interval: Duration,
) -> Result<(), LrclibError> {
    let deadline = Instant::now() + request_interval;
    while let Some(wait) = deadline.checked_duration_since(Instant::now()) {
        check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
        tokio::time::sleep(wait.min(LRCLIB_CANCELLATION_POLL_INTERVAL)).await;
    }
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)
}

async fn await_reqwest<F, T>(future: F, lookup_id: jni::sys::jlong) -> Result<T, LrclibError>
where
    F: Future<Output = Result<T, reqwest::Error>>,
{
    tokio::pin!(future);
    loop {
        tokio::select! {
            result = &mut future => {
                return result.map_err(map_reqwest_error);
            }
            _ = tokio::time::sleep(LRCLIB_CANCELLATION_POLL_INTERVAL) => {
                check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
            }
        }
    }
}

fn map_reqwest_error(error: reqwest::Error) -> LrclibError {
    if error.is_decode() {
        LrclibError::parse(format!("failed to decode LRCLIB response: {error}"))
    } else if error.is_timeout() {
        LrclibError::network("LRCLIB request timed out")
    } else {
        LrclibError::network(format!("LRCLIB network request failed: {error}"))
    }
}

#[cfg(test)]
fn map_api_response(body: &str, query: &LookupQuery) -> Result<NativeResult, LrclibError> {
    let track: ApiTrack = serde_json::from_str(body).map_err(|error| {
        LrclibError::parse(format!("failed to decode LRCLIB response: {error}"))
    })?;

    map_api_track(track, query)
}

fn map_api_track(track: ApiTrack, query: &LookupQuery) -> Result<NativeResult, LrclibError> {
    if track.instrumental.unwrap_or(false) {
        return Err(LrclibError::not_found(
            "LRCLIB track is instrumental and has no timed lyrics",
        ));
    }

    let lrc = track
        .synced_lyrics
        .and_then(normalize_usable_lrc)
        .ok_or_else(|| LrclibError::not_found("LRCLIB returned no usable synced lyrics"))?;
    let title = track
        .track_name
        .and_then(|value| non_empty(normalize_query_part(&value)))
        .unwrap_or_else(|| query.title.clone());
    let artist = track
        .artist_name
        .and_then(|value| non_empty(normalize_query_part(&value)))
        .or_else(|| non_empty(query.artist.clone()));
    let album = track
        .album_name
        .and_then(|value| non_empty(normalize_query_part(&value)))
        .or_else(|| query.album.clone());
    let duration_ms = track
        .duration
        .and_then(duration_seconds_to_ms)
        .or(query.duration_ms);

    Ok(NativeResult {
        ok: true,
        source: "lrclib-rust",
        id: Some(track.id.to_string()),
        title: Some(title),
        artist,
        album,
        duration_ms,
        lrc: Some(lrc.clone()),
        translated_lrc: None,
        merged_lrc: Some(lrc),
        error_type: None,
        error: None,
    })
}

fn trace_lrclib_search(
    trace: &LookupTrace,
    round: usize,
    artist_filter: bool,
    started: Instant,
    stats: &SearchCandidateStats,
    candidates: &[ScoredApiTrack],
    request_count: usize,
) {
    let best = candidates
        .iter()
        .max_by(|left, right| compare_scored_tracks(left, right));
    trace.event(
        "search",
        format_args!(
            "round={} outcome=ok artistFilter={} requestMs={} returned={} accepted={} duplicate={} rejectedInstrumental={} rejectedLyrics={} rejectedTitle={} pool={} topScore={} topTitleScore={} topArtistScore={} topEarly={} requestsUsed={}",
            round,
            artist_filter,
            started.elapsed().as_millis(),
            stats.returned,
            stats.accepted,
            stats.duplicate,
            stats.rejected_instrumental,
            stats.rejected_lyrics,
            stats.rejected_title,
            candidates.len(),
            format_lrclib_score(best.map(|candidate| candidate.match_score.total)),
            format_lrclib_score(best.map(|candidate| candidate.match_score.title_similarity)),
            format_lrclib_score(
                best.and_then(|candidate| candidate.match_score.artist_similarity)
            ),
            best.is_some_and(|candidate| candidate.confident_early_match),
            request_count,
        ),
    );
}

fn format_lrclib_score(value: Option<f64>) -> String {
    value
        .map(|value| format!("{value:.3}"))
        .unwrap_or_else(|| "missing".to_string())
}

#[allow(clippy::too_many_arguments)]
fn finish_lrclib_after_error(
    candidates: Vec<ScoredApiTrack>,
    query: &LookupQuery,
    error: LrclibError,
    lookup_id: jni::sys::jlong,
    trace: &LookupTrace,
    request_count: usize,
    stage: &str,
) -> Result<NativeResult, LrclibError> {
    let triggering_error = error.error_type();
    let result = best_candidate_or_error(candidates, query, error, lookup_id);
    match &result {
        Ok(_) => trace.event(
            "lookup_stop",
            format_args!(
                "reason=retained_candidate_after_{stage} triggeringError={triggering_error} requestsUsed={request_count}",
            ),
        ),
        Err(final_error) => trace.event(
            "lookup_stop",
            format_args!(
                "reason={} stage={} requestsUsed={}",
                final_error.error_type(),
                stage,
                request_count,
            ),
        ),
    }
    result
}

fn add_search_candidates(
    tracks: Vec<ApiTrack>,
    query: &LookupQuery,
    seen_track_ids: &mut HashSet<u64>,
    candidates: &mut Vec<ScoredApiTrack>,
) -> SearchCandidateStats {
    let mut stats = SearchCandidateStats::default();
    for track in tracks {
        stats.returned += 1;
        if !seen_track_ids.insert(track.id) {
            stats.duplicate += 1;
            continue;
        }
        match score_search_track_with_reason(track, query) {
            Ok(candidate) => {
                stats.accepted += 1;
                candidates.push(candidate);
            }
            Err(SearchCandidateRejection::Instrumental) => stats.rejected_instrumental += 1,
            Err(SearchCandidateRejection::NoUsableLyrics) => stats.rejected_lyrics += 1,
            Err(SearchCandidateRejection::Title) => stats.rejected_title += 1,
        }
    }
    stats
}

fn take_best_candidate(candidates: &mut Vec<ScoredApiTrack>, early_only: bool) -> Option<ApiTrack> {
    let best_index = candidates
        .iter()
        .enumerate()
        .max_by(|(_, left), (_, right)| compare_scored_tracks(left, right))?
        .0;
    if early_only && !candidates[best_index].confident_early_match {
        return None;
    }

    Some(candidates.swap_remove(best_index).track)
}

fn best_candidate_or_error(
    mut candidates: Vec<ScoredApiTrack>,
    query: &LookupQuery,
    error: LrclibError,
    lookup_id: jni::sys::jlong,
) -> Result<NativeResult, LrclibError> {
    // Cancellation always wins. Other failures from an optional, broader search must not discard
    // an already obtained and usable result.
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    match take_best_candidate(&mut candidates, false) {
        Some(track) => map_api_track(track, query),
        None => Err(error),
    }
}

#[cfg(test)]
fn score_search_track(track: ApiTrack, query: &LookupQuery) -> Option<ScoredApiTrack> {
    score_search_track_with_reason(track, query).ok()
}

fn score_search_track_with_reason(
    mut track: ApiTrack,
    query: &LookupQuery,
) -> Result<ScoredApiTrack, SearchCandidateRejection> {
    if track.instrumental.unwrap_or(false) {
        return Err(SearchCandidateRejection::Instrumental);
    }

    let lrc = track
        .synced_lyrics
        .take()
        .and_then(normalize_usable_lrc)
        .ok_or(SearchCandidateRejection::NoUsableLyrics)?;
    let candidate_duration_ms = track.duration.and_then(duration_seconds_to_ms);
    let candidate_metadata = MatchMetadata {
        title: track.track_name.as_deref().unwrap_or_default(),
        artist: track.artist_name.as_deref().unwrap_or_default(),
        album: track.album_name.as_deref().unwrap_or_default(),
        duration_ms: candidate_duration_ms,
    };
    let match_score = score_candidate(query.match_metadata(), candidate_metadata)
        .ok_or(SearchCandidateRejection::Title)?;
    let confident_early_match = is_confident_early_match(&match_score);
    let track_duration_ms = track.duration.and_then(duration_seconds_to_ms);
    let duration_delta_ms = query
        .duration_ms
        .zip(track_duration_ms)
        .map(|(query_duration_ms, track_duration_ms)| query_duration_ms.abs_diff(track_duration_ms))
        .unwrap_or(u64::MAX);
    track.synced_lyrics = Some(lrc);

    Ok(ScoredApiTrack {
        track,
        match_score,
        confident_early_match,
        duration_delta_ms,
    })
}

fn compare_scored_tracks(left: &ScoredApiTrack, right: &ScoredApiTrack) -> Ordering {
    left.match_score
        .total
        .total_cmp(&right.match_score.total)
        // Prefer the closest duration when metadata scores are identical.
        .then_with(|| right.duration_delta_ms.cmp(&left.duration_delta_ms))
        // Make duplicate search results deterministic.
        .then_with(|| right.track.id.cmp(&left.track.id))
}

fn client() -> Result<&'static Client, LrclibError> {
    if let Some(client) = LRCLIB_CLIENT.get() {
        return Ok(client);
    }

    let client = Client::builder()
        .user_agent(LRCLIB_USER_AGENT)
        .connect_timeout(Duration::from_secs(4))
        .timeout(LRCLIB_REQUEST_TIMEOUT)
        .build()
        .map_err(|error| LrclibError::native(format!("failed to create LRCLIB client: {error}")))?;
    let _ = LRCLIB_CLIENT.set(client);
    LRCLIB_CLIENT
        .get()
        .ok_or_else(|| LrclibError::native("failed to initialize LRCLIB client"))
}

fn request_schedule() -> &'static Mutex<RequestSchedule> {
    LRCLIB_REQUEST_SCHEDULE.get_or_init(|| Mutex::new(RequestSchedule::default()))
}

fn acquire_request_schedule(
    lookup_id: jni::sys::jlong,
) -> Result<MutexGuard<'static, RequestSchedule>, LrclibError> {
    let deadline = Instant::now() + LRCLIB_QUEUE_WAIT_TIMEOUT;
    loop {
        check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
        match request_schedule().try_lock() {
            Ok(schedule) => return Ok(schedule),
            Err(TryLockError::Poisoned(error)) => return Ok(error.into_inner()),
            Err(TryLockError::WouldBlock) if Instant::now() >= deadline => {
                return Err(LrclibError::network("LRCLIB request queue wait timed out"));
            }
            Err(TryLockError::WouldBlock) => std::thread::sleep(LRCLIB_GATE_POLL_INTERVAL),
        }
    }
}

fn enforce_request_schedule(
    schedule: &mut RequestSchedule,
    lookup_id: jni::sys::jlong,
) -> Result<(), LrclibError> {
    let now = Instant::now();
    if let Some(retry_after_until) = schedule.retry_after_until {
        if retry_after_until > now {
            return Err(LrclibError::rate_limited(retry_after_until - now));
        }
        schedule.retry_after_until = None;
    }

    while let Some(wait) = schedule
        .next_request_at
        .and_then(|next_request_at| next_request_at.checked_duration_since(Instant::now()))
    {
        check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
        std::thread::sleep(wait.min(LRCLIB_GATE_POLL_INTERVAL));
    }
    Ok(())
}

fn parse_retry_after(value: &reqwest::header::HeaderValue) -> Option<Duration> {
    value
        .to_str()
        .ok()?
        .trim()
        .parse::<u64>()
        .ok()
        .map(Duration::from_secs)
        .map(|duration| duration.min(LRCLIB_MAX_RETRY_AFTER))
}

fn duration_ceil_seconds(duration: Duration) -> u64 {
    duration
        .as_secs()
        .saturating_add(u64::from(duration.subsec_nanos() > 0))
}

fn duration_seconds_to_ms(seconds: f64) -> Option<u64> {
    if !seconds.is_finite() || seconds <= 0.0 || seconds > (u64::MAX as f64 / 1000.0) {
        None
    } else {
        Some((seconds * 1000.0).round() as u64)
    }
}

fn normalize_query_part(value: &str) -> String {
    value
        .nfkc()
        .collect::<String>()
        .replace(['\n', '\r', '\t'], " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn non_empty(value: String) -> Option<String> {
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::{HashMap, VecDeque};

    struct FakeLrclibBackend {
        metadata: Mutex<VecDeque<Result<Option<ApiTrack>, LrclibError>>>,
        searches: Mutex<VecDeque<Result<Vec<ApiTrack>, LrclibError>>>,
        metadata_calls: Mutex<usize>,
        search_calls: Mutex<Vec<(String, Option<String>)>>,
    }

    impl FakeLrclibBackend {
        fn new(
            metadata: impl IntoIterator<Item = Result<Option<ApiTrack>, LrclibError>>,
            searches: impl IntoIterator<Item = Result<Vec<ApiTrack>, LrclibError>>,
        ) -> Self {
            Self {
                metadata: Mutex::new(metadata.into_iter().collect()),
                searches: Mutex::new(searches.into_iter().collect()),
                metadata_calls: Mutex::new(0),
                search_calls: Mutex::new(Vec::new()),
            }
        }
    }

    impl LrclibBackend for FakeLrclibBackend {
        fn request_interval(&self) -> Duration {
            Duration::ZERO
        }

        fn fetch_metadata<'a>(
            &'a self,
            _query: &'a LookupQuery,
            _lookup_id: jni::sys::jlong,
            _observed_retry_after: &'a mut Option<Duration>,
        ) -> Pin<Box<dyn Future<Output = Result<Option<ApiTrack>, LrclibError>> + 'a>> {
            *self.metadata_calls.lock().unwrap() += 1;
            let result = self
                .metadata
                .lock()
                .unwrap()
                .pop_front()
                .expect("controlled metadata result");
            Box::pin(std::future::ready(result))
        }

        fn search<'a>(
            &'a self,
            _query: &'a LookupQuery,
            track_name: &'a str,
            artist_name: Option<&'a str>,
            _lookup_id: jni::sys::jlong,
            _observed_retry_after: &'a mut Option<Duration>,
        ) -> Pin<Box<dyn Future<Output = Result<Vec<ApiTrack>, LrclibError>> + 'a>> {
            self.search_calls
                .lock()
                .unwrap()
                .push((track_name.to_string(), artist_name.map(ToString::to_string)));
            let result = self
                .searches
                .lock()
                .unwrap()
                .pop_front()
                .expect("controlled search result");
            Box::pin(std::future::ready(result))
        }
    }

    fn query() -> LookupQuery {
        LookupQuery::new(
            "Contract Song",
            "Contract Artist",
            "Contract Album",
            Some(123_456),
        )
        .expect("valid query")
    }

    fn response_error(body: &str) -> LrclibError {
        match map_api_response(body, &query()) {
            Ok(_) => panic!("expected LRCLIB response error"),
            Err(error) => error,
        }
    }

    fn search_track(
        id: u64,
        title: &str,
        artist: &str,
        album: &str,
        duration: f64,
        synced_lyrics: Option<&str>,
    ) -> ApiTrack {
        ApiTrack {
            id,
            track_name: Some(title.to_string()),
            artist_name: Some(artist.to_string()),
            album_name: Some(album.to_string()),
            duration: Some(duration),
            instrumental: Some(false),
            synced_lyrics: synced_lyrics.map(str::to_string),
        }
    }

    fn usable_search_track(
        id: u64,
        title: &str,
        artist: &str,
        album: &str,
        duration: f64,
    ) -> ApiTrack {
        search_track(id, title, artist, album, duration, Some("[00:01.00]Line"))
    }

    #[test]
    fn api_query_uses_lrclib_parameter_names_and_optional_values() {
        let query = query();
        let request = Client::new()
            .get("https://example.test/api/get")
            .query(&query.api_query())
            .build()
            .expect("request URL");
        let parameters = request
            .url()
            .query_pairs()
            .map(|(key, value)| (key.into_owned(), value.into_owned()))
            .collect::<HashMap<_, _>>();

        assert_eq!(
            parameters.get("track_name"),
            Some(&"Contract Song".to_string())
        );
        assert_eq!(
            parameters.get("artist_name"),
            Some(&"Contract Artist".to_string())
        );
        assert_eq!(
            parameters.get("album_name"),
            Some(&"Contract Album".to_string())
        );
        assert_eq!(parameters.get("duration"), Some(&"123.456".to_string()));
    }

    #[test]
    fn search_query_can_relax_artist_without_sending_album_or_duration() {
        let query = query();
        let request = Client::new()
            .get("https://example.test/api/search")
            .query(&query.search_query("Contract Song", None))
            .build()
            .expect("request URL");
        let parameters = request
            .url()
            .query_pairs()
            .map(|(key, value)| (key.into_owned(), value.into_owned()))
            .collect::<HashMap<_, _>>();

        assert_eq!(
            parameters.get("track_name"),
            Some(&"Contract Song".to_string())
        );
        assert!(!parameters.contains_key("artist_name"));
        assert!(!parameters.contains_key("album_name"));
        assert!(!parameters.contains_key("duration"));
    }

    #[test]
    fn invalid_optional_duration_is_not_sent() {
        let too_short = LookupQuery::new("Song", "Artist", "", Some(999)).expect("query");
        let too_long = LookupQuery::new("Song", "Artist", "", Some(3_600_001)).expect("query");

        assert_eq!(too_short.api_duration_seconds, None);
        assert_eq!(too_long.api_duration_seconds, None);
        assert_eq!(too_short.album, None);
    }

    #[test]
    fn blank_artist_is_allowed_and_uses_title_only_searches() {
        let query = LookupQuery::new("Song (Live)", " \t ", "", None).expect("query");
        let searches = build_search_queries(&query.title, &query.artist);

        assert!(searches.iter().all(|search| search.artist.is_none()));
        assert!(searches.iter().any(|search| search.title == "Song (Live)"));
        assert!(searches.iter().any(|search| search.title == "Song"));
    }

    #[tokio::test]
    async fn full_fallback_runs_all_query_rounds_then_returns_related_low_score() {
        let query = LookupQuery::new(
            "Contract Song (Live)",
            "Contract Artist",
            "Contract Album",
            Some(123_456),
        )
        .expect("query");
        let backend = FakeLrclibBackend::new(
            [Ok(None)],
            [
                Ok(vec![usable_search_track(
                    30,
                    "Contract Song (Live)",
                    "Different Performer",
                    "Other Album",
                    500.0,
                )]),
                Ok(vec![usable_search_track(
                    30,
                    "Contract Song (Live)",
                    "Different Performer",
                    "Other Album",
                    500.0,
                )]),
                Ok(Vec::new()),
                Ok(Vec::new()),
            ],
        );
        let mut observed_retry_after = None;

        let result = fetch_with_fallback_backend(&backend, &query, 0, &mut observed_retry_after)
            .await
            .expect("final related candidate");

        assert_eq!(result.id.as_deref(), Some("30"));
        assert_eq!(*backend.metadata_calls.lock().unwrap(), 1);
        assert_eq!(
            *backend.search_calls.lock().unwrap(),
            vec![
                (
                    "Contract Song (Live)".to_string(),
                    Some("Contract Artist".to_string())
                ),
                (
                    "Contract Song".to_string(),
                    Some("Contract Artist".to_string())
                ),
                ("Contract Song (Live)".to_string(), None),
                ("Contract Song".to_string(), None),
            ]
        );
    }

    #[tokio::test]
    async fn full_fallback_keeps_usable_candidate_when_later_round_fails() {
        let query = query();
        let backend = FakeLrclibBackend::new(
            [Ok(None)],
            [
                Ok(vec![usable_search_track(
                    31,
                    "Contract Song",
                    "Different Performer",
                    "Other Album",
                    500.0,
                )]),
                Err(LrclibError::parse("controlled later parse failure")),
            ],
        );
        let mut observed_retry_after = None;

        let result = fetch_with_fallback_backend(&backend, &query, 0, &mut observed_retry_after)
            .await
            .expect("retained usable lyrics");

        assert_eq!(result.id.as_deref(), Some("31"));
        assert_eq!(backend.search_calls.lock().unwrap().len(), 2);
    }

    #[test]
    fn synced_response_maps_to_native_result() {
        let body = r#"{
            "id": 42,
            "trackName": "Matched Song",
            "artistName": "Matched Artist",
            "albumName": "Matched Album",
            "duration": 123.456,
            "instrumental": false,
            "plainLyrics": "First line",
            "syncedLyrics": "[00:01.00]First line\r\n[00:02.00]Second line"
        }"#;

        let result = map_api_response(body, &query()).expect("synced lyrics result");

        assert!(result.ok);
        assert_eq!(result.source, "lrclib-rust");
        assert_eq!(result.id.as_deref(), Some("42"));
        assert_eq!(result.title.as_deref(), Some("Matched Song"));
        assert_eq!(result.artist.as_deref(), Some("Matched Artist"));
        assert_eq!(result.album.as_deref(), Some("Matched Album"));
        assert_eq!(result.duration_ms, Some(123_456));
        assert_eq!(
            result.lrc.as_deref(),
            Some("[00:01.00]First line\n[00:02.00]Second line")
        );
        assert_eq!(result.translated_lrc, None);
        assert_eq!(result.merged_lrc, result.lrc);
        assert_eq!(result.error_type, None);
        assert_eq!(result.error, None);
    }

    #[test]
    fn plain_only_response_is_not_found() {
        let body = r#"{
            "id": 42,
            "instrumental": false,
            "plainLyrics": "Only plain lyrics",
            "syncedLyrics": null
        }"#;

        let error = response_error(body);

        assert_eq!(error.error_type(), "NotFound");
    }

    #[test]
    fn nonempty_text_without_a_timeline_is_not_found() {
        let body = r#"{
            "id": 42,
            "instrumental": false,
            "syncedLyrics": "First line\nSecond line"
        }"#;

        let error = response_error(body);

        assert_eq!(error.error_type(), "NotFound");
    }

    #[test]
    fn timestamps_without_lyric_text_are_not_found() {
        let body = r#"{
            "id": 42,
            "instrumental": false,
            "syncedLyrics": "[00:01.00]\n[00:02.00]   "
        }"#;

        let error = response_error(body);

        assert_eq!(error.error_type(), "NotFound");
    }

    #[test]
    fn instrumental_response_is_not_found() {
        let body = r#"{
            "id": 42,
            "instrumental": true,
            "syncedLyrics": "[00:01.00]Unexpected line"
        }"#;

        let error = response_error(body);

        assert_eq!(error.error_type(), "NotFound");
    }

    #[test]
    fn search_selection_skips_first_result_without_lyrics() {
        let query = query();
        let tracks = vec![
            search_track(
                1,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.456,
                None,
            ),
            usable_search_track(
                2,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.456,
            ),
        ];
        let mut seen = HashSet::new();
        let mut candidates = Vec::new();

        add_search_candidates(tracks, &query, &mut seen, &mut candidates);
        let selected = take_best_candidate(&mut candidates, false).expect("search candidate");

        assert_eq!(selected.id, 2);
        assert_eq!(seen, HashSet::from([1, 2]));
    }

    #[test]
    fn search_candidates_are_deduplicated_by_lrclib_id_across_rounds() {
        let query = query();
        let mut seen = HashSet::new();
        let mut candidates = Vec::new();

        add_search_candidates(
            vec![usable_search_track(
                7,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.456,
            )],
            &query,
            &mut seen,
            &mut candidates,
        );
        add_search_candidates(
            vec![usable_search_track(
                7,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.456,
            )],
            &query,
            &mut seen,
            &mut candidates,
        );

        assert_eq!(candidates.len(), 1);
    }

    #[test]
    fn final_selection_accepts_related_candidate_below_early_threshold() {
        let query = query();
        let track = usable_search_track(
            8,
            "Contract Song",
            "Unrelated Performer",
            "Unrelated Release",
            500.0,
        );
        let scored = score_search_track(track, &query).expect("title-related candidate");
        assert!(scored.match_score.total < EARLY_ACCEPT_SCORE);
        let mut candidates = vec![scored];

        assert!(take_best_candidate(&mut candidates, true).is_none());
        assert_eq!(
            take_best_candidate(&mut candidates, false).map(|track| track.id),
            Some(8)
        );
    }

    #[test]
    fn version_and_large_duration_differences_only_lower_ranking() {
        let track = usable_search_track(
            9,
            "Contract Song (Live)",
            "Contract Artist",
            "Different Release",
            300.0,
        );

        assert!(score_search_track(track, &query()).is_some());
    }

    #[test]
    fn clearly_different_title_is_rejected_even_for_same_artist() {
        let track = usable_search_track(
            10,
            "Entirely Different Tune",
            "Contract Artist",
            "Contract Album",
            123.456,
        );

        assert!(score_search_track(track, &query()).is_none());
    }

    #[test]
    fn broader_search_error_does_not_discard_a_usable_candidate() {
        let candidate = score_search_track(
            usable_search_track(11, "Contract Song", "Unrelated Performer", "", 500.0),
            &query(),
        )
        .expect("candidate");

        let result = best_candidate_or_error(
            vec![candidate],
            &query(),
            LrclibError::parse("later response was malformed"),
            0,
        )
        .expect("retained candidate");

        assert_eq!(result.id.as_deref(), Some("11"));
    }

    #[test]
    fn broader_search_error_keeps_its_class_when_no_candidate_exists() {
        let error = match best_candidate_or_error(
            Vec::new(),
            &query(),
            LrclibError::parse("controlled parse failure"),
            0,
        ) {
            Ok(_) => panic!("expected error"),
            Err(error) => error,
        };

        assert_eq!(error.error_type(), "ParseError");
    }

    #[test]
    fn cancellation_wins_over_a_retained_candidate() {
        const LOOKUP_ID: jni::sys::jlong = 9_223_372_036_854_700_001;
        let candidate = score_search_track(
            usable_search_track(
                12,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.456,
            ),
            &query(),
        )
        .expect("candidate");
        crate::cancel_lookup(LOOKUP_ID);

        let error = match best_candidate_or_error(
            vec![candidate],
            &query(),
            LrclibError::network("controlled network failure"),
            LOOKUP_ID,
        ) {
            Ok(_) => panic!("expected cancellation"),
            Err(error) => error,
        };
        crate::clear_lookup(LOOKUP_ID);

        assert_eq!(error.error_type(), "NativeError");
        assert!(error.message().contains("canceled"));
    }

    #[tokio::test]
    async fn lookup_budget_timeout_remains_a_network_error() {
        let result = await_lookup_budget(
            tokio::time::Instant::now() + Duration::from_millis(1),
            async {
                tokio::time::sleep(Duration::from_secs(1)).await;
                Ok::<_, LrclibError>(())
            },
        )
        .await;
        let error = result.expect_err("timeout");

        assert_eq!(error.error_type(), "NetworkError");
    }

    #[test]
    fn malformed_response_is_parse_error() {
        let error = response_error("{not-json");

        assert_eq!(error.error_type(), "ParseError");
    }

    #[test]
    fn response_statuses_keep_misses_and_failures_distinct() {
        assert_eq!(
            response_disposition(StatusCode::NOT_FOUND, None).expect("404 disposition"),
            ResponseDisposition::NotFound
        );
        assert_eq!(
            response_disposition(StatusCode::OK, None).expect("success disposition"),
            ResponseDisposition::ReadBody
        );

        let rate_limited =
            response_disposition(StatusCode::TOO_MANY_REQUESTS, Some(Duration::from_secs(9)))
                .expect_err("rate limit");
        let server_error =
            response_disposition(StatusCode::SERVICE_UNAVAILABLE, None).expect_err("server error");
        let client_error =
            response_disposition(StatusCode::BAD_REQUEST, None).expect_err("client error");

        assert_eq!(rate_limited.error_type(), "RateLimited");
        assert_eq!(rate_limited.retry_after, Some(Duration::from_secs(9)));
        assert_eq!(server_error.error_type(), "NetworkError");
        assert_eq!(client_error.error_type(), "Unknown");
    }

    #[test]
    fn retry_after_seconds_are_bounded() {
        let short = reqwest::header::HeaderValue::from_static("17");
        let excessive = reqwest::header::HeaderValue::from_static("999999");

        assert_eq!(parse_retry_after(&short), Some(Duration::from_secs(17)));
        assert_eq!(parse_retry_after(&excessive), Some(LRCLIB_MAX_RETRY_AFTER));
    }

    #[test]
    fn user_agent_identifies_the_app_and_project() {
        assert!(LRCLIB_USER_AGENT.starts_with("AirLyrics/"));
        assert!(LRCLIB_USER_AGENT.contains("github.com/AirLyrics/AirLyrics"));
    }
}
