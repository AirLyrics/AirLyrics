//! Title/artist aliases.
//!
//! An alias is only a **lookup** hint: it never overwrites the player's metadata and it is
//! never produced by machine translation. Every entry must carry a source and a confidence,
//! and a lookup requires the alias artist to be listed as an accepted spelling of the query
//! artist (romanized vs localized names are exactly the case this exists for).
use std::sync::{OnceLock, RwLock};

use serde::Deserialize;

use crate::matching::text_similarity;

const BUNDLED_ALIASES: &str = include_str!("../data/aliases.json");

const TITLE_MATCH_MIN: f64 = 0.92;
const ARTIST_MATCH_MIN: f64 = 0.80;
const MIN_ENTRY_CONFIDENCE: f64 = 0.60;

#[derive(Clone, Debug, Deserialize)]
pub(crate) struct AliasEntry {
    pub(crate) original_title: String,
    pub(crate) original_artist: String,
    #[serde(default)]
    pub(crate) original_artist_aliases: Vec<String>,
    pub(crate) alias_title: String,
    #[serde(default)]
    pub(crate) alias_artist: Option<String>,
    pub(crate) source: String,
    pub(crate) confidence: f64,
}

#[derive(Clone, Debug, Deserialize)]
struct AliasFile {
    version: u32,
    entries: Vec<AliasEntry>,
}

/// Result of a successful alias lookup, carrying provenance for UI and logs.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct AliasHit {
    pub(crate) title: String,
    pub(crate) artist: Option<String>,
    pub(crate) source: String,
    pub(crate) confidence: f64,
}

fn bundled() -> &'static Vec<AliasEntry> {
    static ENTRIES: OnceLock<Vec<AliasEntry>> = OnceLock::new();
    ENTRIES.get_or_init(|| {
        serde_json::from_str::<AliasFile>(BUNDLED_ALIASES)
            .map(|file| {
                let mut entries = file.entries;
                entries.retain(|entry| entry.confidence >= MIN_ENTRY_CONFIDENCE);
                debug_assert!(file.version >= 1);
                entries
            })
            .unwrap_or_default()
    })
}

fn runtime() -> &'static RwLock<Vec<AliasEntry>> {
    static RUNTIME: OnceLock<RwLock<Vec<AliasEntry>>> = OnceLock::new();
    RUNTIME.get_or_init(|| RwLock::new(Vec::new()))
}

/// Aliases confirmed at runtime (user selections) take precedence over the bundled catalogue.
pub(crate) fn register(entry: AliasEntry) {
    if entry.confidence < MIN_ENTRY_CONFIDENCE {
        return;
    }
    if let Ok(mut entries) = runtime().write() {
        entries.retain(|existing| {
            !(same_text(&existing.original_title, &entry.original_title)
                && same_text(&existing.original_artist, &entry.original_artist))
        });
        entries.push(entry);
    }
}

#[cfg(test)]
pub(crate) fn clear_runtime() {
    if let Ok(mut entries) = runtime().write() {
        entries.clear();
    }
}

fn same_text(left: &str, right: &str) -> bool {
    text_similarity(left, right) >= 0.999
}

fn artist_matches(entry: &AliasEntry, artist: &str) -> bool {
    if artist.trim().is_empty() {
        // Without an artist we cannot confirm a cross-language identity.
        return false;
    }
    let mut candidates: Vec<&str> = entry
        .original_artist_aliases
        .iter()
        .map(String::as_str)
        .collect();
    candidates.push(entry.original_artist.as_str());
    if let Some(alias_artist) = entry.alias_artist.as_deref() {
        candidates.push(alias_artist);
    }
    candidates.into_iter().any(|candidate| {
        !candidate.trim().is_empty() && text_similarity(candidate, artist) >= ARTIST_MATCH_MIN
    })
}

/// Look up an alias for the player's metadata. Returns `None` when nothing matches confidently.
pub(crate) fn lookup(title: &str, artist: &str) -> Option<AliasHit> {
    if title.trim().is_empty() {
        return None;
    }
    let from_runtime = runtime().read().ok()?;
    from_runtime
        .iter()
        .chain(bundled().iter())
        .find(|entry| {
            text_similarity(&entry.original_title, title) >= TITLE_MATCH_MIN
                && artist_matches(entry, artist)
        })
        .map(|entry| AliasHit {
            title: entry.alias_title.clone(),
            artist: entry.alias_artist.clone(),
            source: entry.source.clone(),
            confidence: entry.confidence,
        })
}

/// Owned lookup parts for a candidate that is scored against the *original* query.
pub(crate) fn metadata_parts(hit: &AliasHit) -> (String, String) {
    (hit.title.clone(), hit.artist.clone().unwrap_or_default())
}

/// Localized spellings of an artist name (romanized → localized), learned from a catalogue.
///
/// Only the artist *name* is mapped here; it never decides song identity on its own, it just
/// lets a search reach the right performer instead of a cover act.
fn artist_aliases() -> &'static RwLock<Vec<(String, String)>> {
    static ARTISTS: OnceLock<RwLock<Vec<(String, String)>>> = OnceLock::new();
    ARTISTS.get_or_init(|| RwLock::new(Vec::new()))
}

pub(crate) fn register_artist_alias(original: &str, localized: &str) {
    let (original, localized) = (original.trim(), localized.trim());
    if original.is_empty() || localized.is_empty() || original == localized {
        return;
    }
    if let Ok(mut entries) = artist_aliases().write() {
        entries.retain(|(existing, _)| !existing.eq_ignore_ascii_case(original));
        entries.push((original.to_owned(), localized.to_owned()));
    }
}

/// The localized spelling of this artist, when one has been learned.
pub(crate) fn localized_artist(artist: &str) -> Option<String> {
    let artist = artist.trim();
    if artist.is_empty() {
        return None;
    }
    artist_aliases()
        .read()
        .ok()?
        .iter()
        .find(|(original, _)| original.eq_ignore_ascii_case(artist))
        .map(|(_, localized)| localized.clone())
}

#[cfg(test)]
pub(crate) fn clear_artist_aliases() {
    if let Ok(mut entries) = artist_aliases().write() {
        entries.clear();
    }
}

/// Lookup terms derived from a confirmed alias, tried before any relaxed variant. Empty when no
/// alias applies — a lookup is never a machine translation, so nothing is invented here.
pub(crate) fn search_queries(title: &str, artist: &str) -> Vec<(String, Option<String>)> {
    let Some(hit) = lookup(title, artist) else {
        return Vec::new();
    };
    let alias_artist = hit
        .artist
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_owned);
    match alias_artist {
        Some(artist) => vec![(hit.title.clone(), Some(artist)), (hit.title, None)],
        None => vec![(hit.title, None)],
    }
}

/// Free-text form of [`search_queries`].
pub(crate) fn search_keywords(title: &str, artist: &str) -> Vec<String> {
    search_queries(title, artist)
        .into_iter()
        .map(|(title, artist)| match artist {
            Some(artist) => format!("{title} {artist}"),
            None => title,
        })
        .collect()
}
