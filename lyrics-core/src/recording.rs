//! Recording-identity helpers: version markers and same-recording checks.
//!
//! Metadata cannot prove two files are the same recording. These helpers only make
//! differences *visible* so the confidence policy can refuse an automatic match when the
//! version markers disagree (live vs studio, remix, acoustic, cover, instrumental).
use crate::matching::MatchMetadata;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct VersionMarkers {
    pub(crate) live: bool,
    pub(crate) remix: bool,
    pub(crate) acoustic: bool,
    pub(crate) instrumental: bool,
    pub(crate) cover: bool,
    pub(crate) karaoke: bool,
    pub(crate) demo: bool,
    pub(crate) session: bool,
}

impl VersionMarkers {
    pub(crate) fn any(self) -> bool {
        self.live
            || self.remix
            || self.acoustic
            || self.instrumental
            || self.cover
            || self.karaoke
            || self.demo
            || self.session
    }

    /// Bit mask so two marker sets can be compared symmetrically.
    fn mask(self) -> u8 {
        (self.live as u8)
            | (self.remix as u8) << 1
            | (self.acoustic as u8) << 2
            | (self.instrumental as u8) << 3
            | (self.cover as u8) << 4
            | (self.karaoke as u8) << 5
            | (self.demo as u8) << 6
            | (self.session as u8) << 7
    }
}

type MarkerFn = fn(&mut VersionMarkers);

const MARKERS: &[(&str, MarkerFn)] = &[
    ("live", |m| m.live = true),
    ("live at", |m| m.live = true),
    ("concert", |m| m.live = true),
    ("现场", |m| m.live = true),
    ("演唱会", |m| m.live = true),
    ("remix", |m| m.remix = true),
    ("mix", |m| m.remix = true),
    ("混音", |m| m.remix = true),
    ("acoustic", |m| m.acoustic = true),
    ("unplugged", |m| m.acoustic = true),
    ("不插电", |m| m.acoustic = true),
    ("instrumental", |m| m.instrumental = true),
    ("off vocal", |m| m.instrumental = true),
    ("inst.", |m| m.instrumental = true),
    ("伴奏", |m| m.instrumental = true),
    ("cover", |m| m.cover = true),
    ("tribute", |m| m.cover = true),
    ("翻唱", |m| m.cover = true),
    ("karaoke", |m| m.karaoke = true),
    ("卡拉ok", |m| m.karaoke = true),
    ("demo", |m| m.demo = true),
    ("session", |m| m.session = true),
];

pub(crate) fn markers(value: &str) -> VersionMarkers {
    let lowered = value.to_lowercase();
    let mut found = VersionMarkers::default();
    for (needle, apply) in MARKERS {
        if contains_marker(&lowered, needle) {
            apply(&mut found);
        }
    }
    found
}

/// Substring matching would flag ordinary words: the album `Recovery` contains `cover`, the
/// title `Democracy` contains `demo`. Require the needle to sit on non-alphanumeric bounds.
fn contains_marker(lowered: &str, needle: &str) -> bool {
    let mut cursor = 0;
    while let Some(offset) = lowered[cursor..].find(needle) {
        let start = cursor + offset;
        let end = start + needle.len();
        let before_ok = lowered[..start]
            .chars()
            .next_back()
            .is_none_or(|character| !character.is_alphanumeric());
        // CJK has no word boundaries, so only the Latin needles get a trailing check.
        let ascii_needle = needle.is_ascii();
        let after_ok = !ascii_needle
            || lowered[end..]
                .chars()
                .next()
                .is_none_or(|character| !character.is_alphanumeric());
        if before_ok && after_ok {
            return true;
        }
        cursor = end;
    }
    false
}

/// True when the two metadata sets disagree about the recording version. A missing marker on
/// either side is not a disagreement — absence of evidence is not evidence.
pub(crate) fn version_conflict(left: &MatchMetadata<'_>, right: &MatchMetadata<'_>) -> bool {
    let left_text = format!("{} {}", left.title, left.album);
    let right_text = format!("{} {}", right.title, right.album);
    let left_markers = markers(&left_text);
    let right_markers = markers(&right_text);
    // One-sided markers still disagree: a studio master versus an explicitly marked
    // "(Live)" / "(Remix)" / "(Acoustic)" release are different recordings.
    (left_markers.any() || right_markers.any()) && left_markers.mask() != right_markers.mask()
}

/// Duration evidence: identical recordings of the same master usually land within seconds.
pub(crate) fn duration_close(
    left: Option<u64>,
    right: Option<u64>,
    tolerance_ms: u64,
) -> Option<bool> {
    let (left, right) = (left.filter(|ms| *ms > 0)?, right.filter(|ms| *ms > 0)?);
    Some(left.abs_diff(right) <= tolerance_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ordinary_words_are_not_mistaken_for_markers() {
        assert!(!contains_marker("recovery", "cover"));
        assert!(!contains_marker("democracy", "demo"));
        assert!(!contains_marker("my girl (deluxe)", "girl live"));
    }

    #[test]
    fn marked_versions_are_recognised() {
        assert!(contains_marker("(cover version)", "cover"));
        assert!(contains_marker("live at the bowl", "live"));
        assert!(contains_marker("demo tape", "demo"));
        assert!(contains_marker("伴奏版", "伴奏"));
    }
}
