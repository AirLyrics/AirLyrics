//! Confidence policy: decide whether a scored candidate may be shown automatically.
//!
//! The scorer answers "how similar is this?"; this module answers "is that enough?". Missing
//! metadata never invents evidence — it can only lower certainty. A high title similarity is
//! never sufficient on its own when no independent evidence corroborates it.
use crate::aliases::AliasHit;
use crate::matching::{
    different_scripts, score_candidate, score_metadata_alias, MatchMetadata, MatchScore,
};
use crate::recording::duration_close;

#[derive(Clone, Copy, Debug)]
pub(crate) struct Thresholds {
    pub(crate) accept_total: f64,
    pub(crate) accept_title: f64,
    pub(crate) review_total: f64,
    pub(crate) review_title: f64,
    pub(crate) strong_artist: f64,
    pub(crate) album_match: f64,
    pub(crate) duration_tolerance_ms: u64,
}

impl Default for Thresholds {
    fn default() -> Self {
        Self {
            accept_total: 0.80,
            accept_title: 0.85,
            review_total: 0.55,
            review_title: 0.70,
            strong_artist: 0.80,
            album_match: 0.85,
            duration_tolerance_ms: 2_000,
        }
    }
}

impl Thresholds {
    /// Optional overrides for benchmarking; unset or unparsable values keep the defaults.
    pub(crate) fn from_env() -> Self {
        let base = Self::default();
        let read = |key: &str, fallback: f64| {
            std::env::var(key)
                .ok()
                .and_then(|value| value.trim().parse::<f64>().ok())
                .filter(|value| (0.0..=1.0).contains(value))
                .unwrap_or(fallback)
        };
        Self {
            accept_total: read("AIRLYRICS_ACCEPT_TOTAL", base.accept_total),
            accept_title: read("AIRLYRICS_ACCEPT_TITLE", base.accept_title),
            review_total: read("AIRLYRICS_REVIEW_TOTAL", base.review_total),
            review_title: read("AIRLYRICS_REVIEW_TITLE", base.review_title),
            strong_artist: read("AIRLYRICS_STRONG_ARTIST", base.strong_artist),
            album_match: read("AIRLYRICS_ALBUM_MATCH", base.album_match),
            duration_tolerance_ms: std::env::var("AIRLYRICS_DURATION_TOLERANCE_MS")
                .ok()
                .and_then(|value| value.trim().parse::<u64>().ok())
                .unwrap_or(base.duration_tolerance_ms),
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct Evidence {
    pub(crate) title_similarity: f64,
    pub(crate) artist_similarity: Option<f64>,
    pub(crate) album_similarity: Option<f64>,
    pub(crate) duration_close: Option<bool>,
    pub(crate) alias_hit: bool,
    pub(crate) version_conflict: bool,
    pub(crate) cross_script: bool,
}

impl Evidence {
    fn artist_strong(&self, thresholds: &Thresholds) -> bool {
        self.artist_similarity
            .is_some_and(|value| value >= thresholds.strong_artist)
    }

    fn album_strong(&self, thresholds: &Thresholds) -> bool {
        self.album_similarity
            .is_some_and(|value| value >= thresholds.album_match)
    }

    /// At least one independent field agrees, not just the title.
    pub(crate) fn corroborated(&self, thresholds: &Thresholds) -> bool {
        self.artist_strong(thresholds)
            || self.duration_close == Some(true)
            || self.album_strong(thresholds)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Decision {
    /// Show automatically.
    Accept,
    /// Ask the user: show the candidate list, do not replace the current lyrics.
    Review,
    /// Do not show; keep searching other sources.
    Reject,
}

/// Full evaluation result, including the evidence trail (used by `evaluate_with`).
#[cfg_attr(not(test), allow(dead_code))]
#[derive(Clone, Copy, Debug)]
pub(crate) struct Evaluation {
    pub(crate) score: Option<MatchScore>,
    pub(crate) evidence: Evidence,
    pub(crate) decision: Decision,
}

/// Evaluate one candidate. `query` is the metadata actually used for the lookup: the alias when
/// one was confirmed, otherwise the player's own fields.
pub(crate) fn evaluate_with(
    query: MatchMetadata<'_>,
    candidate: MatchMetadata<'_>,
    alias: Option<&AliasHit>,
    thresholds: &Thresholds,
) -> Evaluation {
    let score = if alias.is_some() {
        Some(score_metadata_alias(query, candidate))
    } else {
        score_candidate(query, candidate)
    };

    let cross_script = different_scripts(query.title, candidate.title);
    let evidence = Evidence {
        title_similarity: score.map(|value| value.title_similarity).unwrap_or(0.0),
        artist_similarity: score.and_then(|value| value.artist_similarity),
        album_similarity: score.and_then(|value| value.album_similarity),
        duration_close: duration_close(
            query.duration_ms,
            candidate.duration_ms,
            thresholds.duration_tolerance_ms,
        ),
        alias_hit: alias.is_some(),
        version_conflict: crate::recording::version_conflict(&query, &candidate),
        cross_script,
    };

    let decision = decide(score, &evidence, thresholds);
    Evaluation {
        score,
        evidence,
        decision,
    }
}

/// Whether a "review" candidate may still be used by a provider. Off by default: the point of
/// the review tier is that the user decides, not that the app repeats the same guess as before.
pub(crate) fn review_is_usable() -> bool {
    std::env::var("AIRLYRICS_ALLOW_REVIEW_MATCH")
        .map(|value| {
            let value = value.trim().to_ascii_lowercase();
            value == "1" || value == "true" || value == "yes"
        })
        .unwrap_or(false)
}

fn allowed(decision: Decision) -> bool {
    match decision {
        Decision::Accept => true,
        Decision::Review => review_is_usable(),
        Decision::Reject => false,
    }
}

/// Provider-facing assessment of one candidate.
#[derive(Clone, Copy, Debug)]
pub(crate) struct Assessment {
    pub(crate) score: MatchScore,
    pub(crate) decision: Decision,
    /// True when the candidate could only be established through a confirmed alias.
    pub(crate) alias_used: bool,
}

/// Score a candidate and apply the confidence policy.
///
/// The player's own metadata is tried first; when that cannot corroborate the candidate, a
/// confirmed alias is tried as a second, independent lookup path (romanized titles can only
/// reach their localized catalogue entry that way). `None` means "do not use this candidate".
pub(crate) fn assess(query: MatchMetadata<'_>, candidate: MatchMetadata<'_>) -> Option<Assessment> {
    let thresholds = Thresholds::from_env();

    let direct = evaluate_with(query, candidate, None, &thresholds);
    if let Some(score) = direct.score.filter(|_| allowed(direct.decision)) {
        return Some(Assessment {
            score,
            decision: direct.decision,
            alias_used: false,
        });
    }

    let hit = crate::aliases::lookup(query.title, query.artist)?;
    let (alias_title, alias_artist) = crate::aliases::metadata_parts(&hit);
    let alias_metadata = MatchMetadata {
        title: &alias_title,
        artist: &alias_artist,
        album: query.album,
        duration_ms: query.duration_ms,
    };
    let aliased = evaluate_with(alias_metadata, candidate, Some(&hit), &thresholds);
    let score = aliased.score.filter(|_| allowed(aliased.decision))?;
    Some(Assessment {
        score,
        decision: aliased.decision,
        alias_used: true,
    })
}

/// Why a candidate was not accepted, for the "pick one" UI and for diagnostics.
pub(crate) fn rejection_reason(
    query: MatchMetadata<'_>,
    candidate: MatchMetadata<'_>,
) -> &'static str {
    let thresholds = Thresholds::from_env();
    let evaluation = evaluate_with(query, candidate, None, &thresholds);
    if evaluation.evidence.version_conflict {
        return "version_conflict";
    }
    if evaluation.score.is_none() {
        return "title_only";
    }
    if evaluation.evidence.cross_script {
        return "cross_script";
    }
    "weak_evidence"
}

pub(crate) fn decide(
    score: Option<MatchScore>,
    evidence: &Evidence,
    thresholds: &Thresholds,
) -> Decision {
    let Some(score) = score else {
        return Decision::Reject;
    };
    if evidence.version_conflict {
        // Different recordings: never replace the on-screen lyrics, but still offer candidates.
        return Decision::Review;
    }

    let title_ok = evidence.title_similarity >= thresholds.accept_title || evidence.alias_hit;
    // Cross-script pairs cannot be established by text similarity: they need both a strong
    // performer match and a matching running time, unless a confirmed alias is in play.
    let cross_script_ok = !evidence.cross_script
        || evidence.alias_hit
        || (evidence
            .artist_similarity
            .is_some_and(|v| v >= thresholds.strong_artist)
            && evidence.duration_close == Some(true));

    if score.total >= thresholds.accept_total
        && title_ok
        && evidence.corroborated(thresholds)
        && cross_script_ok
    {
        return Decision::Accept;
    }

    if score.total >= thresholds.review_total
        || evidence.title_similarity >= thresholds.review_title
    {
        return Decision::Review;
    }
    Decision::Reject
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::aliases::{AliasEntry, AliasHit};

    fn query() -> MatchMetadata<'static> {
        MatchMetadata {
            title: "Radio Love Song",
            artist: "Karen Mok",
            album: "",
            duration_ms: Some(242_000),
        }
    }

    fn alias() -> AliasHit {
        AliasHit {
            title: "电台情歌".to_owned(),
            artist: Some("莫文蔚".to_owned()),
            source: "user".to_owned(),
            confidence: 0.95,
        }
    }

    #[test]
    fn wrong_english_song_is_never_accepted_on_title_alone() {
        let candidate = MatchMetadata {
            title: "Radio Love Songs",
            artist: "The Highways",
            album: "Long Drives",
            duration_ms: Some(214_000),
        };
        let evaluation = evaluate_with(query(), candidate, None, &Thresholds::default());
        assert_ne!(
            evaluation.decision,
            Decision::Accept,
            "a title-only coincidence must not be shown automatically: {:?}",
            evaluation.score
        );
        assert_eq!(evaluation.decision, Decision::Review);
    }

    #[test]
    fn english_title_with_artist_and_duration_can_be_accepted() {
        let candidate = MatchMetadata {
            title: "Radio Love Song",
            artist: "Karen Mok",
            album: "Karen Mok",
            duration_ms: Some(242_500),
        };
        let evaluation = evaluate_with(query(), candidate, None, &Thresholds::default());
        assert_eq!(evaluation.decision, Decision::Accept);
    }

    fn alias_query() -> MatchMetadata<'static> {
        MatchMetadata {
            title: "电台情歌",
            artist: "莫文蔚",
            album: "",
            duration_ms: Some(242_000),
        }
    }

    #[test]
    fn alias_candidate_is_accepted_with_corroborating_evidence() {
        let candidate = MatchMetadata {
            title: "电台情歌",
            artist: "莫文蔚",
            album: "",
            duration_ms: Some(241_000),
        };
        let hit = alias();
        let evaluation =
            evaluate_with(alias_query(), candidate, Some(&hit), &Thresholds::default());
        assert_eq!(
            evaluation.decision,
            Decision::Accept,
            "{:?}",
            evaluation.evidence
        );
    }

    #[test]
    fn alias_candidate_without_corroboration_is_only_reviewed() {
        let candidate = MatchMetadata {
            title: "电台情歌",
            artist: "",
            album: "",
            duration_ms: None,
        };
        let hit = alias();
        let evaluation =
            evaluate_with(alias_query(), candidate, Some(&hit), &Thresholds::default());
        assert_ne!(
            evaluation.decision,
            Decision::Accept,
            "{:?}",
            evaluation.evidence
        );
        assert_eq!(evaluation.decision, Decision::Review);
    }

    #[test]
    fn live_version_never_matches_studio_recording() {
        let studio = MatchMetadata {
            title: "Radio Love Song",
            artist: "Karen Mok",
            album: "Studio Album",
            duration_ms: Some(242_000),
        };
        let live = MatchMetadata {
            title: "Radio Love Song (Live)",
            artist: "Karen Mok",
            album: "Live in Hong Kong",
            duration_ms: Some(242_000),
        };
        let evaluation = evaluate_with(studio, live, None, &Thresholds::default());
        assert_ne!(
            evaluation.decision,
            Decision::Accept,
            "{:?}",
            evaluation.evidence
        );
        assert_eq!(evaluation.decision, Decision::Review);
        assert!(evaluation.evidence.version_conflict);
    }

    #[test]
    fn missing_metadata_cannot_be_accepted() {
        let sparse = MatchMetadata {
            title: "Radio Love Song",
            artist: "",
            album: "",
            duration_ms: None,
        };
        let evaluation = evaluate_with(query(), sparse, None, &Thresholds::default());
        assert_ne!(evaluation.decision, Decision::Accept);
    }

    #[test]
    fn a_same_artist_song_one_second_shorter_is_never_accepted() {
        // Real case: "Susan Said" (《苏三说》, 261,826 ms) vs 陶喆《我太傻》 (260,907 ms) —
        // 919 ms apart, same artist, completely different song. The alias makes the pair a
        // cross-script lookup, so this exercises the alias path directly (no shared registry).
        let hit = AliasHit {
            title: "苏三说".to_owned(),
            artist: Some("陶喆".to_owned()),
            source: "catalogue".to_owned(),
            confidence: 0.85,
        };
        let lookup = MatchMetadata {
            title: "苏三说",
            artist: "陶喆",
            album: "太平盛世",
            duration_ms: Some(261_826),
        };
        let wrong = MatchMetadata {
            title: "我太傻",
            artist: "陶喆",
            album: "六九樂章",
            duration_ms: Some(260_907),
        };
        assert_ne!(
            evaluate_with(lookup, wrong, Some(&hit), &Thresholds::default()).decision,
            Decision::Accept,
            "a different song by the same artist must not be shown"
        );

        let right = MatchMetadata {
            title: "苏三说",
            artist: "陶喆",
            album: "太平盛世",
            duration_ms: Some(261_826),
        };
        assert_eq!(
            evaluate_with(lookup, right, Some(&hit), &Thresholds::default()).decision,
            Decision::Accept
        );
    }

    #[test]
    fn runtime_alias_registration_is_honoured() {
        crate::aliases::clear_runtime();
        crate::aliases::register(AliasEntry {
            original_title: "Radio Love Song".to_owned(),
            original_artist: "Karen Mok".to_owned(),
            original_artist_aliases: vec!["Karen Mok".to_owned()],
            alias_title: "电台情歌".to_owned(),
            alias_artist: Some("莫文蔚".to_owned()),
            source: "user".to_owned(),
            confidence: 0.9,
        });
        let hit = crate::aliases::lookup("Radio Love Song", "Karen Mok").expect("alias");
        assert_eq!(hit.title, "电台情歌");
        crate::aliases::clear_runtime();
    }
}
