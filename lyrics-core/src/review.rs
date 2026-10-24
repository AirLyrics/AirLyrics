//! Candidates that are *plausible but unproven*.
//!
//! The confidence policy refuses to show them automatically. Rather than throwing the evidence
//! away, they are parked here so the app can offer them for confirmation — the user decides,
//! the engine never guesses. Exactly the most recent lookup is retained.
#[cfg(not(test))]
use std::sync::Mutex;

/// The recording the parked candidates were found for.
#[derive(Clone, Debug, serde::Serialize, PartialEq)]
pub(crate) struct ParkedCandidate {
    pub(crate) title: String,
    pub(crate) artist: String,
    pub(crate) album: String,
    pub(crate) duration_ms: Option<u64>,
    pub(crate) provider: &'static str,
    pub(crate) score: f64,
    /// Why it was not used: `title_only`, `weak_evidence`, `version_conflict`, `cross_script`.
    pub(crate) reason: &'static str,
    pub(crate) alias_hit: bool,
}

#[cfg(not(test))]
static PARKED: Mutex<Option<(String, Vec<ParkedCandidate>)>> = Mutex::new(None);

#[cfg(test)]
thread_local! {
    static PARKED: std::cell::RefCell<Option<(String, Vec<ParkedCandidate>)>> =
        const { std::cell::RefCell::new(None) };
}

/// Key identifying the playback metadata a candidate set belongs to.
pub(crate) fn query_key(title: &str, artist: &str, duration_ms: Option<u64>) -> String {
    format!(
        "{}|{}|{}",
        title.trim().to_lowercase(),
        artist.trim().to_lowercase(),
        duration_ms.map(|value| value / 1000).unwrap_or(0)
    )
}

/// Parks one candidate, replacing the slot when the playback metadata changed.
#[cfg(not(test))]
pub(crate) fn park(key: &str, candidate: ParkedCandidate) {
    if let Ok(mut slot) = PARKED.lock() {
        match slot.as_mut() {
            Some((current, candidates)) if current == key => {
                candidates.retain(|existing| {
                    !(existing.title == candidate.title
                        && existing.artist == candidate.artist
                        && existing.provider == candidate.provider)
                });
                candidates.push(candidate);
                candidates.truncate(MAX_PARKED);
            }
            _ => *slot = Some((key.to_owned(), vec![candidate])),
        }
    }
}

const MAX_PARKED: usize = 5;

/// JSON for the UI: `{"query": "<key>", "candidates": [...]}`. Consumes the slot.
#[cfg(not(test))]
pub(crate) fn take_json() -> String {
    let taken = PARKED.lock().ok().and_then(|mut slot| slot.take());
    match taken {
        Some((key, candidates)) => {
            serde_json::json!({ "query": key, "candidates": candidates }).to_string()
        }
        None => serde_json::json!({ "query": null, "candidates": [] }).to_string(),
    }
}

/// Serializes tests that touch the process-wide parking slot.
#[cfg(test)]
pub(crate) fn test_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| std::sync::Mutex::new(()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
pub(crate) fn park(key: &str, candidate: ParkedCandidate) {
    PARKED.with(|slot| {
        let mut slot = slot.borrow_mut();
        match slot.as_mut() {
            Some((current, candidates)) if current == key => {
                candidates.retain(|existing| {
                    !(existing.title == candidate.title
                        && existing.artist == candidate.artist
                        && existing.provider == candidate.provider)
                });
                candidates.push(candidate);
                candidates.truncate(MAX_PARKED);
            }
            _ => *slot = Some((key.to_owned(), vec![candidate])),
        }
    });
}

#[cfg(test)]
pub(crate) fn take_json() -> String {
    let taken = PARKED.with(|slot| slot.borrow_mut().take());
    match taken {
        Some((key, candidates)) => {
            serde_json::json!({ "query": key, "candidates": candidates }).to_string()
        }
        None => serde_json::json!({ "query": null, "candidates": [] }).to_string(),
    }
}

#[cfg(test)]
pub(crate) fn clear() {
    PARKED.with(|slot| *slot.borrow_mut() = None);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(title: &str, reason: &'static str) -> ParkedCandidate {
        ParkedCandidate {
            title: title.to_owned(),
            artist: "莫文蔚".to_owned(),
            album: String::new(),
            duration_ms: Some(241_000),
            provider: "lrclib",
            score: 0.42,
            reason,
            alias_hit: true,
        }
    }

    #[test]
    fn parked_candidates_are_returned_once_for_the_same_lookup() {
        let _guard = test_lock();
        clear();
        let key = query_key("Radio Love Song", "Karen Mok", Some(242_000));
        park(&key, candidate("电台情歌", "weak_evidence"));

        let payload = take_json();
        let parsed: serde_json::Value = serde_json::from_str(&payload).expect("json");
        assert_eq!(parsed["query"], serde_json::json!(key));
        assert_eq!(parsed["candidates"].as_array().map(Vec::len), Some(1));
        assert_eq!(
            parsed["candidates"][0]["reason"],
            serde_json::json!("weak_evidence")
        );

        // Consumed: a second read must not resurrect the old candidates.
        let again: serde_json::Value = serde_json::from_str(&take_json()).expect("json");
        assert_eq!(again["candidates"].as_array().map(Vec::len), Some(0));
    }

    #[test]
    fn a_new_song_replaces_the_parked_set() {
        let _guard = test_lock();
        clear();
        park(
            &query_key("Song A", "Artist", Some(1_000)),
            candidate("A", "weak_evidence"),
        );
        park(
            &query_key("Song B", "Artist", Some(2_000)),
            candidate("B", "version_conflict"),
        );

        let parsed: serde_json::Value = serde_json::from_str(&take_json()).expect("json");
        let candidates = parsed["candidates"].as_array().expect("array");
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0]["title"], serde_json::json!("B"));
        assert_eq!(
            candidates[0]["reason"],
            serde_json::json!("version_conflict")
        );
    }
}
