use crate::lrc;
use std::collections::{HashMap, HashSet};
use unicode_normalization::UnicodeNormalization;

pub(crate) const EARLY_ACCEPT_SCORE: f64 = 0.70;
pub(crate) const MIN_TITLE_SIMILARITY: f64 = 0.55;
const EARLY_ACCEPT_TITLE_SIMILARITY: f64 = 0.80;
const EARLY_ACCEPT_MIN_ARTIST_SIMILARITY: f64 = 0.35;

const TITLE_WEIGHT: f64 = 0.54;
const ARTIST_WEIGHT: f64 = 0.30;
const DURATION_WEIGHT: f64 = 0.13;
const ALBUM_WEIGHT: f64 = 0.03;
const VERSION_GROUP_PENALTY: f64 = 0.04;
const MAX_VERSION_PENALTY: f64 = 0.12;

const VERSION_MARKER_GROUPS: &[&[&str]] = &[
    &[
        "live",
        "concert",
        "现场",
        "現場",
        "现场版",
        "現場版",
        "라이브",
        "ライブ",
        "ライブ版",
        "ライブバージョン",
        "ライヴ",
        "ライヴ版",
    ],
    &[
        "remix",
        "remixed",
        "rework",
        "original mix",
        "club mix",
        "混音",
        "混音版",
        "dj版",
        "リミックス",
        "リミックス版",
        "リミックスバージョン",
    ],
    &[
        "acoustic",
        "unplugged",
        "不插电",
        "不插電",
        "アコースティック",
        "アコースティック版",
        "アコースティックバージョン",
    ],
    &[
        "instrumental",
        "karaoke",
        "伴奏",
        "纯音乐",
        "純音樂",
        "インスト",
        "インスト版",
        "インストゥルメンタル",
        "インストゥルメンタル版",
        "インストゥルメンタルバージョン",
        "カラオケ",
    ],
    &[
        "sped up",
        "speed up",
        "nightcore",
        "加速",
        "加速版",
        "スピードアップ",
        "高速版",
    ],
    &[
        "slowed",
        "slowed down",
        "慢速",
        "慢速版",
        "减速",
        "減速",
        "减速版",
        "減速版",
        "スロー",
        "スロー版",
        "スローバージョン",
    ],
    &[
        "remaster",
        "remastered",
        "re mastered",
        "重制",
        "重製",
        "重制版",
        "重製版",
        "リマスター",
        "リマスター版",
        "リマスターバージョン",
        "リマスタリング",
    ],
    &[
        "radio edit",
        "single edit",
        "edit",
        "剪辑版",
        "剪輯版",
        "ラジオエディット",
        "シングルエディット",
        "エディット",
    ],
    &["demo", "demo version", "小样", "小樣", "デモ", "デモ版"],
    &[
        "cover",
        "cover version",
        "翻唱",
        "翻唱版",
        "カバー",
        "カバー版",
    ],
    &[
        "extended",
        "extended version",
        "加长版",
        "加長版",
        "拡張版",
        "ロングバージョン",
    ],
    &[
        "album version",
        "single version",
        "alternate version",
        "original version",
        "taylor s version",
        "deluxe edition",
        "特别版",
        "特別版",
        "アルバムバージョン",
        "シングルバージョン",
        "別バージョン",
        "オリジナルバージョン",
    ],
    &[
        "mono",
        "stereo",
        "mono mix",
        "stereo mix",
        "单声道",
        "單聲道",
        "モノラル",
        "ステレオ",
    ],
];

#[derive(Clone, Copy, Debug)]
pub(crate) struct MatchMetadata<'a> {
    pub(crate) title: &'a str,
    pub(crate) artist: &'a str,
    pub(crate) album: &'a str,
    pub(crate) duration_ms: Option<u64>,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct MatchScore {
    pub(crate) total: f64,
    pub(crate) title_similarity: f64,
    pub(crate) artist_similarity: Option<f64>,
    pub(crate) album_similarity: Option<f64>,
    pub(crate) duration_similarity: Option<f64>,
    pub(crate) version_penalty: f64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct SearchQuery {
    pub(crate) title: String,
    pub(crate) artist: Option<String>,
}

/// Builds progressively broader queries without repeating equivalent requests.
///
/// The order is full title + artist, simplified title + artist, full title only, then
/// simplified title only. Empty artists naturally start at the title-only rounds.
pub(crate) fn build_search_queries(title: &str, artist: &str) -> Vec<SearchQuery> {
    let title = clean_query_part(title);
    if title.is_empty() {
        return Vec::new();
    }

    let artist = non_empty(clean_query_part(artist));
    let simplified_title = simplify_title(&title);
    let mut queries = Vec::new();
    let mut seen = HashSet::new();

    if let Some(artist) = artist.as_deref() {
        push_unique_query(&mut queries, &mut seen, &title, Some(artist));
        if simplified_title != title {
            push_unique_query(&mut queries, &mut seen, &simplified_title, Some(artist));
        }
    }

    push_unique_query(&mut queries, &mut seen, &title, None);
    if simplified_title != title {
        push_unique_query(&mut queries, &mut seen, &simplified_title, None);
    }

    queries
}

pub(crate) fn score_candidate(
    query: MatchMetadata<'_>,
    candidate: MatchMetadata<'_>,
) -> Option<MatchScore> {
    let title_similarity = title_similarity(query.title, candidate.title);
    if title_similarity < MIN_TITLE_SIMILARITY {
        return None;
    }

    let artist_similarity = compare_optional_text(query.artist, candidate.artist);
    let album_similarity = compare_optional_text(query.album, candidate.album);
    let duration_similarity = compare_optional_duration(query.duration_ms, candidate.duration_ms);

    let mut weighted_sum = title_similarity * TITLE_WEIGHT;
    let mut compared_weight = TITLE_WEIGHT;
    if let Some(value) = artist_similarity {
        weighted_sum += value * ARTIST_WEIGHT;
        compared_weight += ARTIST_WEIGHT;
    }
    if let Some(value) = duration_similarity {
        weighted_sum += value * DURATION_WEIGHT;
        compared_weight += DURATION_WEIGHT;
    }
    if let Some(value) = album_similarity {
        weighted_sum += value * ALBUM_WEIGHT;
        compared_weight += ALBUM_WEIGHT;
    }

    let version_penalty = version_difference_penalty(query.title, candidate.title);
    let total = (weighted_sum / compared_weight - version_penalty).clamp(0.0, 1.0);
    Some(MatchScore {
        total,
        title_similarity,
        artist_similarity,
        album_similarity,
        duration_similarity,
        version_penalty,
    })
}

/// A candidate may remain eligible for the final fallback without being strong enough to stop
/// broader searches. Missing metadata is neutral for ranking, but cannot inflate a weak title into
/// an early return; an explicit artist conflict also requires the remaining variants to run.
pub(crate) fn is_confident_early_match(score: &MatchScore) -> bool {
    score.total >= EARLY_ACCEPT_SCORE
        && score.title_similarity >= EARLY_ACCEPT_TITLE_SIMILARITY
        && !score
            .artist_similarity
            .is_some_and(|value| value < EARLY_ACCEPT_MIN_ARTIST_SIMILARITY)
}

/// Keeps only LRC with at least one parseable timestamp carrying non-empty lyric text.
pub(crate) fn normalize_usable_lrc(value: String) -> Option<String> {
    let value = value.replace("\r\n", "\n").replace('\r', "\n");
    let has_timed_lyric_body = lrc::parse_lrc_entries(&value)
        .iter()
        .any(|(_, text)| has_lyric_body(text));
    if value.trim().is_empty() || !has_timed_lyric_body {
        None
    } else {
        Some(value)
    }
}

fn has_lyric_body(text: &str) -> bool {
    let mut remaining = text.trim();
    while let Some(after_open) = remaining.strip_prefix('[') {
        let Some(end) = after_open.find(']') else {
            return true;
        };
        let tag = after_open[..end].trim().to_lowercase();
        let key = tag.split_once(':').map(|(key, _)| key.trim());
        if !matches!(
            key,
            Some(
                "ar" | "al"
                    | "ti"
                    | "au"
                    | "by"
                    | "re"
                    | "ve"
                    | "length"
                    | "offset"
                    | "tool"
                    | "id"
                    | "hash"
                    | "language"
            )
        ) {
            return true;
        }
        remaining = after_open[end + 1..].trim_start();
    }
    !remaining.is_empty()
}

pub(crate) fn simplify_title(value: &str) -> String {
    let mut title = clean_query_part(value);
    loop {
        if let Some((prefix, suffix)) = trailing_modifier(&title) {
            if is_known_modifier(suffix) {
                let simplified = clean_query_part(prefix);
                if !simplified.is_empty() && simplified != title {
                    title = simplified;
                    continue;
                }
            }
        }

        if let Some(index) = feature_suffix_start(&title) {
            let prefix = clean_query_part(&title[..index]);
            if !prefix.is_empty() && prefix != title {
                title = prefix;
                continue;
            }
        }

        break;
    }
    title
}

pub(crate) fn text_similarity(left: &str, right: &str) -> f64 {
    let left_identity = normalize_identity(left);
    let right_identity = normalize_identity(right);
    if left_identity.is_empty() || right_identity.is_empty() {
        return 0.0;
    }
    if left_identity == right_identity {
        return 1.0;
    }

    let left_words = normalize_words(left);
    let right_words = normalize_words(right);
    let left_compact = compact(&left_words);
    let right_compact = compact(&right_words);

    if left_compact.is_empty() || right_compact.is_empty() {
        return 0.0;
    }
    if left_compact == right_compact {
        return 1.0;
    }

    let length_ratio = left_compact
        .chars()
        .count()
        .min(right_compact.chars().count()) as f64
        / left_compact
            .chars()
            .count()
            .max(right_compact.chars().count()) as f64;
    let token_overlap = token_dice_coefficient(&left_words, &right_words);
    let token_score = token_overlap * length_ratio;
    let left_token_count = left_words.split_whitespace().count();
    let right_token_count = right_words.split_whitespace().count();
    let mut char_score =
        dice_coefficient(&left_compact, &right_compact) * (0.5 + length_ratio * 0.5);

    if left_token_count > 1 || right_token_count > 1 {
        // Character bigrams alone overrate titles that merely share a short prefix. Exact token
        // overlap keeps reordered titles strong while damping "Song A" vs "Song B".
        char_score *= token_overlap.sqrt();
        if left_token_count != right_token_count
            && token_sequence_contains(&left_words, &right_words)
        {
            char_score = char_score.min(token_score);
        }
    }

    char_score.max(token_score)
}

fn title_similarity(query_title: &str, candidate_title: &str) -> f64 {
    let query_simple = simplify_title(query_title);
    let candidate_simple = simplify_title(candidate_title);
    [
        text_similarity(query_title, candidate_title),
        text_similarity(&query_simple, candidate_title),
        text_similarity(query_title, &candidate_simple),
        text_similarity(&query_simple, &candidate_simple),
    ]
    .into_iter()
    .fold(0.0, f64::max)
}

fn compare_optional_text(left: &str, right: &str) -> Option<f64> {
    let left = clean_query_part(left);
    let right = clean_query_part(right);
    if left.is_empty() || right.is_empty() {
        None
    } else {
        Some(text_similarity(&left, &right))
    }
}

fn compare_optional_duration(left: Option<u64>, right: Option<u64>) -> Option<f64> {
    let (left, right) = left
        .filter(|value| *value > 0)
        .zip(right.filter(|value| *value > 0))?;
    let difference = left.abs_diff(right);
    Some(match difference {
        0..=2_000 => 1.0,
        2_001..=5_000 => 0.85,
        5_001..=10_000 => 0.60,
        10_001..=15_000 => 0.35,
        _ => 0.0,
    })
}

fn version_difference_penalty(query_title: &str, candidate_title: &str) -> f64 {
    let query_groups = version_groups(query_title);
    let candidate_groups = version_groups(candidate_title);
    let differences = (query_groups ^ candidate_groups).count_ones() as f64;
    (differences * VERSION_GROUP_PENALTY).min(MAX_VERSION_PENALTY)
}

fn version_groups(value: &str) -> u16 {
    let normalized = normalize_words(value);
    VERSION_MARKER_GROUPS
        .iter()
        .enumerate()
        .fold(0_u16, |mask, (index, markers)| {
            if markers
                .iter()
                .any(|marker| contains_marker(&normalized, marker))
            {
                mask | (1 << index)
            } else {
                mask
            }
        })
}

fn trailing_modifier(value: &str) -> Option<(&str, &str)> {
    for (open, close) in [('(', ')'), ('[', ']'), ('（', '）'), ('【', '】')] {
        if value.ends_with(close) {
            let Some(index) = value.rfind(open) else {
                continue;
            };
            let prefix = value[..index].trim_end();
            if !prefix.is_empty() {
                return Some((
                    prefix,
                    &value[index + open.len_utf8()..value.len() - close.len_utf8()],
                ));
            }
        }
    }

    [" - ", " – ", " — ", " | ", "-", "–", "—", "|"]
        .iter()
        .filter_map(|separator| value.rsplit_once(*separator))
        .max_by_key(|(prefix, _)| prefix.len())
}

fn is_known_modifier(value: &str) -> bool {
    const EXACT_NON_VERSION_MODIFIERS: &[&str] = &[
        "official video",
        "official audio",
        "official music video",
        "official lyric video",
        "official lyrics video",
        "music video",
        "lyric video",
        "lyrics video",
        "lyrics",
        "audio",
        "video",
        "mv",
        "bonus track",
        "explicit",
        "clean",
        "完整版",
        "官方音频",
        "官方音頻",
        "歌词版",
        "歌詞版",
    ];

    let normalized = normalize_words(value);
    if normalized.is_empty() {
        return false;
    }

    is_feature_modifier(&normalized)
        || EXACT_NON_VERSION_MODIFIERS.contains(&normalized.as_str())
        || [
            "official video ",
            "official audio ",
            "official music video ",
            "official lyric video ",
            "official lyrics video ",
            "music video ",
            "lyric video ",
            "lyrics video ",
        ]
        .iter()
        .any(|prefix| normalized.starts_with(prefix))
        || is_clear_version_modifier(&normalized)
}

fn feature_suffix_start(value: &str) -> Option<usize> {
    let bytes = value.as_bytes();
    let mut earliest = None;
    for marker in [" feat.", " feat ", " ft.", " ft ", " featuring "] {
        let marker = marker.as_bytes();
        if marker.len() > bytes.len() {
            continue;
        }
        for index in 0..=bytes.len() - marker.len() {
            let end = index + marker.len();
            if value.is_char_boundary(index)
                && value.is_char_boundary(end)
                && bytes[index..end].eq_ignore_ascii_case(marker)
                && is_outside_brackets(value, index)
            {
                earliest = Some(earliest.map_or(index, |current: usize| current.min(index)));
            }
        }
    }
    earliest
}

fn is_feature_modifier(value: &str) -> bool {
    ["feat ", "featuring ", "ft "]
        .iter()
        .any(|prefix| value.starts_with(prefix))
}

fn is_clear_version_modifier(value: &str) -> bool {
    const EXACT: &[&str] = &[
        "live",
        "live version",
        "live recording",
        "live concert",
        "concert",
        "concert version",
        "concert live",
        "remix",
        "remixed",
        "rework",
        "acoustic",
        "acoustic version",
        "unplugged",
        "instrumental",
        "instrumental version",
        "karaoke",
        "sped up",
        "speed up",
        "nightcore",
        "slowed",
        "slowed down",
        "remaster",
        "remastered",
        "radio edit",
        "single edit",
        "edit",
        "demo",
        "cover",
        "cover version",
        "extended",
        "extended version",
        "original mix",
        "album version",
        "single version",
        "alternate version",
        "original version",
        "deluxe edition",
        "mono",
        "stereo",
        "from the vault",
        "现场版",
        "現場版",
        "ライブ",
        "라이브",
    ];
    const UNIQUE_MARKERS: &[&str] = &[
        "remaster",
        "remastered",
        "re mastered",
        "现场版",
        "現場版",
        "混音版",
        "不插电",
        "不插電",
        "伴奏",
        "纯音乐",
        "純音樂",
        "加速版",
        "慢速版",
        "减速版",
        "減速版",
        "重制版",
        "重製版",
        "翻唱版",
        "dj版",
    ];

    exact_version_marker(value)
        || EXACT.contains(&value)
        || [
            "live at ",
            "live from ",
            "live in ",
            "live on ",
            "recorded live ",
            "remix ",
            "remixed ",
            "rework ",
            "cover by ",
        ]
        .iter()
        .any(|prefix| value.starts_with(prefix))
        || [
            " remix", " rework", " mix", " edit", " version", " edition", " demo", " cover",
        ]
        .iter()
        .any(|suffix| value.ends_with(suffix))
        || UNIQUE_MARKERS.iter().any(|marker| value.contains(marker))
}

fn exact_version_marker(value: &str) -> bool {
    VERSION_MARKER_GROUPS
        .iter()
        .flat_map(|group| group.iter())
        .any(|marker| normalize_words(marker) == value)
}

fn is_outside_brackets(value: &str, byte_index: usize) -> bool {
    let mut depth = 0_u32;
    for ch in value[..byte_index].chars() {
        match ch {
            '(' | '[' | '（' | '【' => depth += 1,
            ')' | ']' | '）' | '】' => depth = depth.saturating_sub(1),
            _ => {}
        }
    }
    depth == 0
}

fn push_unique_query(
    queries: &mut Vec<SearchQuery>,
    seen: &mut HashSet<String>,
    title: &str,
    artist: Option<&str>,
) {
    let key = format!(
        "{}\0{}",
        normalize_words(title),
        artist.map(normalize_words).unwrap_or_default()
    );
    if seen.insert(key) {
        queries.push(SearchQuery {
            title: title.to_string(),
            artist: artist.map(ToString::to_string),
        });
    }
}

fn contains_marker(normalized_value: &str, marker: &str) -> bool {
    let marker = normalize_words(marker);
    if !marker.is_ascii() {
        normalized_value.contains(&marker)
    } else {
        format!(" {normalized_value} ").contains(&format!(" {marker} "))
    }
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

fn normalize_words(value: &str) -> String {
    value
        .nfkc()
        .collect::<String>()
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

fn normalize_identity(value: &str) -> String {
    value
        .nfkc()
        .collect::<String>()
        .to_lowercase()
        .replace(['\n', '\r', '\t'], " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn compact(value: &str) -> String {
    value.chars().filter(|ch| !ch.is_whitespace()).collect()
}

fn token_dice_coefficient(left: &str, right: &str) -> f64 {
    let left_tokens = left.split_whitespace().collect::<Vec<_>>();
    let right_tokens = right.split_whitespace().collect::<Vec<_>>();
    if left_tokens.is_empty() || right_tokens.is_empty() {
        return 0.0;
    }

    let mut right_counts = HashMap::<&str, usize>::new();
    for token in &right_tokens {
        *right_counts.entry(token).or_default() += 1;
    }
    let mut intersection = 0_usize;
    for token in &left_tokens {
        if let Some(count) = right_counts.get_mut(token) {
            if *count > 0 {
                *count -= 1;
                intersection += 1;
            }
        }
    }

    (2.0 * intersection as f64) / (left_tokens.len() + right_tokens.len()) as f64
}

fn token_sequence_contains(left: &str, right: &str) -> bool {
    let left = format!(" {left} ");
    let right = format!(" {right} ");
    left.contains(&right) || right.contains(&left)
}

fn dice_coefficient(left: &str, right: &str) -> f64 {
    let left = left.chars().collect::<Vec<_>>();
    let right = right.chars().collect::<Vec<_>>();
    if left.len() < 2 || right.len() < 2 {
        return 0.0;
    }

    let left_pairs = left.windows(2).map(|pair| (pair[0], pair[1]));
    let mut right_pairs = right
        .windows(2)
        .map(|pair| (pair[0], pair[1]))
        .collect::<Vec<_>>();
    let mut intersection = 0;
    for pair in left_pairs {
        if let Some(index) = right_pairs.iter().position(|candidate| *candidate == pair) {
            intersection += 1;
            right_pairs.remove(index);
        }
    }

    (2.0 * intersection as f64) / (left.len() + right.len() - 2) as f64
}

fn non_empty(value: String) -> Option<String> {
    (!value.is_empty()).then_some(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn metadata<'a>(
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

    #[test]
    fn search_queries_progressively_relax_and_deduplicate() {
        assert_eq!(
            build_search_queries("Song (Live)", "Artist"),
            vec![
                SearchQuery {
                    title: "Song (Live)".to_string(),
                    artist: Some("Artist".to_string()),
                },
                SearchQuery {
                    title: "Song".to_string(),
                    artist: Some("Artist".to_string()),
                },
                SearchQuery {
                    title: "Song (Live)".to_string(),
                    artist: None,
                },
                SearchQuery {
                    title: "Song".to_string(),
                    artist: None,
                },
            ]
        );
        assert_eq!(
            build_search_queries("Song", ""),
            vec![SearchQuery {
                title: "Song".to_string(),
                artist: None,
            }]
        );
    }

    #[test]
    fn title_simplification_only_removes_known_trailing_modifiers() {
        assert_eq!(simplify_title("Song (2011 Remastered)"), "Song");
        assert_eq!(simplify_title("Song - Live at Wembley"), "Song");
        assert_eq!(simplify_title("Song (Remix by Guest)"), "Song");
        assert_eq!(simplify_title("Song (Official Music Video)"), "Song");
        assert_eq!(simplify_title("Song feat. Guest"), "Song");
        assert_eq!(simplify_title("Song feat.Guest (Live)"), "Song");
        assert_eq!(simplify_title("İ feat. Guest"), "İ");
        assert_eq!(simplify_title("Song (Chapter Two)"), "Song (Chapter Two)");
        assert_eq!(simplify_title("Song (Video Games)"), "Song (Video Games)");
        assert_eq!(
            simplify_title("Song (With or Without You)"),
            "Song (With or Without You)"
        );
        assert_eq!(simplify_title("Song (Live Forever)"), "Song (Live Forever)");
        assert_eq!(
            simplify_title("(I Can't Get No) Satisfaction"),
            "(I Can't Get No) Satisfaction"
        );
        assert_eq!(simplify_title("花火（リミックス）"), "花火");
        assert_eq!(simplify_title("花火-アコースティック版"), "花火");
        assert_eq!(simplify_title("花火（リマスター版）"), "花火");
        assert_eq!(simplify_title("花火 - ライブバージョン"), "花火");
        assert_eq!(simplify_title("花火（物語）"), "花火(物語)");
        assert_eq!(
            simplify_title("花火（バージョン情報）"),
            "花火(バージョン情報)"
        );
    }

    #[test]
    fn compatibility_normalization_handles_full_width_and_symbol_titles() {
        assert_eq!(text_similarity("Ｓｏｎｇ", "Song"), 1.0);
        assert_eq!(text_similarity("ＡＢＣ（Live）", "ABC (Live)"), 1.0);
        assert_eq!(text_similarity("♡", "♡"), 1.0);
        assert_eq!(text_similarity("♡", "☆"), 0.0);
        assert_eq!(text_similarity("后来的我们", "後來的我們"), 0.25);
    }

    #[test]
    fn similarity_does_not_promote_short_prefixes_or_repeated_words() {
        for (query, candidate) in [
            ("A", "A Thousand Years"),
            ("Love", "Love Story"),
            ("La La La", "La"),
            ("Song A", "Song B"),
        ] {
            assert!(
                text_similarity(query, candidate) < MIN_TITLE_SIMILARITY,
                "{query:?} should not match {candidate:?}"
            );
        }

        assert!(text_similarity("Blue Moon Rising", "Rising Blue Moon") >= MIN_TITLE_SIMILARITY);
    }

    #[test]
    fn missing_artist_is_not_treated_as_an_explicit_mismatch() {
        let missing = score_candidate(
            metadata("Song", "", "", None),
            metadata("Song", "Candidate Artist", "", None),
        )
        .expect("related title");
        let mismatch = score_candidate(
            metadata("Song", "Query Artist", "", None),
            metadata("Song", "Different Performer", "", None),
        )
        .expect("related title");

        assert_eq!(missing.artist_similarity, None);
        assert_eq!(missing.total, 1.0);
        assert!(mismatch.artist_similarity.is_some_and(|score| score < 0.1));
        assert!(mismatch.total < EARLY_ACCEPT_SCORE);
    }

    #[test]
    fn early_accept_requires_strong_title_evidence_without_artist_conflict() {
        let weak_title = score_candidate(
            metadata("后来", "歌手", "", None),
            metadata("后来呢", "歌手", "", None),
        )
        .expect("minimum-related title");
        assert!(weak_title.total >= EARLY_ACCEPT_SCORE);
        assert!(!is_confident_early_match(&weak_title));

        let missing_artist = score_candidate(
            metadata("Ｓｏｎｇ", "Artist", "", None),
            metadata("Song", "", "", None),
        )
        .expect("compatibility-equivalent title");
        assert!(is_confident_early_match(&missing_artist));

        let conflicting_artist = score_candidate(
            metadata("Song", "Query Artist", "Album", Some(180_000)),
            metadata("Song", "Different Performer", "Album", Some(180_000)),
        )
        .expect("title-related candidate");
        assert!(conflicting_artist.total >= EARLY_ACCEPT_SCORE);
        assert!(!is_confident_early_match(&conflicting_artist));
    }

    #[test]
    fn low_total_related_title_remains_eligible_for_final_fallback() {
        let score = score_candidate(
            metadata("Song", "Query Artist", "Query Album", Some(180_000)),
            metadata("Song", "Different Performer", "Other Album", Some(260_000)),
        )
        .expect("title still meets the minimum relation");

        assert!(score.total < EARLY_ACCEPT_SCORE);
        assert_eq!(score.duration_similarity, Some(0.0));
    }

    #[test]
    fn version_difference_is_light_and_compared_symmetrically() {
        let same_version = score_candidate(
            metadata("Song (Live)", "Artist", "", Some(180_000)),
            metadata("Song - Concert", "Artist", "", Some(180_000)),
        )
        .expect("synonymous live version");
        let repeated_synonyms = score_candidate(
            metadata("Song (Live)", "Artist", "", Some(180_000)),
            metadata("Song - Live Concert", "Artist", "", Some(180_000)),
        )
        .expect("same version group");
        let different_version = score_candidate(
            metadata("Song", "Artist", "", Some(180_000)),
            metadata("Song - Live", "Artist", "", Some(180_000)),
        )
        .expect("related version");

        assert_eq!(same_version.version_penalty, 0.0);
        assert_eq!(repeated_synonyms.version_penalty, 0.0);
        assert_eq!(different_version.version_penalty, VERSION_GROUP_PENALTY);
        assert!(different_version.total >= EARLY_ACCEPT_SCORE);

        assert_eq!(
            version_difference_penalty("Live Forever", "Live Forever (2011 Remastered)"),
            VERSION_GROUP_PENALTY
        );
    }

    #[test]
    fn distant_duration_only_loses_its_component() {
        let score = score_candidate(
            metadata("Song", "Artist", "", Some(180_000)),
            metadata("Song", "Artist", "", Some(300_000)),
        )
        .expect("related title");

        assert_eq!(score.duration_similarity, Some(0.0));
        assert!(score.total >= EARLY_ACCEPT_SCORE);
    }

    #[test]
    fn clearly_different_title_is_rejected() {
        assert!(score_candidate(
            metadata("Blue Moon", "Artist", "", None),
            metadata("Completely Unrelated", "Artist", "", None),
        )
        .is_none());
    }

    #[test]
    fn usable_lrc_requires_timestamp_and_non_empty_body() {
        assert!(normalize_usable_lrc("plain text only".to_string()).is_none());
        assert!(normalize_usable_lrc("[ar:Artist]\n[00:01.00]".to_string()).is_none());
        assert!(normalize_usable_lrc("[00:01.00][ar:Artist]".to_string()).is_none());
        assert_eq!(
            normalize_usable_lrc("[00:01.00]Line\r\n".to_string()).as_deref(),
            Some("[00:01.00]Line\n")
        );
    }
}
