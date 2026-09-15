use crate::{
    begin_lookup_cancellation, check_lookup_cancelled, normalize_optional_lrc, NativeResult,
};
use reqwest::header::RETRY_AFTER;
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use std::future::Future;
use std::sync::{Mutex, MutexGuard, OnceLock, TryLockError};
use std::time::{Duration, Instant};

const LRCLIB_GET_URL: &str = "https://lrclib.net/api/get";
const LRCLIB_LOOKUP_TIMEOUT: Duration = Duration::from_secs(8);
const LRCLIB_GATE_POLL_INTERVAL: Duration = Duration::from_millis(25);
const LRCLIB_CANCELLATION_POLL_INTERVAL: Duration = Duration::from_millis(50);
const LRCLIB_REQUEST_INTERVAL: Duration = Duration::from_millis(250);
const LRCLIB_DEFAULT_RETRY_AFTER: Duration = Duration::from_secs(60);
const LRCLIB_MAX_RETRY_AFTER: Duration = Duration::from_secs(24 * 60 * 60);
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

        if title.is_empty() || artist.is_empty() {
            return Err(LrclibError::not_found(
                "LRCLIB lookup requires both title and artist",
            ));
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

#[derive(Deserialize)]
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

    let mut schedule = acquire_request_schedule(lookup_id)?;
    enforce_request_schedule(&mut schedule, lookup_id)?;

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| LrclibError::native(format!("failed to create tokio runtime: {error}")))?;

    let result = runtime.block_on(async {
        tokio::time::timeout(LRCLIB_LOOKUP_TIMEOUT, fetch_from_api(&query, lookup_id))
            .await
            .unwrap_or_else(|_| Err(LrclibError::network("LRCLIB lookup timed out")))
    });

    let now = Instant::now();
    schedule.next_request_at = Some(now + LRCLIB_REQUEST_INTERVAL);
    if let Err(error) = &result {
        if let Some(retry_after) = error.retry_after {
            schedule.retry_after_until = Some(now + retry_after);
        }
    }

    result
}

async fn fetch_from_api(
    query: &LookupQuery,
    lookup_id: jni::sys::jlong,
) -> Result<NativeResult, LrclibError> {
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    let request = client()?.get(LRCLIB_GET_URL).query(&query.api_query());
    let response = await_reqwest(request.send(), lookup_id).await?;
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;

    let status = response.status();
    if status == StatusCode::NOT_FOUND {
        return Err(LrclibError::not_found("LRCLIB track not found"));
    }
    if status == StatusCode::TOO_MANY_REQUESTS {
        let retry_after = response
            .headers()
            .get(RETRY_AFTER)
            .and_then(parse_retry_after)
            .unwrap_or(LRCLIB_DEFAULT_RETRY_AFTER);
        return Err(LrclibError::rate_limited(retry_after));
    }
    if !status.is_success() {
        let message = format!("LRCLIB request failed with HTTP {status}");
        return if status.is_server_error() {
            Err(LrclibError::network(message))
        } else {
            Err(LrclibError::unknown(message))
        };
    }

    let body = await_reqwest(response.text(), lookup_id).await?;
    check_lookup_cancelled(lookup_id).map_err(LrclibError::native)?;
    map_api_response(&body, query)
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

fn map_api_response(body: &str, query: &LookupQuery) -> Result<NativeResult, LrclibError> {
    let track: ApiTrack = serde_json::from_str(body).map_err(|error| {
        LrclibError::parse(format!("failed to decode LRCLIB response: {error}"))
    })?;

    if track.instrumental.unwrap_or(false) {
        return Err(LrclibError::not_found(
            "LRCLIB track is instrumental and has no timed lyrics",
        ));
    }

    let lrc = track
        .synced_lyrics
        .and_then(normalize_optional_lrc)
        .ok_or_else(|| LrclibError::not_found("LRCLIB returned no synced lyrics"))?;
    let title = track
        .track_name
        .and_then(|value| non_empty(normalize_query_part(&value)))
        .unwrap_or_else(|| query.title.clone());
    let artist = track
        .artist_name
        .and_then(|value| non_empty(normalize_query_part(&value)))
        .unwrap_or_else(|| query.artist.clone());
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
        artist: Some(artist),
        album,
        duration_ms,
        lrc: Some(lrc.clone()),
        translated_lrc: None,
        merged_lrc: Some(lrc),
        error_type: None,
        error: None,
    })
}

fn client() -> Result<&'static Client, LrclibError> {
    if let Some(client) = LRCLIB_CLIENT.get() {
        return Ok(client);
    }

    let client = Client::builder()
        .user_agent(LRCLIB_USER_AGENT)
        .connect_timeout(Duration::from_secs(4))
        .timeout(LRCLIB_LOOKUP_TIMEOUT)
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
    let deadline = Instant::now() + LRCLIB_LOOKUP_TIMEOUT;
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
    use std::collections::HashMap;

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
    fn invalid_optional_duration_is_not_sent() {
        let too_short = LookupQuery::new("Song", "Artist", "", Some(999)).expect("query");
        let too_long = LookupQuery::new("Song", "Artist", "", Some(3_600_001)).expect("query");

        assert_eq!(too_short.api_duration_seconds, None);
        assert_eq!(too_long.api_duration_seconds, None);
        assert_eq!(too_short.album, None);
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
    fn malformed_response_is_parse_error() {
        let error = response_error("{not-json");

        assert_eq!(error.error_type(), "ParseError");
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
