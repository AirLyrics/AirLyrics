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
const LRCLIB_MAX_INLINE_503_RETRY_AFTER: Duration = Duration::from_secs(5);
/// An overloaded LRCLIB often answers 503 without a `Retry-After` header; retry a couple of times
/// anyway, otherwise one busy moment turns into "no lyrics" for that song.
const LRCLIB_MAX_INLINE_503_RETRIES: u8 = 2;
const LRCLIB_INLINE_503_BACKOFF: Duration = Duration::from_millis(1200);
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
    retry_after_status: Option<StatusCode>,
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

    fn http(status: StatusCode, retry_after: Option<Duration>) -> Self {
        Self {
            error_type: "HttpError",
            message: format!("LRCLIB request failed with HTTP {status}"),
            retry_after,
        }
    }

    fn parse(message: impl Into<String>) -> Self {
        Self::new("ParseError", message)
    }

    pub(crate) fn native(message: impl Into<String>) -> Self {
        Self::new("NativeError", message)
    }

    pub(crate) fn error_type(&self) -> &'static str {
        self.error_type
    }

    pub(crate) fn message(&self) -> &str {
        &self.message
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
    #[serde(default)]
    plain_lyrics: Option<String>,
}

#[derive(Debug)]
struct ScoredApiTrack {
    track: ApiTrack,
    match_score: MatchScore,
    confident_early_match: bool,
    duration_delta_ms: u64,
    /// True when only a confirmed alias could establish this candidate.
    alias_used: bool,
    decision: crate::confidence::Decision,
}

#[derive(Default)]
struct SearchCandidateStats {
    returned: usize,
    accepted: usize,
    duplicate: usize,
    rejected_instrumental: usize,
    rejected_lyrics: usize,
    rejected_title: usize,
    rejected_low_confidence: usize,
}

enum SearchCandidateRejection {
    Instrumental,
    NoUsableLyrics,
    Title,
    /// Passed the title gate but not the confidence policy: not enough independent evidence.
    LowConfidence,
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
        observed_retry_after: &'a mut Option<(Duration, StatusCode)>,
    ) -> Pin<Box<dyn Future<Output = Result<Option<ApiTrack>, LrclibError>> + 'a>>;

    fn search<'a>(
        &'a self,
        query: &'a LookupQuery,
        track_name: &'a str,
        artist_name: Option<&'a str>,
        lookup_id: jni::sys::jlong,
        observed_retry_after: &'a mut Option<(Duration, StatusCode)>,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<ApiTrack>, LrclibError>> + 'a>>;
}

struct HttpLrclibBackend;

impl LrclibBackend for HttpLrclibBackend {
    fn fetch_metadata<'a>(
        &'a self,
        query: &'a LookupQuery,
        lookup_id: jni::sys::jlong,
        observed_retry_after: &'a mut Option<(Duration, StatusCode)>,
    ) -> Pin<Box<dyn Future<Output = Result<Option<ApiTrack>, LrclibError>> + 'a>> {
        Box::pin(fetch_metadata_track(query, lookup_id, observed_retry_after))
    }

    fn search<'a>(
        &'a self,
        query: &'a LookupQuery,
        track_name: &'a str,
        artist_name: Option<&'a str>,
        lookup_id: jni::sys::jlong,
        observed_retry_after: &'a mut Option<(Duration, StatusCode)>,
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
    let retry_after = observed_retry_after.or_else(|| {
        result.as_ref().err().and_then(|error| {
            error.retry_after.map(|duration| {
                let status = if error.error_type == "RateLimited" {
                    StatusCode::TOO_MANY_REQUESTS
                } else {
                    StatusCode::SERVICE_UNAVAILABLE
                };
                (duration, status)
            })
        })
    });
    if let Some((retry_after, status)) = retry_after {
        schedule.retry_after_until = Some(now + retry_after);
        schedule.retry_after_status = Some(status);
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
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
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
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
) -> Result<NativeResult, LrclibError> {
    let trace = LookupTrace::new("lrclib-test");
    fetch_with_fallback_backend_traced(backend, query, lookup_id, observed_retry_after, &trace)
        .await
}

async fn fetch_with_fallback_backend_traced<B: LrclibBackend>(
    backend: &B,
    query: &LookupQuery,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
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
            // Even the exact endpoint goes through the common candidate policy. A
            // related but weak match must not prevent broader searches.
            let stats =
                add_search_candidates(vec![track], query, &mut seen_track_ids, &mut candidates);
            trace_lrclib_search(trace, 0, true, started, &stats, &candidates, request_count);
            if let Some(track) = take_best_candidate(&mut candidates, true) {
                return map_api_track(track, query);
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
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
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
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
) -> Result<Vec<ApiTrack>, LrclibError> {
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    let mut tracks = search_by_fields(
        query,
        track_name,
        artist_name,
        lookup_id,
        observed_retry_after,
    )
    .await?;

    // LRCLIB's field search is relevance-ranked and only returns a handful of rows, which can hide
    // the right recording for CJK titles (e.g. "不該" with several fan uploads). When no candidate is
    // close to the played duration, ask again with free text, which ranks differently, and merge.
    let has_close_duration = query.duration_ms.is_some_and(|target_ms| {
        tracks.iter().any(|track| {
            track
                .duration
                .map(|seconds| ((seconds * 1000.0) - target_ms as f64).abs() <= 1500.0)
                .unwrap_or(false)
        })
    });
    if !has_close_duration {
        // Prefer the played artist spelling: LRCLIB indexes Latin artist names more reliably than
        // the localised ones the lyrics source uses.
        let artist_for_text = if query.artist.trim().is_empty() {
            artist_name.unwrap_or_default()
        } else {
            query.artist.as_str()
        };
        if !artist_for_text.trim().is_empty() {
            let text = format!("{track_name} {artist_for_text}");
            if let Ok(mut extra) = search_by_text(&text, lookup_id, observed_retry_after).await {
                tracks.retain(|track| !extra.iter().any(|other| other.id == track.id));
                tracks.append(&mut extra);
            }
        }
    }

    Ok(tracks)
}

async fn search_by_fields(
    query: &LookupQuery,
    track_name: &str,
    artist_name: Option<&str>,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
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

async fn search_by_text(
    text: &str,
    lookup_id: jni::sys::jlong,
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
) -> Result<Vec<ApiTrack>, LrclibError> {
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    let request = client()?.get(LRCLIB_SEARCH_URL).query(&[("q", text)]);
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
    observed_retry_after: &mut Option<(Duration, StatusCode)>,
) -> Result<Option<String>, LrclibError> {
    let mut request = request;
    let mut retried_503: u8 = 0;
    loop {
        let retry_request = (retried_503 < LRCLIB_MAX_INLINE_503_RETRIES)
            .then(|| request.try_clone())
            .flatten();
        let response = await_reqwest(request.send(), lookup_id).await?;
        check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;

        let status = response.status();
        let retry_after = (status == StatusCode::TOO_MANY_REQUESTS
            || status == StatusCode::SERVICE_UNAVAILABLE)
            .then(|| {
                response
                    .headers()
                    .get(RETRY_AFTER)
                    .and_then(parse_retry_after)
                    .or_else(|| {
                        (status == StatusCode::TOO_MANY_REQUESTS)
                            .then_some(LRCLIB_DEFAULT_RETRY_AFTER)
                    })
            })
            .flatten();
        if let Some(retry_after) = retry_after {
            *observed_retry_after = Some((retry_after, status));
        }
        if status == StatusCode::SERVICE_UNAVAILABLE {
            let delay =
                retry_after.unwrap_or(LRCLIB_INLINE_503_BACKOFF * u32::from(1 + retried_503));
            if let Some(next_request) = retry_request {
                if delay <= LRCLIB_MAX_INLINE_503_RETRY_AFTER {
                    wait_between_requests(lookup_id, delay.max(LRCLIB_REQUEST_INTERVAL)).await?;
                    request = next_request;
                    retried_503 += 1;
                    continue;
                }
            }
        }
        if response_disposition(status, retry_after)? == ResponseDisposition::NotFound {
            if retried_503 > 0 {
                *observed_retry_after = None;
            }
            return Ok(None);
        }

        let body = await_reqwest(response.text(), lookup_id).await?;
        check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
        if retried_503 > 0 {
            *observed_retry_after = None;
        }
        return Ok(Some(body));
    }
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
        return Err(LrclibError::http(status, retry_after));
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
    if let Some(status) = error.status() {
        if status == StatusCode::TOO_MANY_REQUESTS {
            LrclibError::rate_limited(LRCLIB_DEFAULT_RETRY_AFTER)
        } else {
            LrclibError::http(status, None)
        }
    } else if error.is_decode() {
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
            "round={} outcome=ok artistFilter={} requestMs={} returned={} accepted={} duplicate={} rejectedInstrumental={} rejectedLyrics={} rejectedTitle={} rejectedLowConfidence={} pool={} topScore={} topTitleScore={} topArtistScore={} topEarly={} topAliasUsed={} topDecision={:?} requestsUsed={}",
            round,
            artist_filter,
            started.elapsed().as_millis(),
            stats.returned,
            stats.accepted,
            stats.duplicate,
            stats.rejected_instrumental,
            stats.rejected_lyrics,
            stats.rejected_title,
            stats.rejected_low_confidence,
            candidates.len(),
            format_lrclib_score(best.map(|candidate| candidate.match_score.total)),
            format_lrclib_score(best.map(|candidate| candidate.match_score.title_similarity)),
            format_lrclib_score(
                best.and_then(|candidate| candidate.match_score.artist_similarity)
            ),
            best.is_some_and(|candidate| candidate.confident_early_match),
            best.is_some_and(|candidate| candidate.alias_used),
            best.map(|candidate| candidate.decision),
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
        match score_track_with_reason(track, query) {
            Ok(candidate) => {
                stats.accepted += 1;
                candidates.push(candidate);
            }
            Err(SearchCandidateRejection::Instrumental) => stats.rejected_instrumental += 1,
            Err(SearchCandidateRejection::NoUsableLyrics) => stats.rejected_lyrics += 1,
            Err(SearchCandidateRejection::Title) => stats.rejected_title += 1,
            Err(SearchCandidateRejection::LowConfidence) => stats.rejected_low_confidence += 1,
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
    score_track_with_reason(track, query).ok()
}

fn score_track_with_reason(
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
    let Some(assessment) = crate::confidence::assess(query.match_metadata(), candidate_metadata)
    else {
        // Distinguish "not even the same song" from "same song, not enough evidence".
        let Some(title_score) = score_candidate(query.match_metadata(), candidate_metadata) else {
            return Err(SearchCandidateRejection::Title);
        };
        crate::review::park(
            &crate::review::query_key(&query.title, &query.artist, query.duration_ms),
            crate::review::ParkedCandidate {
                title: candidate_metadata.title.to_owned(),
                artist: candidate_metadata.artist.to_owned(),
                album: candidate_metadata.album.to_owned(),
                duration_ms: candidate_duration_ms,
                provider: "lrclib",
                score: title_score.total,
                reason: crate::confidence::rejection_reason(
                    query.match_metadata(),
                    candidate_metadata,
                ),
                alias_hit: crate::aliases::lookup(&query.title, &query.artist).is_some(),
            },
        );
        return Err(SearchCandidateRejection::LowConfidence);
    };
    let match_score = assessment.score;
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
        alias_used: assessment.alias_used,
        decision: assessment.decision,
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
            return Err(match schedule.retry_after_status {
                Some(StatusCode::SERVICE_UNAVAILABLE) => LrclibError::http(
                    StatusCode::SERVICE_UNAVAILABLE,
                    Some(retry_after_until - now),
                ),
                _ => LrclibError::rate_limited(retry_after_until - now),
            });
        }
        schedule.retry_after_until = None;
        schedule.retry_after_status = None;
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
            _observed_retry_after: &'a mut Option<(Duration, StatusCode)>,
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
            _observed_retry_after: &'a mut Option<(Duration, StatusCode)>,
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
            plain_lyrics: None,
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
    async fn full_fallback_runs_all_query_rounds_then_reports_no_match() {
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

        let result =
            fetch_with_fallback_backend(&backend, &query, 0, &mut observed_retry_after).await;

        assert!(
            result.is_err(),
            "no corroborated candidate exists, so the lookup must report a miss"
        );
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
    async fn rejected_candidate_does_not_stop_the_search_and_later_failure_is_reported() {
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

        let result =
            fetch_with_fallback_backend(&backend, &query, 0, &mut observed_retry_after).await;

        assert!(
            result.is_err(),
            "the only candidate carries no corroborating evidence, so the lookup reports a miss"
        );
        // The rejected candidate did not stop the broader rounds from running.
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
    fn review_candidates_are_parked_and_not_used_as_lyrics() {
        let _guard = crate::review::test_lock();
        crate::review::clear();
        let query = query();
        let track = usable_search_track(50, "Contract Song", "Unrelated Performer", "", 500.0);

        assert!(
            score_search_track(track, &query).is_none(),
            "a title-only candidate must not be used as lyrics"
        );

        let parsed: serde_json::Value =
            serde_json::from_str(&crate::review::take_json()).expect("parked payload");
        let candidates = parsed["candidates"].as_array().expect("candidates");
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0]["title"], serde_json::json!("Contract Song"));
        assert_eq!(candidates[0]["provider"], serde_json::json!("lrclib"));
        assert_eq!(candidates[0]["reason"], serde_json::json!("weak_evidence"));
    }

    #[test]
    fn a_live_variant_is_parked_with_a_version_conflict_reason() {
        let _guard = crate::review::test_lock();
        crate::review::clear();
        let query = query();
        let track = usable_search_track(51, "Contract Song (Live)", "Contract Artist", "", 123.5);

        assert!(score_search_track(track, &query).is_none());

        let parsed: serde_json::Value =
            serde_json::from_str(&crate::review::take_json()).expect("parked payload");
        let candidates = parsed["candidates"].as_array().expect("candidates");
        assert_eq!(candidates.len(), 1);
        assert_eq!(
            candidates[0]["reason"],
            serde_json::json!("version_conflict")
        );
    }

    #[tokio::test]
    async fn radio_love_song_english_lookalike_is_never_selected() {
        // The reported failure: Apple Music shows "Radio Love Song - Karen Mok" (莫文蔚《电台情歌》)
        // and an unrelated English song with a similar title must not be shown.
        let query =
            LookupQuery::new("Radio Love Song", "Karen Mok", "", Some(242_000)).expect("query");
        let lookalike = || {
            vec![usable_search_track(
                40,
                "Radio Love Songs",
                "The Highways",
                "Long Drives",
                214.0,
            )]
        };
        let backend = FakeLrclibBackend::new(
            [Ok(None)],
            [
                Ok(lookalike()),
                Ok(lookalike()),
                Ok(lookalike()),
                Ok(lookalike()),
            ],
        );
        let mut observed_retry_after = None;

        let result =
            fetch_with_fallback_backend(&backend, &query, 0, &mut observed_retry_after).await;

        assert!(
            result.is_err(),
            "a title-only English lookalike must never be auto-selected"
        );
    }

    #[tokio::test]
    async fn radio_love_song_alias_selects_the_localized_release() {
        let query =
            LookupQuery::new("Radio Love Song", "Karen Mok", "", Some(242_000)).expect("query");
        let backend = FakeLrclibBackend::new(
            [Ok(None)],
            [
                // The alias keywords are searched first and reach the Chinese release.
                Ok(vec![usable_search_track(
                    41,
                    "电台情歌",
                    "莫文蔚",
                    "",
                    241.0,
                )]),
                Ok(vec![usable_search_track(
                    40,
                    "Radio Love Songs",
                    "The Highways",
                    "Long Drives",
                    214.0,
                )]),
                Ok(Vec::new()),
                Ok(Vec::new()),
            ],
        );
        let mut observed_retry_after = None;

        let result = fetch_with_fallback_backend(&backend, &query, 0, &mut observed_retry_after)
            .await
            .expect("alias lookup finds the localized release");

        assert_eq!(result.id.as_deref(), Some("41"));
        assert_eq!(
            backend.search_calls.lock().unwrap().first().cloned(),
            Some(("电台情歌".to_string(), Some("莫文蔚".to_string())))
        );
    }

    #[test]
    fn title_only_candidate_is_never_selected() {
        let query = query();
        let track = usable_search_track(
            8,
            "Contract Song",
            "Unrelated Performer",
            "Unrelated Release",
            500.0,
        );

        assert!(
            score_search_track(track, &query).is_none(),
            "a title-only coincidence must not enter the candidate pool"
        );
    }

    #[test]
    fn final_selection_accepts_corroborated_candidate() {
        let query = query();
        let track = usable_search_track(
            8,
            "Contract Song",
            "Contract Artist",
            "Other Release",
            123.5,
        );
        let scored = score_search_track(track, &query).expect("corroborated candidate");
        let mut candidates = vec![scored];

        assert_eq!(
            take_best_candidate(&mut candidates, false).map(|track| track.id),
            Some(8)
        );
    }

    #[test]
    fn live_variant_with_duration_mismatch_is_not_selected() {
        let track = usable_search_track(
            9,
            "Contract Song (Live)",
            "Contract Artist",
            "Different Release",
            300.0,
        );

        assert!(
            score_search_track(track, &query()).is_none(),
            "a live recording must not stand in for the studio version"
        );
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
            usable_search_track(
                11,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.5,
            ),
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
        let server_error = response_disposition(
            StatusCode::SERVICE_UNAVAILABLE,
            Some(Duration::from_secs(3)),
        )
        .expect_err("server error");
        let client_error =
            response_disposition(StatusCode::BAD_REQUEST, None).expect_err("client error");

        assert_eq!(rate_limited.error_type(), "RateLimited");
        assert_eq!(rate_limited.retry_after, Some(Duration::from_secs(9)));
        assert_eq!(server_error.error_type(), "HttpError");
        assert_eq!(server_error.retry_after, Some(Duration::from_secs(3)));
        assert_eq!(client_error.error_type(), "HttpError");
    }

    #[tokio::test]
    async fn service_unavailable_retries_once_after_retry_after() {
        let server = crate::test_http::Server::new(vec![
            "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        ]);
        let request = crate::test_http::client().get(format!("{}/api/get", server.url));
        let mut observed_retry_after = None;
        let result = tokio::time::timeout(
            Duration::from_secs(4),
            fetch_response_body(request, 0, &mut observed_retry_after),
        )
        .await
        .expect("lookup completed")
        .expect("retry reached 404");

        assert!(result.is_none());
        assert!(observed_retry_after.is_none());
        server.finish();
    }

    #[test]
    fn retry_after_seconds_are_bounded() {
        let short = reqwest::header::HeaderValue::from_static("17");
        let excessive = reqwest::header::HeaderValue::from_static("999999");

        assert_eq!(parse_retry_after(&short), Some(Duration::from_secs(17)));
        assert_eq!(parse_retry_after(&excessive), Some(LRCLIB_MAX_RETRY_AFTER));
    }

    #[test]
    fn service_unavailable_backoff_keeps_http_classification() {
        let mut schedule = RequestSchedule {
            next_request_at: None,
            retry_after_until: Some(Instant::now() + Duration::from_secs(2)),
            retry_after_status: Some(StatusCode::SERVICE_UNAVAILABLE),
        };
        let error = enforce_request_schedule(&mut schedule, 0).expect_err("backoff active");
        assert_eq!(error.error_type(), "HttpError");
        assert!(error.message().contains("HTTP 503"));

        schedule.retry_after_status = Some(StatusCode::TOO_MANY_REQUESTS);
        let error = enforce_request_schedule(&mut schedule, 0).expect_err("rate limit active");
        assert_eq!(error.error_type(), "RateLimited");
    }

    #[test]
    fn user_agent_identifies_the_app_and_project() {
        assert!(LRCLIB_USER_AGENT.starts_with("AirLyrics/"));
        assert!(LRCLIB_USER_AGENT.contains("github.com/AirLyrics/AirLyrics"));
    }
    #[tokio::test]
    async fn exact_endpoint_cannot_bypass_shared_matching_policy() {
        let query = query();
        let backend = FakeLrclibBackend::new(
            [Ok(Some(usable_search_track(
                1,
                "Unrelated",
                "Contract Artist",
                "Contract Album",
                123.456,
            )))],
            [Ok(vec![usable_search_track(
                2,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.456,
            )])],
        );
        let result = fetch_with_fallback_backend(&backend, &query, 0, &mut None)
            .await
            .unwrap();
        assert_eq!(result.id.as_deref(), Some("2"));
        assert_eq!(backend.search_calls.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn confident_exact_endpoint_match_skips_search() {
        let query = query();
        let backend = FakeLrclibBackend::new(
            [Ok(Some(usable_search_track(
                1,
                "Contract Song",
                "Contract Artist",
                "Contract Album",
                123.456,
            )))],
            [],
        );
        let result = fetch_with_fallback_backend(&backend, &query, 0, &mut None)
            .await
            .unwrap();
        assert_eq!(result.id.as_deref(), Some("1"));
        assert!(backend.search_calls.lock().unwrap().is_empty());
    }
}

pub(crate) fn manual(
    request: &crate::manual::Request,
    lookup: i64,
) -> Result<serde_json::Value, String> {
    use crate::manual::{ranked, Track};
    let run = || -> Result<serde_json::Value, LrclibError> {
        let mut schedule = acquire_request_schedule(lookup)?;
        enforce_request_schedule(&mut schedule, lookup)?;
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| LrclibError::native("runtime"))?;
        let mut retry = None;
        let t = &request.track;
        let result = runtime.block_on(async {
            let req = if request.operation == "search" {
                let mut req = client()?.get(LRCLIB_SEARCH_URL);
                if !t.title.is_empty() {
                    req = req.query(&[("track_name", &t.title)]);
                }
                if !t.artist.is_empty() {
                    req = req.query(&[("artist_name", &t.artist)]);
                }
                if !t.album.is_empty() {
                    req = req.query(&[("album_name", &t.album)]);
                }
                req
            } else {
                let id =
                    t.id.parse::<u64>()
                        .map_err(|_| LrclibError::parse("invalid track ID"))?;
                client()?.get(format!("{LRCLIB_GET_URL}/{id}"))
            };
            let body = fetch_response_body(req, lookup, &mut retry)
                .await?
                .ok_or_else(|| LrclibError::not_found("track unavailable"))?;
            if request.operation == "search" {
                let tracks: Vec<ApiTrack> = serde_json::from_str(&body)
                    .map_err(|_| LrclibError::parse("invalid candidates"))?;
                let tracks = tracks
                    .into_iter()
                    .map(|v| Track {
                        id: v.id.to_string(),
                        title: v.track_name.unwrap_or_default(),
                        artist: v.artist_name.unwrap_or_default(),
                        album: v.album_name.unwrap_or_default(),
                        duration_ms: v
                            .duration
                            .and_then(duration_seconds_to_ms)
                            .unwrap_or_default(),
                        score: 0.0,
                    })
                    .collect();
                Ok(serde_json::json!({"ok":true,"candidates":ranked(tracks,t)}))
            } else {
                let v: ApiTrack =
                    serde_json::from_str(&body).map_err(|_| LrclibError::parse("invalid track"))?;
                manual_track_content(v, t)
            }
        });
        schedule.next_request_at = Some(Instant::now() + LRCLIB_REQUEST_INTERVAL);
        if let Some((delay, status)) = retry {
            schedule.retry_after_until = Some(Instant::now() + delay);
            schedule.retry_after_status = Some(status);
        }
        result
    };
    run().map_err(|e| e.error_type().to_owned())
}

fn manual_track_content(
    v: ApiTrack,
    t: &crate::manual::Track,
) -> Result<serde_json::Value, LrclibError> {
    if v.id.to_string() != t.id {
        return Err(LrclibError::parse("track ID mismatch"));
    }
    if v.synced_lyrics
        .as_deref()
        .is_none_or(|text| text.trim().is_empty())
        && v.plain_lyrics
            .as_deref()
            .is_none_or(|text| text.trim().is_empty())
    {
        return Ok(crate::manual::unavailable(
            crate::manual::UnavailableReason::SourceEmpty,
        ));
    }
    let Some(lrc) = v.synced_lyrics.and_then(normalize_usable_lrc) else {
        return Ok(crate::manual::unavailable(
            crate::manual::UnavailableReason::NoTimeline,
        ));
    };
    Ok(t.lyrics("lrclib-rust", lrc, None))
}

#[cfg(test)]
mod manual_tests {
    use super::*;
    #[test]
    fn manual_reasons_preserve_instrumental_timing_and_identity() {
        let t = crate::manual::Track {
            id: "1".into(),
            title: "Song".into(),
            artist: "".into(),
            album: "".into(),
            duration_ms: 0,
            score: 0.0,
        };
        for (body, reason) in [
            (r#"{"id":1,"instrumental":true}"#, "source_empty"),
            (r#"{"id":1}"#, "source_empty"),
            (r#"{"id":1,"plainLyrics":"Untimed lyrics"}"#, "no_timeline"),
            (r#"{"id":1,"syncedLyrics":"untimed"}"#, "no_timeline"),
            (r#"{"id":1,"syncedLyrics":"[00:01.00]"}"#, "no_timeline"),
        ] {
            let v = serde_json::from_str(body).unwrap();
            assert_eq!(manual_track_content(v, &t).unwrap()["reason"], reason);
        }
        let v = serde_json::from_str(r#"{"id":1,"syncedLyrics":"[00:01.00]Hello"}"#).unwrap();
        assert!(manual_track_content(v, &t).unwrap()["lrc"]
            .as_str()
            .unwrap()
            .contains("Hello"));
        let v = serde_json::from_str(r#"{"id":2,"instrumental":true}"#).unwrap();
        assert_eq!(
            manual_track_content(v, &t).unwrap_err().error_type(),
            "ParseError"
        );
    }
}
