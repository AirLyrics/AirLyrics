use crate::diagnostics::LookupTrace;
use crate::lrc;
#[cfg(test)]
use crate::matching::EARLY_ACCEPT_SCORE;
use crate::matching::{
    build_search_queries, is_confident_early_match, normalize_usable_lrc, score_candidate,
    MatchMetadata, MatchScore, SearchQuery,
};
use crate::{begin_lookup_cancellation, check_lookup_cancelled, NativeResult};
use musixmatch_inofficial::models::{
    SortOrder, Subtitle, SubtitleFormat, Track, TrackId, TranslationList,
};
use musixmatch_inofficial::{Error as MusixmatchApiError, Musixmatch};
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet, VecDeque};
use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use unicode_normalization::UnicodeNormalization;

static MUSIXMATCH_CLIENT: OnceLock<Musixmatch> = OnceLock::new();

const MUSIXMATCH_LOOKUP_TIMEOUT: Duration = Duration::from_secs(15);
const MUSIXMATCH_REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const MUSIXMATCH_CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const MUSIXMATCH_CANCELLATION_POLL_INTERVAL: Duration = Duration::from_millis(50);
const MUSIXMATCH_MAX_REQUESTS: usize = 16;
const MUSIXMATCH_MAX_TRACK_ATTEMPTS: usize = 3;
const MUSIXMATCH_MAX_EARLY_TRACK_ATTEMPTS: usize = 2;
const MUSIXMATCH_MAX_MATCHER_SUBTITLE_REQUESTS: usize = 4;
const MUSIXMATCH_MATCHER_REQUEST_RESERVE: usize = 2;
const MUSIXMATCH_SEARCH_PAGE_SIZE: u8 = 15;

#[derive(Clone, Debug)]
struct ScoredTrack {
    track: Track,
    match_score: MatchScore,
    confident_early_match: bool,
}

#[derive(Debug)]
struct FoundLyrics {
    track: Option<Track>,
    subtitle_id: u64,
    lrc: String,
}

#[derive(Debug)]
struct FetchedSubtitle {
    subtitle_id: u64,
    lrc: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum FailureKind {
    Cancelled,
    NeedCredential,
    RateLimited,
    Restricted,
    Network,
    Parse,
    NotFound,
    BudgetExhausted,
    Unknown,
}

#[derive(Debug)]
struct LookupFailure {
    kind: FailureKind,
    context: String,
    detail: String,
}

impl LookupFailure {
    fn new(kind: FailureKind, context: impl Into<String>, detail: impl Into<String>) -> Self {
        Self {
            kind,
            context: context.into(),
            detail: detail.into(),
        }
    }

    fn cancelled(detail: impl Into<String>) -> Self {
        Self::new(FailureKind::Cancelled, "lookup", detail)
    }

    fn not_found(context: impl Into<String>, detail: impl Into<String>) -> Self {
        Self::new(FailureKind::NotFound, context, detail)
    }

    fn budget(context: impl Into<String>) -> Self {
        Self::new(
            FailureKind::BudgetExhausted,
            context,
            "request budget exhausted",
        )
    }

    fn lookup_timeout() -> Self {
        Self::new(FailureKind::Network, "lookup", "overall lookup timed out")
    }

    fn native(context: impl Into<String>, detail: impl Into<String>) -> Self {
        Self::new(FailureKind::Unknown, context, detail)
    }

    fn from_api(context: impl Into<String>, error: MusixmatchApiError) -> Self {
        let kind = match &error {
            MusixmatchApiError::Ratelimit => FailureKind::RateLimited,
            MusixmatchApiError::TokenExpired
            | MusixmatchApiError::MissingCredentials
            | MusixmatchApiError::WrongCredentials => FailureKind::NeedCredential,
            MusixmatchApiError::MusixmatchError { status_code, .. } => match status_code {
                401 => FailureKind::NeedCredential,
                403 => FailureKind::Restricted,
                404 => FailureKind::NotFound,
                429 => FailureKind::RateLimited,
                500..=599 => FailureKind::Network,
                _ => FailureKind::Unknown,
            },
            MusixmatchApiError::NotFound => FailureKind::NotFound,
            MusixmatchApiError::NotAvailable => FailureKind::Restricted,
            MusixmatchApiError::InvalidData(_) => FailureKind::Parse,
            MusixmatchApiError::Http(_) => FailureKind::Network,
            _ => FailureKind::Unknown,
        };
        Self::new(kind, context, error.to_string())
    }

    fn is_immediate(&self) -> bool {
        matches!(
            self.kind,
            FailureKind::Cancelled | FailureKind::NeedCredential | FailureKind::RateLimited
        )
    }
}

impl fmt::Display for LookupFailure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        let classification = match self.kind {
            FailureKind::Cancelled => "lookup canceled",
            FailureKind::NeedCredential => "musixmatch credential required",
            FailureKind::RateLimited => "musixmatch rate limited",
            FailureKind::Restricted => "musixmatch restricted lyrics",
            FailureKind::Network => "musixmatch network error",
            FailureKind::Parse => "musixmatch parse error",
            FailureKind::NotFound => "musixmatch content not found",
            FailureKind::BudgetExhausted => "musixmatch request budget exhausted",
            FailureKind::Unknown => "musixmatch lookup error",
        };
        write!(
            formatter,
            "{classification}: {}: {}",
            self.context, self.detail
        )
    }
}

#[derive(Default)]
struct FailureLog {
    failures: Vec<LookupFailure>,
}

impl FailureLog {
    fn record(&mut self, failure: LookupFailure) -> Result<(), LookupFailure> {
        if failure.is_immediate() {
            Err(failure)
        } else {
            self.failures.push(failure);
            Ok(())
        }
    }

    fn into_final(self, context: impl Into<String>) -> LookupFailure {
        const PRIORITY: &[FailureKind] = &[
            FailureKind::Cancelled,
            FailureKind::NeedCredential,
            FailureKind::RateLimited,
            FailureKind::Network,
            FailureKind::Parse,
            FailureKind::Restricted,
            FailureKind::Unknown,
        ];

        for kind in PRIORITY {
            if let Some(failure) = self.failures.iter().find(|failure| failure.kind == *kind) {
                return LookupFailure::new(failure.kind, &failure.context, &failure.detail);
            }
        }

        LookupFailure::not_found(context, "no usable synchronized lyrics")
    }
}

#[derive(Debug)]
struct RequestBudget {
    used: usize,
    limit: usize,
}

impl RequestBudget {
    fn new(limit: usize) -> Self {
        Self { used: 0, limit }
    }

    fn spend(&mut self, context: &str) -> Result<(), LookupFailure> {
        if self.used >= self.limit {
            Err(LookupFailure::budget(context))
        } else {
            self.used += 1;
            Ok(())
        }
    }

    fn exhausted(&self) -> bool {
        self.used >= self.limit
    }

    fn used(&self) -> usize {
        self.used
    }

    fn remaining(&self) -> usize {
        self.limit.saturating_sub(self.used)
    }

    fn can_spend_reserving(&self, reserved: usize) -> bool {
        self.remaining() > reserved
    }
}

#[derive(Default)]
struct CandidateBatchStats {
    returned: usize,
    accepted: usize,
    duplicates: usize,
    rejected_title: usize,
}

#[derive(Default)]
struct CandidatePool {
    tracks: HashMap<u64, ScoredTrack>,
}

impl CandidatePool {
    fn add(&mut self, track: Track, query: MatchMetadata<'_>) -> CandidateBatchStats {
        let mut stats = CandidateBatchStats {
            returned: 1,
            ..CandidateBatchStats::default()
        };
        let candidate_duration_ms =
            (track.track_length > 0).then_some((track.track_length as u64).saturating_mul(1000));
        let candidate = MatchMetadata {
            title: &track.track_name,
            artist: &track.artist_name,
            album: &track.album_name,
            duration_ms: candidate_duration_ms,
        };
        let Some(match_score) = score_candidate(query, candidate) else {
            stats.rejected_title = 1;
            return stats;
        };

        let confident_early_match = is_confident_early_match(&match_score);
        let already_present = self.tracks.contains_key(&track.track_id);

        let should_replace = self.tracks.get(&track.track_id).is_some_and(|existing| {
            (track.has_subtitles && !existing.track.has_subtitles)
                || (track.has_subtitles == existing.track.has_subtitles
                    && match_score.total > existing.match_score.total)
        });
        if should_replace || !already_present {
            self.tracks.insert(
                track.track_id,
                ScoredTrack {
                    track,
                    match_score,
                    confident_early_match,
                },
            );
        }
        if already_present {
            stats.duplicates = 1;
        } else {
            stats.accepted = 1;
        }
        stats
    }

    fn add_all(&mut self, tracks: Vec<Track>, query: MatchMetadata<'_>) -> CandidateBatchStats {
        tracks
            .into_iter()
            .fold(CandidateBatchStats::default(), |mut total, track| {
                let stats = self.add(track, query);
                total.returned += stats.returned;
                total.accepted += stats.accepted;
                total.duplicates += stats.duplicates;
                total.rejected_title += stats.rejected_title;
                total
            })
    }

    fn ranked(&self) -> Vec<ScoredTrack> {
        let mut ranked = self.tracks.values().cloned().collect::<Vec<_>>();
        ranked.sort_by(compare_scored_tracks);
        ranked
    }

    fn ranked_unselected(&self, selected_track_ids: &HashSet<u64>) -> Vec<ScoredTrack> {
        self.ranked()
            .into_iter()
            .filter(|candidate| !selected_track_ids.contains(&candidate.track.track_id))
            .collect()
    }

    fn get(&self, track_id: u64) -> Option<ScoredTrack> {
        self.tracks.get(&track_id).cloned()
    }

    fn best(&self) -> Option<ScoredTrack> {
        self.ranked().into_iter().next()
    }

    fn len(&self) -> usize {
        self.tracks.len()
    }

    fn describe(&self) -> String {
        let mut ranked = self.tracks.values().cloned().collect::<Vec<_>>();
        ranked.sort_by(compare_scored_tracks);
        ranked
            .iter()
            .take(8)
            .map(describe_track)
            .collect::<Vec<_>>()
            .join(" | ")
    }
}

pub(crate) fn fetch_best_lyrics(
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    translation_language: &str,
    lookup_id: jni::sys::jlong,
) -> Result<NativeResult, String> {
    let _cancellation_guard = begin_lookup_cancellation(lookup_id)?;
    check_lookup_cancelled(lookup_id)?;

    if title.trim().is_empty() {
        return Err("musixmatch content not found: empty title".into());
    }

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("failed to create tokio runtime: {e}"))?;

    runtime
        .block_on(async move {
            let client = get_client()
                .map_err(|error| LookupFailure::native("client initialization", error))?;
            let title = clean_query_part(title);
            let artist = clean_query_part(artist);
            let album = clean_query_part(album);
            let translation_language = normalize_translation_language(translation_language);
            let deadline = tokio::time::Instant::now() + MUSIXMATCH_LOOKUP_TIMEOUT;
            let mut budget = RequestBudget::new(MUSIXMATCH_MAX_REQUESTS);
            let trace = LookupTrace::new("musixmatch");

            let found = match within_lookup_deadline(
                deadline,
                find_usable_lyrics(
                    &client,
                    &title,
                    &artist,
                    &album,
                    duration_ms,
                    lookup_id,
                    &mut budget,
                    &trace,
                ),
            )
            .await
            {
                Ok(found) => found,
                Err(error) => {
                    trace.event(
                        "lookup_stop",
                        format_args!(
                            "reason={} requestsUsed={}",
                            failure_kind_name(error.kind),
                            budget.used(),
                        ),
                    );
                    return Err(error);
                }
            };

            check_cancelled(lookup_id)?;
            let translated_lrc = if translation_language.is_empty() {
                None
            } else if let Some(track) = found.track.as_ref() {
                match tokio::time::timeout_at(
                    deadline,
                    fetch_translation_for_track(
                        &client,
                        track,
                        &translation_language,
                        lookup_id,
                        &mut budget,
                    ),
                )
                .await
                {
                    Ok(Ok(translation_list)) => {
                        trace.event(
                            "translation",
                            format_args!("outcome=received requestsUsed={}", budget.used()),
                        );
                        translation_list_to_lrc(&found.lrc, &translation_list)
                    }
                    Ok(Err(error)) if error.kind == FailureKind::Cancelled => return Err(error),
                    Ok(Err(error)) => {
                        trace.event(
                            "translation",
                            format_args!(
                                "outcome=unavailable reason={} requestsUsed={}",
                                failure_kind_name(error.kind),
                                budget.used(),
                            ),
                        );
                        None
                    }
                    Err(_) => {
                        trace.event(
                            "translation",
                            format_args!("outcome=timeout requestsUsed={}", budget.used()),
                        );
                        None
                    }
                }
            } else {
                None
            };

            check_cancelled(lookup_id)?;
            let merged_lrc = merge_lrc_like_netease(&found.lrc, translated_lrc.as_deref())
                .unwrap_or_else(|| found.lrc.clone());
            let track = found.track.as_ref();
            trace.event(
                "result",
                format_args!(
                    "outcome=usable matchedTrack={} translated={} requestsUsed={}",
                    track.is_some(),
                    translated_lrc.is_some(),
                    budget.used(),
                ),
            );

            Ok(NativeResult {
                ok: true,
                source: "musixmatch-rust",
                id: Some(
                    track
                        .map(|track| track.track_id)
                        .unwrap_or(found.subtitle_id)
                        .to_string(),
                ),
                title: Some(
                    track
                        .map(|track| track.track_name.clone())
                        .unwrap_or_else(|| title.clone()),
                ),
                artist: Some(
                    track
                        .map(|track| track.artist_name.clone())
                        .filter(|value| !value.is_empty())
                        .unwrap_or_else(|| artist.clone()),
                ),
                album: track
                    .map(|track| track.album_name.clone())
                    .filter(|value| !value.is_empty())
                    .or_else(|| (!album.is_empty()).then_some(album.clone())),
                duration_ms: track
                    .and_then(|track| {
                        (track.track_length > 0)
                            .then_some((track.track_length as u64).saturating_mul(1000))
                    })
                    .or(duration_ms),
                lrc: Some(found.lrc),
                translated_lrc,
                merged_lrc: Some(merged_lrc),
                error_type: None,
                error: None,
            })
        })
        .map_err(|error: LookupFailure| error.to_string())
}

#[allow(clippy::too_many_arguments)]
async fn find_usable_lyrics(
    client: &Musixmatch,
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
    budget: &mut RequestBudget,
    trace: &LookupTrace,
) -> Result<FoundLyrics, LookupFailure> {
    let backend = ApiMusixmatchBackend { client, lookup_id };
    find_usable_lyrics_with_backend(
        &backend,
        title,
        artist,
        album,
        duration_ms,
        lookup_id,
        budget,
        trace,
    )
    .await
}

#[allow(clippy::too_many_arguments)]
async fn find_usable_lyrics_with_backend<B: MusixmatchBackend>(
    backend: &B,
    title: &str,
    artist: &str,
    album: &str,
    duration_ms: Option<u64>,
    lookup_id: jni::sys::jlong,
    budget: &mut RequestBudget,
    trace: &LookupTrace,
) -> Result<FoundLyrics, LookupFailure> {
    let query = MatchMetadata {
        title,
        artist,
        album,
        duration_ms,
    };
    let duration_seconds = duration_ms
        .filter(|value| *value > 0)
        .map(|value| value as f32 / 1000.0);
    let mut pool = CandidatePool::default();
    let mut failures = FailureLog::default();
    let mut selected_track_ids = Vec::<u64>::new();
    let mut selected_track_set = HashSet::<u64>::new();
    let mut subtitle_request_keys = HashSet::<SubtitleRequestKey>::new();
    let search_attempts = build_track_search_attempts(title, artist);

    trace.event(
        "lookup_start",
        format_args!(
            "searchVariants={} artistPresent={} albumPresent={} durationPresent={} requestLimit={}",
            search_attempts.len(),
            !artist.is_empty(),
            !album.is_empty(),
            duration_seconds.is_some(),
            budget.limit,
        ),
    );

    budget.spend("matcher.track")?;
    let started = Instant::now();
    match backend.matcher_track(title, artist, album).await {
        Ok(track) => {
            let stats = pool.add(track, query);
            trace_candidate_batch(trace, "matcher_track", 0, started, &stats, &pool, budget);
        }
        Err(error) => {
            trace.event(
                "matcher_track",
                format_args!(
                    "outcome=error reason={} requestMs={} requestsUsed={}",
                    failure_kind_name(error.kind),
                    started.elapsed().as_millis(),
                    budget.used(),
                ),
            );
            failures.record(error)?;
        }
    }

    if let Some(found) = try_next_early_candidate(
        backend,
        &pool,
        duration_seconds,
        search_attempts.len(),
        budget,
        &mut selected_track_ids,
        &mut selected_track_set,
        &mut subtitle_request_keys,
        &mut failures,
        trace,
    )
    .await?
    {
        return Ok(found);
    }

    for (index, attempt) in search_attempts.iter().enumerate() {
        check_cancelled(lookup_id)?;
        if budget.exhausted() {
            trace.event(
                "search_stop",
                format_args!("reason=request_budget requestsUsed={}", budget.used()),
            );
            break;
        }

        let label = format!("track.search round={}", index + 1);
        budget.spend(&label)?;
        let started = Instant::now();
        match backend.search(attempt).await {
            Ok(tracks) => {
                let stats = pool.add_all(tracks, query);
                trace_candidate_batch(
                    trace,
                    "track_search",
                    index + 1,
                    started,
                    &stats,
                    &pool,
                    budget,
                );
            }
            Err(error) => {
                trace.event(
                    "track_search",
                    format_args!(
                        "round={} outcome=error reason={} requestMs={} requestsUsed={}",
                        index + 1,
                        failure_kind_name(error.kind),
                        started.elapsed().as_millis(),
                        budget.used(),
                    ),
                );
                failures.record(error)?;
            }
        }

        let remaining_searches = search_attempts.len().saturating_sub(index + 1);
        if let Some(found) = try_next_early_candidate(
            backend,
            &pool,
            duration_seconds,
            remaining_searches,
            budget,
            &mut selected_track_ids,
            &mut selected_track_set,
            &mut subtitle_request_keys,
            &mut failures,
            trace,
        )
        .await?
        {
            return Ok(found);
        }
    }

    for candidate in pool.ranked_unselected(&selected_track_set) {
        if selected_track_ids.len() >= MUSIXMATCH_MAX_TRACK_ATTEMPTS {
            break;
        }
        selected_track_set.insert(candidate.track.track_id);
        selected_track_ids.push(candidate.track.track_id);
    }

    let mut selected = selected_track_ids
        .iter()
        .filter_map(|track_id| pool.get(*track_id))
        .collect::<Vec<_>>();
    selected.sort_by(compare_scored_tracks);
    trace.event(
        "candidate_selection",
        format_args!(
            "selected={} pool={} candidates={} requestsUsed={}",
            selected.len(),
            pool.len(),
            describe_selected_candidates(&selected),
            budget.used(),
        ),
    );

    // Breadth-first request rounds give every selected track a primary subtitle request before
    // any track consumes its no-duration or commontrack fallbacks.
    'candidate_rounds: for request_round in 0..3 {
        for candidate in &selected {
            let Some(request) = candidate_subtitle_requests(&candidate.track, duration_seconds)
                .into_iter()
                .nth(request_round)
            else {
                continue;
            };
            if !budget.can_spend_reserving(MUSIXMATCH_MATCHER_REQUEST_RESERVE) {
                trace.event(
                    "candidate_stop",
                    format_args!(
                        "reason=matcher_reserve reserve={} requestsUsed={}",
                        MUSIXMATCH_MATCHER_REQUEST_RESERVE,
                        budget.used(),
                    ),
                );
                break 'candidate_rounds;
            }
            if let Some(found) = try_candidate_subtitle_request(
                backend,
                candidate,
                request,
                "ranked",
                budget,
                &mut subtitle_request_keys,
                &mut failures,
                trace,
            )
            .await?
            {
                return Ok(found);
            }
        }
    }

    if let Some(subtitle) = fetch_lrc_with_matcher_fallback(
        backend,
        title,
        artist,
        duration_seconds,
        lookup_id,
        budget,
        &mut failures,
        trace,
    )
    .await?
    {
        trace.event(
            "lookup_stop",
            format_args!(
                "reason=matcher_subtitle_success requestsUsed={}",
                budget.used()
            ),
        );
        return Ok(FoundLyrics {
            // matcher.subtitle does not identify which search candidate supplied the subtitle.
            // Retaining a pool track here would also apply its unrelated translation ID.
            track: None,
            subtitle_id: subtitle.subtitle_id,
            lrc: subtitle.lrc,
        });
    }

    trace.event(
        "lookup_stop",
        format_args!(
            "reason=no_usable_timed_lyrics pool={} selected={} requestsUsed={}",
            pool.len(),
            selected.len(),
            budget.used(),
        ),
    );
    let candidates = pool.describe();
    Err(failures.into_final(format!(
        "no usable synchronized lyrics; requestsUsed={}; candidates=[{candidates}]",
        budget.used(),
    )))
}

struct SearchAttempt {
    title: String,
    artist: Option<String>,
}

impl SearchAttempt {
    fn from_query(query: SearchQuery) -> Self {
        Self {
            title: query.title,
            artist: query.artist,
        }
    }

    fn key(&self) -> String {
        format!(
            "{}\0{}",
            self.title.to_lowercase(),
            self.artist.as_deref().unwrap_or_default().to_lowercase()
        )
    }
}

fn build_track_search_attempts(title: &str, artist: &str) -> Vec<SearchAttempt> {
    let mut seen = HashSet::new();
    build_search_queries(title, artist)
        .into_iter()
        .map(SearchAttempt::from_query)
        .filter(|attempt| seen.insert(attempt.key()))
        .collect()
}

trait MusixmatchBackend {
    fn matcher_track<'a>(
        &'a self,
        title: &'a str,
        artist: &'a str,
        album: &'a str,
    ) -> Pin<Box<dyn Future<Output = Result<Track, LookupFailure>> + 'a>>;

    fn search<'a>(
        &'a self,
        attempt: &'a SearchAttempt,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<Track>, LookupFailure>> + 'a>>;

    fn track_subtitle<'a>(
        &'a self,
        request: &'a SubtitleRequest,
    ) -> Pin<Box<dyn Future<Output = Result<Subtitle, LookupFailure>> + 'a>>;

    fn matcher_subtitle<'a>(
        &'a self,
        request: &'a MatcherSubtitleRequest,
    ) -> Pin<Box<dyn Future<Output = Result<Subtitle, LookupFailure>> + 'a>>;
}

struct ApiMusixmatchBackend<'a> {
    client: &'a Musixmatch,
    lookup_id: jni::sys::jlong,
}

impl MusixmatchBackend for ApiMusixmatchBackend<'_> {
    fn matcher_track<'a>(
        &'a self,
        title: &'a str,
        artist: &'a str,
        album: &'a str,
    ) -> Pin<Box<dyn Future<Output = Result<Track, LookupFailure>> + 'a>> {
        Box::pin(await_api(
            self.client
                .matcher_track(title, artist, album, false, false, false),
            self.lookup_id,
            "matcher.track",
        ))
    }

    fn search<'a>(
        &'a self,
        attempt: &'a SearchAttempt,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<Track>, LookupFailure>> + 'a>> {
        let mut query = self
            .client
            .track_search()
            .q_track(&attempt.title)
            .s_track_rating(SortOrder::Desc);
        if let Some(artist) = attempt.artist.as_deref() {
            query = query.q_artist(artist);
        }
        Box::pin(async move {
            await_api(
                query.send(MUSIXMATCH_SEARCH_PAGE_SIZE, 1),
                self.lookup_id,
                "track.search",
            )
            .await
        })
    }

    fn track_subtitle<'a>(
        &'a self,
        request: &'a SubtitleRequest,
    ) -> Pin<Box<dyn Future<Output = Result<Subtitle, LookupFailure>> + 'a>> {
        Box::pin(await_api(
            self.client.track_subtitle(
                request.track_id(),
                SubtitleFormat::Lrc,
                request.duration_seconds,
                request.duration_seconds.map(|_| 12.0),
            ),
            self.lookup_id,
            request.label(),
        ))
    }

    fn matcher_subtitle<'a>(
        &'a self,
        request: &'a MatcherSubtitleRequest,
    ) -> Pin<Box<dyn Future<Output = Result<Subtitle, LookupFailure>> + 'a>> {
        Box::pin(await_api(
            self.client.matcher_subtitle(
                &request.title,
                &request.artist,
                SubtitleFormat::Lrc,
                request.duration_seconds,
                request.duration_seconds.map(|_| 12.0),
            ),
            self.lookup_id,
            "matcher.subtitle",
        ))
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
enum SubtitleRequestKey {
    TrackWithDuration(u64),
    Track(u64),
    Commontrack(u64),
}

#[derive(Clone, Copy, Debug)]
struct SubtitleRequest {
    key: SubtitleRequestKey,
    duration_seconds: Option<f32>,
}

impl SubtitleRequest {
    fn track_id(self) -> TrackId<'static> {
        match self.key {
            SubtitleRequestKey::TrackWithDuration(id) | SubtitleRequestKey::Track(id) => {
                TrackId::TrackId(id)
            }
            SubtitleRequestKey::Commontrack(id) => TrackId::Commontrack(id),
        }
    }

    fn label(self) -> String {
        match self.key {
            SubtitleRequestKey::TrackWithDuration(id) => {
                format!("track.subtitle trackId={id} with duration")
            }
            SubtitleRequestKey::Track(id) => format!("track.subtitle trackId={id}"),
            SubtitleRequestKey::Commontrack(id) => {
                format!("track.subtitle commontrackId={id}")
            }
        }
    }

    fn kind(self) -> &'static str {
        match self.key {
            SubtitleRequestKey::TrackWithDuration(_) => "track_with_duration",
            SubtitleRequestKey::Track(_) => "track",
            SubtitleRequestKey::Commontrack(_) => "commontrack",
        }
    }
}

fn candidate_subtitle_requests(
    track: &Track,
    duration_seconds: Option<f32>,
) -> Vec<SubtitleRequest> {
    let mut requests = Vec::with_capacity(3);
    if let Some(duration_seconds) = duration_seconds {
        requests.push(SubtitleRequest {
            key: SubtitleRequestKey::TrackWithDuration(track.track_id),
            duration_seconds: Some(duration_seconds),
        });
    }
    requests.push(SubtitleRequest {
        key: SubtitleRequestKey::Track(track.track_id),
        duration_seconds: None,
    });
    if track.commontrack_id != 0 {
        requests.push(SubtitleRequest {
            key: SubtitleRequestKey::Commontrack(track.commontrack_id),
            duration_seconds: None,
        });
    }
    requests
}

#[allow(clippy::too_many_arguments)]
async fn try_next_early_candidate<B: MusixmatchBackend>(
    backend: &B,
    pool: &CandidatePool,
    duration_seconds: Option<f32>,
    remaining_searches: usize,
    budget: &mut RequestBudget,
    selected_track_ids: &mut Vec<u64>,
    selected_track_set: &mut HashSet<u64>,
    requested: &mut HashSet<SubtitleRequestKey>,
    failures: &mut FailureLog,
    trace: &LookupTrace,
) -> Result<Option<FoundLyrics>, LookupFailure> {
    if selected_track_ids.len() >= MUSIXMATCH_MAX_EARLY_TRACK_ATTEMPTS {
        return Ok(None);
    }

    let Some(candidate) =
        pool.ranked_unselected(selected_track_set)
            .into_iter()
            .find(|candidate| {
                candidate.confident_early_match
                    && (candidate.track.has_subtitles || candidate.track.has_lyrics)
            })
    else {
        return Ok(None);
    };
    let reserve = remaining_searches + MUSIXMATCH_MATCHER_REQUEST_RESERVE;
    if !budget.can_spend_reserving(reserve) {
        trace.event(
            "early_candidate_skip",
            format_args!(
                "reason=search_and_matcher_reserve reserve={} requestsUsed={}",
                reserve,
                budget.used(),
            ),
        );
        return Ok(None);
    }

    selected_track_set.insert(candidate.track.track_id);
    selected_track_ids.push(candidate.track.track_id);
    let request = candidate_subtitle_requests(&candidate.track, duration_seconds)
        .into_iter()
        .next()
        .expect("every candidate has a primary subtitle request");
    try_candidate_subtitle_request(
        backend, &candidate, request, "early", budget, requested, failures, trace,
    )
    .await
}

#[allow(clippy::too_many_arguments)]
async fn try_candidate_subtitle_request<B: MusixmatchBackend>(
    backend: &B,
    candidate: &ScoredTrack,
    request: SubtitleRequest,
    phase: &str,
    budget: &mut RequestBudget,
    requested: &mut HashSet<SubtitleRequestKey>,
    failures: &mut FailureLog,
    trace: &LookupTrace,
) -> Result<Option<FoundLyrics>, LookupFailure> {
    if !requested.insert(request.key) {
        return Ok(None);
    }

    let label = request.label();
    budget.spend(&label)?;
    let started = Instant::now();
    let result = backend
        .track_subtitle(&request)
        .await
        .and_then(|subtitle| validate_subtitle(subtitle, &label));
    match result {
        Ok(subtitle) => {
            trace.event(
                "candidate_subtitle",
                format_args!(
                    "phase={} outcome=usable trackId={} requestKind={} score={:.3} requestMs={} requestsUsed={}",
                    phase,
                    candidate.track.track_id,
                    request.kind(),
                    candidate.match_score.total,
                    started.elapsed().as_millis(),
                    budget.used(),
                ),
            );
            Ok(Some(FoundLyrics {
                track: Some(candidate.track.clone()),
                subtitle_id: subtitle.subtitle_id,
                lrc: subtitle.lrc,
            }))
        }
        Err(error) => {
            trace.event(
                "candidate_subtitle",
                format_args!(
                    "phase={} outcome=unusable trackId={} requestKind={} score={:.3} reason={} requestMs={} requestsUsed={}",
                    phase,
                    candidate.track.track_id,
                    request.kind(),
                    candidate.match_score.total,
                    failure_kind_name(error.kind),
                    started.elapsed().as_millis(),
                    budget.used(),
                ),
            );
            failures.record(error)?;
            Ok(None)
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
struct MatcherSubtitleRequest {
    title: String,
    artist: String,
    duration_seconds: Option<f32>,
}

fn build_matcher_subtitle_requests(
    title: &str,
    artist: &str,
    duration_seconds: Option<f32>,
) -> Vec<MatcherSubtitleRequest> {
    let queries = build_search_queries(title, artist);
    let mut requests = Vec::new();
    if let (Some(first), Some(duration_seconds)) = (queries.first(), duration_seconds) {
        requests.push(MatcherSubtitleRequest {
            title: first.title.clone(),
            artist: first.artist.clone().unwrap_or_default(),
            duration_seconds: Some(duration_seconds),
        });
    }
    requests.extend(queries.into_iter().map(|query| MatcherSubtitleRequest {
        title: query.title,
        artist: query.artist.unwrap_or_default(),
        duration_seconds: None,
    }));

    let mut seen = HashSet::new();
    requests
        .into_iter()
        .filter(|request| {
            seen.insert(format!(
                "{}\0{}\0{}",
                request.title.to_lowercase(),
                request.artist.to_lowercase(),
                request.duration_seconds.is_some(),
            ))
        })
        .collect()
}

fn select_matcher_subtitle_requests(
    mut requests: Vec<MatcherSubtitleRequest>,
    limit: usize,
) -> Vec<MatcherSubtitleRequest> {
    if limit == 0 {
        return Vec::new();
    }
    if requests.len() <= limit {
        return requests;
    }

    // The final request is the broadest title-only variant. Keep it even when the budget can
    // accommodate only part of the deduplicated list.
    let broadest = requests.pop().expect("non-empty matcher request list");
    if limit == 1 {
        return vec![broadest];
    }
    requests.truncate(limit - 1);
    requests.push(broadest);
    requests
}

#[allow(clippy::too_many_arguments)]
async fn fetch_lrc_with_matcher_fallback<B: MusixmatchBackend>(
    backend: &B,
    title: &str,
    artist: &str,
    duration_seconds: Option<f32>,
    lookup_id: jni::sys::jlong,
    budget: &mut RequestBudget,
    failures: &mut FailureLog,
    trace: &LookupTrace,
) -> Result<Option<FetchedSubtitle>, LookupFailure> {
    let all_requests = build_matcher_subtitle_requests(title, artist, duration_seconds);
    let request_limit = budget
        .remaining()
        .min(MUSIXMATCH_MAX_MATCHER_SUBTITLE_REQUESTS);
    let requests = select_matcher_subtitle_requests(all_requests, request_limit);
    trace.event(
        "matcher_subtitle_plan",
        format_args!(
            "selected={} budgetRemaining={} broadestIncluded={} requestsUsed={}",
            requests.len(),
            budget.remaining(),
            requests.last().is_some_and(
                |request| request.artist.is_empty() && request.duration_seconds.is_none()
            ),
            budget.used(),
        ),
    );

    for (index, request) in requests.iter().enumerate() {
        check_cancelled(lookup_id)?;
        let label = format!("matcher.subtitle round={}", index + 1);
        budget.spend(&label)?;
        let started = Instant::now();
        let result = backend
            .matcher_subtitle(request)
            .await
            .and_then(|subtitle| validate_subtitle(subtitle, &label));
        match result {
            Ok(subtitle) => {
                trace.event(
                    "matcher_subtitle",
                    format_args!(
                        "round={} outcome=usable artistPresent={} durationPresent={} requestMs={} requestsUsed={}",
                        index + 1,
                        !request.artist.is_empty(),
                        request.duration_seconds.is_some(),
                        started.elapsed().as_millis(),
                        budget.used(),
                    ),
                );
                return Ok(Some(subtitle));
            }
            Err(error) => {
                trace.event(
                    "matcher_subtitle",
                    format_args!(
                        "round={} outcome=unusable artistPresent={} durationPresent={} reason={} requestMs={} requestsUsed={}",
                        index + 1,
                        !request.artist.is_empty(),
                        request.duration_seconds.is_some(),
                        failure_kind_name(error.kind),
                        started.elapsed().as_millis(),
                        budget.used(),
                    ),
                );
                failures.record(error)?;
            }
        }
    }

    Ok(None)
}

async fn fetch_translation_for_track(
    client: &Musixmatch,
    track: &Track,
    language: &str,
    lookup_id: jni::sys::jlong,
    budget: &mut RequestBudget,
) -> Result<TranslationList, LookupFailure> {
    let mut failures = FailureLog::default();
    let label = format!(
        "track.translation trackId={} lang={language}",
        track.track_id
    );
    budget.spend(&label)?;
    match await_api(
        client.track_lyrics_translation(TrackId::TrackId(track.track_id), language),
        lookup_id,
        &label,
    )
    .await
    {
        Ok(list) if !list.is_empty() => return Ok(list),
        Ok(_) => failures.record(LookupFailure::not_found(&label, "empty translation"))?,
        Err(error) => failures.record(error)?,
    }

    if track.commontrack_id != 0 && !budget.exhausted() {
        let label = format!(
            "track.translation commontrackId={} lang={language}",
            track.commontrack_id
        );
        budget.spend(&label)?;
        match await_api(
            client.track_lyrics_translation(TrackId::Commontrack(track.commontrack_id), language),
            lookup_id,
            &label,
        )
        .await
        {
            Ok(list) if !list.is_empty() => return Ok(list),
            Ok(_) => failures.record(LookupFailure::not_found(&label, "empty translation"))?,
            Err(error) => failures.record(error)?,
        }
    }

    Err(failures.into_final(format!("translation lang={language}")))
}

fn validate_subtitle(subtitle: Subtitle, context: &str) -> Result<FetchedSubtitle, LookupFailure> {
    let subtitle_id = subtitle.subtitle_id;
    let lrc = normalize_usable_lrc(subtitle.subtitle_body).ok_or_else(|| {
        LookupFailure::not_found(context, "subtitle has no parseable non-empty timeline")
    })?;
    Ok(FetchedSubtitle { subtitle_id, lrc })
}

async fn await_api<T, F>(
    future: F,
    lookup_id: jni::sys::jlong,
    context: impl Into<String>,
) -> Result<T, LookupFailure>
where
    F: Future<Output = Result<T, MusixmatchApiError>>,
{
    let context = context.into();
    check_cancelled(lookup_id)?;
    tokio::pin!(future);
    loop {
        tokio::select! {
            result = &mut future => {
                check_cancelled(lookup_id)?;
                return result.map_err(|error| LookupFailure::from_api(context, error));
            }
            _ = tokio::time::sleep(MUSIXMATCH_CANCELLATION_POLL_INTERVAL) => {
                check_cancelled(lookup_id)?;
            }
        }
    }
}

async fn within_lookup_deadline<T, F>(
    deadline: tokio::time::Instant,
    future: F,
) -> Result<T, LookupFailure>
where
    F: Future<Output = Result<T, LookupFailure>>,
{
    tokio::time::timeout_at(deadline, future)
        .await
        .map_err(|_| LookupFailure::lookup_timeout())?
}

fn check_cancelled(lookup_id: jni::sys::jlong) -> Result<(), LookupFailure> {
    check_lookup_cancelled(lookup_id).map_err(LookupFailure::cancelled)
}

fn trace_candidate_batch(
    trace: &LookupTrace,
    stage: &str,
    round: usize,
    started: Instant,
    stats: &CandidateBatchStats,
    pool: &CandidatePool,
    budget: &RequestBudget,
) {
    let best = pool.best();
    trace.event(
        stage,
        format_args!(
            "round={} outcome=ok requestMs={} returned={} accepted={} duplicate={} rejectedTitle={} pool={} topScore={} topTitleScore={} topArtistScore={} topEarly={} requestsUsed={}",
            round,
            started.elapsed().as_millis(),
            stats.returned,
            stats.accepted,
            stats.duplicates,
            stats.rejected_title,
            pool.len(),
            format_optional_score(best.as_ref().map(|candidate| candidate.match_score.total)),
            format_optional_score(
                best.as_ref()
                    .map(|candidate| candidate.match_score.title_similarity)
            ),
            format_optional_score(
                best.as_ref()
                    .and_then(|candidate| candidate.match_score.artist_similarity)
            ),
            best.as_ref()
                .is_some_and(|candidate| candidate.confident_early_match),
            budget.used(),
        ),
    );
}

fn format_optional_score(value: Option<f64>) -> String {
    value
        .map(|value| format!("{value:.3}"))
        .unwrap_or_else(|| "missing".to_string())
}

fn describe_selected_candidates(candidates: &[ScoredTrack]) -> String {
    candidates
        .iter()
        .map(|candidate| {
            format!(
                "{}:{:.3}/{:.3}:early={}:sub={}:lyrics={}",
                candidate.track.track_id,
                candidate.match_score.total,
                candidate.match_score.title_similarity,
                candidate.confident_early_match,
                candidate.track.has_subtitles,
                candidate.track.has_lyrics,
            )
        })
        .collect::<Vec<_>>()
        .join(",")
}

fn failure_kind_name(kind: FailureKind) -> &'static str {
    match kind {
        FailureKind::Cancelled => "cancelled",
        FailureKind::NeedCredential => "credential",
        FailureKind::RateLimited => "rate_limited",
        FailureKind::Restricted => "restricted",
        FailureKind::Network => "network",
        FailureKind::Parse => "parse",
        FailureKind::NotFound => "not_found",
        FailureKind::BudgetExhausted => "budget",
        FailureKind::Unknown => "unknown",
    }
}

fn compare_scored_tracks(left: &ScoredTrack, right: &ScoredTrack) -> Ordering {
    candidate_priority_tier(left)
        .cmp(&candidate_priority_tier(right))
        .then_with(|| right.match_score.total.total_cmp(&left.match_score.total))
        .then_with(|| left.track.instrumental.cmp(&right.track.instrumental))
        .then_with(|| right.track.track_rating.cmp(&left.track.track_rating))
        .then_with(|| left.track.track_id.cmp(&right.track.track_id))
}

fn candidate_priority_tier(candidate: &ScoredTrack) -> u8 {
    match (
        candidate.confident_early_match,
        candidate.track.has_subtitles,
        candidate.track.has_lyrics,
    ) {
        (true, true, _) => 0,
        (true, false, true) => 1,
        (true, false, false) => 2,
        (false, true, _) => 3,
        (false, false, true) => 4,
        (false, false, false) => 5,
    }
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
        .build_with_client(
            reqwest::Client::builder()
                .connect_timeout(MUSIXMATCH_CONNECT_TIMEOUT)
                .timeout(MUSIXMATCH_REQUEST_TIMEOUT),
        )
        .map_err(|e| format!("failed to create musixmatch client: {e}"))?;

    let _ = MUSIXMATCH_CLIENT.set(client.clone());
    Ok(client)
}

fn describe_track(candidate: &ScoredTrack) -> String {
    let track = &candidate.track;
    format!(
        "id={} common={} score={:.3} titleScore={:.3} artistScore={:?} albumScore={:?} durationScore={:?} versionPenalty={:.3} early={} len={}s hasLyrics={} hasSubtitles={} rating={}",
        track.track_id,
        track.commontrack_id,
        candidate.match_score.total,
        candidate.match_score.title_similarity,
        candidate.match_score.artist_similarity,
        candidate.match_score.album_similarity,
        candidate.match_score.duration_similarity,
        candidate.match_score.version_penalty,
        candidate.confident_early_match,
        track.track_length,
        track.has_lyrics,
        track.has_subtitles,
        track.track_rating,
    )
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::Mutex;

    struct FakeMusixmatchBackend {
        matcher_tracks: Mutex<VecDeque<Result<Track, LookupFailure>>>,
        searches: Mutex<VecDeque<Result<Vec<Track>, LookupFailure>>>,
        subtitles: Mutex<HashMap<SubtitleRequestKey, Result<Subtitle, LookupFailure>>>,
        matcher_subtitles: Mutex<VecDeque<Result<Subtitle, LookupFailure>>>,
        calls: Mutex<Vec<String>>,
        matcher_requests: Mutex<Vec<MatcherSubtitleRequest>>,
    }

    impl FakeMusixmatchBackend {
        fn new(
            matcher_track: Result<Track, LookupFailure>,
            searches: impl IntoIterator<Item = Result<Vec<Track>, LookupFailure>>,
            subtitles: impl IntoIterator<Item = (SubtitleRequestKey, Result<Subtitle, LookupFailure>)>,
            matcher_subtitles: impl IntoIterator<Item = Result<Subtitle, LookupFailure>>,
        ) -> Self {
            Self {
                matcher_tracks: Mutex::new(VecDeque::from([matcher_track])),
                searches: Mutex::new(searches.into_iter().collect()),
                subtitles: Mutex::new(subtitles.into_iter().collect()),
                matcher_subtitles: Mutex::new(matcher_subtitles.into_iter().collect()),
                calls: Mutex::new(Vec::new()),
                matcher_requests: Mutex::new(Vec::new()),
            }
        }

        fn calls(&self) -> Vec<String> {
            self.calls.lock().unwrap().clone()
        }

        fn matcher_requests(&self) -> Vec<MatcherSubtitleRequest> {
            self.matcher_requests.lock().unwrap().clone()
        }
    }

    impl MusixmatchBackend for FakeMusixmatchBackend {
        fn matcher_track<'a>(
            &'a self,
            _title: &'a str,
            _artist: &'a str,
            _album: &'a str,
        ) -> Pin<Box<dyn Future<Output = Result<Track, LookupFailure>> + 'a>> {
            self.calls.lock().unwrap().push("matcher.track".to_string());
            let result = self
                .matcher_tracks
                .lock()
                .unwrap()
                .pop_front()
                .expect("controlled matcher.track response");
            Box::pin(std::future::ready(result))
        }

        fn search<'a>(
            &'a self,
            attempt: &'a SearchAttempt,
        ) -> Pin<Box<dyn Future<Output = Result<Vec<Track>, LookupFailure>> + 'a>> {
            self.calls.lock().unwrap().push(format!(
                "search:{}|{}",
                attempt.title,
                attempt.artist.as_deref().unwrap_or_default()
            ));
            let result = self
                .searches
                .lock()
                .unwrap()
                .pop_front()
                .expect("controlled search response");
            Box::pin(std::future::ready(result))
        }

        fn track_subtitle<'a>(
            &'a self,
            request: &'a SubtitleRequest,
        ) -> Pin<Box<dyn Future<Output = Result<Subtitle, LookupFailure>> + 'a>> {
            self.calls
                .lock()
                .unwrap()
                .push(format!("subtitle:{:?}", request.key));
            let result = self
                .subtitles
                .lock()
                .unwrap()
                .remove(&request.key)
                .unwrap_or_else(|| {
                    Err(LookupFailure::not_found(
                        "controlled subtitle",
                        "missing fixture",
                    ))
                });
            Box::pin(std::future::ready(result))
        }

        fn matcher_subtitle<'a>(
            &'a self,
            request: &'a MatcherSubtitleRequest,
        ) -> Pin<Box<dyn Future<Output = Result<Subtitle, LookupFailure>> + 'a>> {
            self.calls.lock().unwrap().push(format!(
                "matcher.subtitle:{}|{}|{}",
                request.title,
                request.artist,
                request.duration_seconds.is_some()
            ));
            self.matcher_requests.lock().unwrap().push(request.clone());
            let result = self
                .matcher_subtitles
                .lock()
                .unwrap()
                .pop_front()
                .unwrap_or_else(|| {
                    Err(LookupFailure::not_found(
                        "controlled matcher.subtitle",
                        "missing fixture",
                    ))
                });
            Box::pin(std::future::ready(result))
        }
    }

    fn test_track(
        id: u64,
        title: &str,
        artist: &str,
        album: &str,
        duration_seconds: u32,
        has_subtitles: bool,
        rating: u8,
    ) -> Track {
        serde_json::from_value(json!({
            "track_id": id,
            "track_name": title,
            "track_rating": rating,
            "track_length": duration_seconds,
            "commontrack_id": id + 10_000,
            "instrumental": false,
            "has_lyrics": has_subtitles,
            "has_subtitles": has_subtitles,
            "album_id": 1,
            "album_name": album,
            "artist_id": 1,
            "artist_name": artist,
            "commontrack_vanity_id": format!("artist/{id}"),
            "updated_time": "2024-01-01T00:00:00Z",
            "performer_tagging": null
        }))
        .expect("valid controlled Musixmatch track")
    }

    fn test_subtitle(id: u64, body: &str) -> Subtitle {
        serde_json::from_value(json!({
            "subtitle_id": id,
            "subtitle_body": body,
            "subtitle_length": 180,
            "updated_time": "2024-01-01T00:00:00Z"
        }))
        .expect("valid controlled Musixmatch subtitle")
    }

    fn query<'a>(
        title: &'a str,
        artist: &'a str,
        album: &'a str,
        duration_ms: Option<u64>,
    ) -> MatchMetadata<'a> {
        MatchMetadata {
            title,
            artist,
            album,
            duration_ms,
        }
    }

    fn missing(context: &str) -> LookupFailure {
        LookupFailure::not_found(context, "controlled missing subtitle")
    }

    async fn run_fake_lookup(
        backend: &FakeMusixmatchBackend,
        title: &str,
        artist: &str,
        duration_ms: Option<u64>,
    ) -> (Result<FoundLyrics, LookupFailure>, RequestBudget) {
        let mut budget = RequestBudget::new(MUSIXMATCH_MAX_REQUESTS);
        let trace = LookupTrace::new("musixmatch-test");
        let result = find_usable_lyrics_with_backend(
            backend,
            title,
            artist,
            "Album",
            duration_ms,
            0,
            &mut budget,
            &trace,
        )
        .await;
        (result, budget)
    }

    #[test]
    fn source_search_queries_relax_in_order_and_allow_blank_artist() {
        let attempts = build_track_search_attempts("Song (Live)", "Artist");
        let actual = attempts
            .iter()
            .map(|attempt| (attempt.title.as_str(), attempt.artist.as_deref()))
            .collect::<Vec<_>>();
        assert_eq!(
            actual,
            vec![
                ("Song (Live)", Some("Artist")),
                ("Song", Some("Artist")),
                ("Song (Live)", None),
                ("Song", None),
            ]
        );

        let no_artist = build_track_search_attempts("Song", "  ");
        assert_eq!(no_artist.len(), 1);
        assert_eq!(no_artist[0].title, "Song");
        assert_eq!(no_artist[0].artist, None);
    }

    #[test]
    fn candidates_are_deduplicated_by_track_id() {
        let metadata = query("Song", "Artist", "", None);
        let mut pool = CandidatePool::default();
        pool.add(
            test_track(7, "Song", "Artist", "", 180, false, 90),
            metadata,
        );
        pool.add(test_track(7, "Song", "Artist", "", 180, true, 90), metadata);

        assert_eq!(pool.tracks.len(), 1);
        assert!(pool.best().expect("candidate").track.has_subtitles);

        let mut flagged_pool = CandidatePool::default();
        flagged_pool.add(
            test_track(8, "Song (Live)", "Artist", "", 180, true, 80),
            metadata,
        );
        flagged_pool.add(
            test_track(8, "Song", "Artist", "", 180, false, 100),
            metadata,
        );
        let retained = flagged_pool.best().expect("deduplicated candidate");
        assert!(retained.track.has_subtitles);
        assert_eq!(retained.track.track_name, "Song (Live)");
    }

    #[tokio::test]
    async fn complete_flow_gives_later_candidate_a_primary_try_before_first_fallback() {
        let backend = FakeMusixmatchBackend::new(
            Err(missing("matcher.track")),
            [
                Ok(vec![
                    test_track(1, "Song (Live)", "Artist", "Album", 180, false, 100),
                    test_track(2, "Song (Live)", "Artist", "Album", 180, false, 90),
                ]),
                Ok(Vec::new()),
                Ok(Vec::new()),
                Ok(Vec::new()),
            ],
            [
                (
                    SubtitleRequestKey::TrackWithDuration(1),
                    Err(missing("first primary")),
                ),
                (
                    SubtitleRequestKey::TrackWithDuration(2),
                    Ok(test_subtitle(102, "[00:01.00]second candidate")),
                ),
            ],
            [],
        );

        let (result, budget) =
            run_fake_lookup(&backend, "Song (Live)", "Artist", Some(180_000)).await;
        let found = result.expect("second candidate should provide lyrics");

        assert_eq!(found.track.as_ref().map(|track| track.track_id), Some(2));
        assert_eq!(found.lrc, "[00:01.00]second candidate");
        let calls = backend.calls();
        let first_subtitle = calls
            .iter()
            .position(|call| call.starts_with("subtitle:"))
            .expect("subtitle call");
        assert_eq!(
            calls[..first_subtitle]
                .iter()
                .filter(|call| call.starts_with("search:"))
                .count(),
            4
        );
        assert_eq!(
            &calls[first_subtitle..],
            [
                "subtitle:TrackWithDuration(1)",
                "subtitle:TrackWithDuration(2)"
            ]
        );
        assert_eq!(budget.used(), 7);
    }

    #[tokio::test]
    async fn complete_flow_bounds_candidate_fallbacks_and_preserves_broad_matcher() {
        let tracks = vec![
            test_track(1, "Song (Live)", "Artist", "Album", 180, false, 100),
            test_track(2, "Song (Live)", "Artist", "Album", 180, false, 90),
            test_track(3, "Song (Live)", "Artist", "Album", 180, false, 80),
            test_track(4, "Song (Live)", "Artist", "Album", 180, false, 70),
        ];
        let mut subtitle_results = Vec::new();
        for id in 1..=3 {
            subtitle_results.push((
                SubtitleRequestKey::TrackWithDuration(id),
                Err(missing("duration subtitle")),
            ));
            subtitle_results.push((
                SubtitleRequestKey::Track(id),
                Err(missing("track subtitle")),
            ));
            subtitle_results.push((
                SubtitleRequestKey::Commontrack(id + 10_000),
                Err(missing("commontrack subtitle")),
            ));
        }
        let backend = FakeMusixmatchBackend::new(
            Err(missing("matcher.track")),
            [Ok(tracks), Ok(Vec::new()), Ok(Vec::new()), Ok(Vec::new())],
            subtitle_results,
            [
                Err(missing("exact matcher")),
                Ok(test_subtitle(900, "[00:02.00]broad matcher")),
            ],
        );

        let (result, budget) =
            run_fake_lookup(&backend, "Song (Live)", "Artist", Some(180_000)).await;
        let found = result.expect("reserved broad matcher should run");

        assert!(
            found.track.is_none(),
            "matcher result must not borrow pool metadata"
        );
        assert_eq!(found.subtitle_id, 900);
        assert_eq!(budget.used(), MUSIXMATCH_MAX_REQUESTS);
        let calls = backend.calls();
        let subtitle_calls = calls
            .iter()
            .filter(|call| call.starts_with("subtitle:"))
            .cloned()
            .collect::<Vec<_>>();
        assert_eq!(
            subtitle_calls,
            vec![
                "subtitle:TrackWithDuration(1)",
                "subtitle:TrackWithDuration(2)",
                "subtitle:TrackWithDuration(3)",
                "subtitle:Track(1)",
                "subtitle:Track(2)",
                "subtitle:Track(3)",
                "subtitle:Commontrack(10001)",
                "subtitle:Commontrack(10002)",
                "subtitle:Commontrack(10003)",
            ]
        );
        assert!(!calls
            .iter()
            .any(|call| call.contains("TrackWithDuration(4)")));

        let matcher_requests = backend.matcher_requests();
        assert_eq!(matcher_requests.len(), MUSIXMATCH_MATCHER_REQUEST_RESERVE);
        assert!(matcher_requests[0].duration_seconds.is_some());
        let broadest = matcher_requests.last().expect("broad matcher request");
        assert_eq!(broadest.title, "Song");
        assert!(broadest.artist.is_empty());
        assert!(broadest.duration_seconds.is_none());
    }

    #[tokio::test]
    async fn low_score_candidate_is_finally_eligible_but_cannot_stop_searches_early() {
        let candidate = test_track(
            30,
            "Song",
            "Different Performer",
            "Other Album",
            300,
            true,
            100,
        );
        let mut pool = CandidatePool::default();
        pool.add(
            candidate.clone(),
            query("Song", "Query Artist", "Album", Some(180_000)),
        );
        let scored = pool.best().expect("title-related candidate");
        assert!(scored.match_score.total < EARLY_ACCEPT_SCORE);
        assert!(!scored.confident_early_match);

        let backend = FakeMusixmatchBackend::new(
            Err(missing("matcher.track")),
            [
                Ok(vec![candidate]),
                Ok(Vec::new()),
                Ok(Vec::new()),
                Ok(Vec::new()),
            ],
            [(
                SubtitleRequestKey::TrackWithDuration(30),
                Ok(test_subtitle(930, "[00:03.00]related fallback")),
            )],
            [],
        );

        let (result, _) =
            run_fake_lookup(&backend, "Song (Live)", "Query Artist", Some(180_000)).await;
        let found = result.expect("related candidate remains eligible at final selection");
        assert_eq!(found.track.as_ref().map(|track| track.track_id), Some(30));
        let calls = backend.calls();
        let first_subtitle = calls
            .iter()
            .position(|call| call.starts_with("subtitle:"))
            .expect("subtitle call");
        assert_eq!(
            calls[..first_subtitle]
                .iter()
                .filter(|call| call.starts_with("search:"))
                .count(),
            4
        );
    }

    #[test]
    fn missing_artist_and_version_or_duration_differences_do_not_reject() {
        let mut no_artist_pool = CandidatePool::default();
        no_artist_pool.add(
            test_track(4, "Song", "Known Artist", "", 180, true, 70),
            query("Song", "", "", None),
        );
        assert_eq!(
            no_artist_pool
                .best()
                .expect("title-only candidate")
                .match_score
                .artist_similarity,
            None
        );

        let mut variant_pool = CandidatePool::default();
        variant_pool.add(
            test_track(5, "Song - Live", "Artist", "", 300, true, 70),
            query("Song (Live)", "Artist", "", Some(180_000)),
        );
        let variant = variant_pool.best().expect("related live variant");
        assert_eq!(variant.match_score.version_penalty, 0.0);
        assert_eq!(variant.match_score.duration_similarity, Some(0.0));
    }

    #[test]
    fn clearly_different_title_is_not_added_to_candidate_pool() {
        let mut pool = CandidatePool::default();
        pool.add(
            test_track(6, "Completely Unrelated", "Artist", "", 180, true, 100),
            query("Blue Moon", "Artist", "", Some(180_000)),
        );
        assert!(pool.tracks.is_empty());
    }

    #[test]
    fn subtitle_validation_requires_timeline_and_non_empty_text() {
        assert!(validate_subtitle(test_subtitle(1, "plain text"), "test").is_err());
        assert!(validate_subtitle(test_subtitle(1, "[00:01.00]"), "test").is_err());
        assert_eq!(
            validate_subtitle(test_subtitle(1, "[00:01.00]line\r\n"), "test")
                .expect("usable LRC")
                .lrc,
            "[00:01.00]line\n"
        );
    }

    #[test]
    fn commontrack_requests_are_not_repeated_across_track_variants() {
        let mut requested = HashSet::new();
        assert!(requested.insert(SubtitleRequestKey::Commontrack(42)));
        assert!(!requested.insert(SubtitleRequestKey::Commontrack(42)));
        assert!(requested.insert(SubtitleRequestKey::Track(42)));
    }

    #[test]
    fn request_budget_is_shared_and_bounded() {
        let mut budget = RequestBudget::new(2);
        budget.spend("search").expect("first request");
        budget.spend("subtitle").expect("second request");
        let error = budget.spend("translation").expect_err("budget exhausted");
        assert_eq!(error.kind, FailureKind::BudgetExhausted);
        assert!(budget.exhausted());
    }

    #[test]
    fn subtitle_flags_are_prioritized_only_after_matching_is_sufficient() {
        let metadata = query("Song", "Artist", "", None);
        let mut pool = CandidatePool::default();
        pool.add(
            test_track(20, "Song", "Artist", "", 180, false, 100),
            metadata,
        );
        pool.add(
            test_track(21, "Song", "Artist", "", 180, true, 80),
            metadata,
        );
        pool.add(
            test_track(22, "Song", "Beethoven", "", 180, true, 100),
            metadata,
        );

        let ranked = pool.ranked();
        assert_eq!(ranked[0].track.track_id, 21);
        assert_eq!(ranked[1].track.track_id, 20);
        assert_eq!(ranked[2].track.track_id, 22);
    }

    #[test]
    fn matcher_queries_are_deduplicated_before_limit_and_keep_title_only_tail() {
        let all = build_matcher_subtitle_requests("Song (Live)", "Artist", Some(180.0));
        assert_eq!(all.len(), 5);

        let selected = select_matcher_subtitle_requests(all, 2);
        assert_eq!(selected.len(), 2);
        assert!(selected[0].duration_seconds.is_some());
        assert_eq!(selected[1].title, "Song");
        assert!(selected[1].artist.is_empty());

        let deduplicated = build_matcher_subtitle_requests("Song", "", None);
        assert_eq!(deduplicated.len(), 1);
    }

    #[test]
    fn cancellation_timeout_and_non_not_found_errors_keep_their_classification() {
        let cancelled = LookupFailure::cancelled("lookup canceled");
        assert_eq!(cancelled.kind, FailureKind::Cancelled);
        assert!(cancelled.to_string().contains("lookup canceled"));

        let timeout = LookupFailure::lookup_timeout();
        assert_eq!(timeout.kind, FailureKind::Network);
        assert!(timeout.to_string().contains("network error"));
        assert!(timeout.to_string().contains("timed out"));

        let mut failures = FailureLog::default();
        failures
            .record(LookupFailure::not_found("subtitle", "missing"))
            .expect("not found is recoverable");
        failures
            .record(LookupFailure::new(
                FailureKind::Parse,
                "search",
                "invalid JSON",
            ))
            .expect("parse error is recoverable while fallbacks remain");
        failures
            .record(LookupFailure::new(
                FailureKind::Network,
                "matcher",
                "connection failed",
            ))
            .expect("network error is recoverable while fallbacks remain");
        assert_eq!(failures.into_final("lookup").kind, FailureKind::Network);
    }

    #[test]
    fn musixmatch_api_errors_are_classified_before_fallback_aggregation() {
        assert_eq!(
            LookupFailure::from_api("search", MusixmatchApiError::Ratelimit).kind,
            FailureKind::RateLimited
        );
        assert_eq!(
            LookupFailure::from_api("subtitle", MusixmatchApiError::NotAvailable).kind,
            FailureKind::Restricted
        );
        assert_eq!(
            LookupFailure::from_api(
                "search",
                MusixmatchApiError::InvalidData("controlled invalid JSON".into()),
            )
            .kind,
            FailureKind::Parse
        );
        assert_eq!(
            LookupFailure::from_api("subtitle", MusixmatchApiError::NotFound).kind,
            FailureKind::NotFound
        );
    }

    #[test]
    fn async_cancellation_and_deadline_stop_pending_work() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("test runtime");

        let lookup_id = 9_100_001;
        let guard = begin_lookup_cancellation(lookup_id).expect("register lookup");
        crate::cancel_lookup(lookup_id);
        let cancelled = runtime.block_on(await_api(
            std::future::pending::<Result<(), MusixmatchApiError>>(),
            lookup_id,
            "controlled request",
        ));
        assert_eq!(
            cancelled.expect_err("pending request must cancel").kind,
            FailureKind::Cancelled
        );
        drop(guard);

        let timed_out = runtime.block_on(within_lookup_deadline(
            tokio::time::Instant::now() + Duration::from_millis(1),
            std::future::pending::<Result<(), LookupFailure>>(),
        ));
        assert_eq!(
            timed_out.expect_err("pending lookup must time out").kind,
            FailureKind::Network
        );
    }

    #[test]
    fn missing_translation_keeps_the_usable_original_lrc() {
        let original = "[00:01.00]original line\n";
        assert_eq!(
            merge_lrc_like_netease(original, None).as_deref(),
            Some(original)
        );
    }
}
