use std::collections::BTreeMap;

pub(crate) fn parse_lrc_lines(lrc: &str) -> BTreeMap<u64, String> {
    parse_lrc_entries(lrc).into_iter().collect()
}

pub(crate) fn parse_lrc_entries(lrc: &str) -> Vec<(u64, String)> {
    let mut entries = Vec::new();
    for raw in lrc.lines() {
        let times = extract_time_tags(raw);
        if times.is_empty() {
            continue;
        }

        let text = strip_time_tags(raw).trim().to_string();
        if text.is_empty() {
            continue;
        }

        for time in times {
            entries.push((time, text.clone()));
        }
    }
    entries.sort_by_key(|(time, _)| *time);
    entries
}

pub(crate) fn format_lrc_time(time_ms: u64) -> String {
    let minutes = time_ms / 60_000;
    let seconds = (time_ms % 60_000) / 1_000;
    let millis = time_ms % 1_000;
    format!("{:02}:{:02}.{:03}", minutes, seconds, millis)
}

fn extract_time_tags(line: &str) -> Vec<u64> {
    let mut times = Vec::new();
    let bytes = line.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'[' {
            if let Some(end_rel) = line[i..].find(']') {
                let end = i + end_rel;
                if let Some(time) = parse_time_tag(&line[i + 1..end]) {
                    times.push(time);
                }
                i = end + 1;
                continue;
            }
        }
        i += 1;
    }
    times
}

fn strip_time_tags(line: &str) -> String {
    let mut output = String::new();
    let bytes = line.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'[' {
            if let Some(end_rel) = line[i..].find(']') {
                let end = i + end_rel;
                if parse_time_tag(&line[i + 1..end]).is_some() {
                    i = end + 1;
                    continue;
                }
            }
        }
        if let Some(ch) = line[i..].chars().next() {
            output.push(ch);
            i += ch.len_utf8();
        } else {
            break;
        }
    }
    output
}

fn parse_time_tag(value: &str) -> Option<u64> {
    let mut parts = value.split(':');
    let minutes = parts.next()?.parse::<u64>().ok()?;
    let seconds_part = parts.next()?;
    if parts.next().is_some() {
        return None;
    }

    let mut second_parts = seconds_part.split('.');
    let seconds = second_parts.next()?.parse::<u64>().ok()?;
    let fraction = second_parts.next().unwrap_or("0");
    if second_parts.next().is_some() || seconds >= 60 {
        return None;
    }

    let millis = match fraction.len() {
        0 => 0,
        1 => fraction.parse::<u64>().ok()? * 100,
        2 => fraction.parse::<u64>().ok()? * 10,
        _ => fraction
            .chars()
            .take(3)
            .collect::<String>()
            .parse::<u64>()
            .ok()?,
    };

    Some(minutes * 60_000 + seconds * 1_000 + millis)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_lrc_entries_supports_multiple_timestamps_and_sorts() {
        let entries = parse_lrc_entries("[00:02.50]second\n[00:01.00][00:03.00]shared");

        assert_eq!(
            entries,
            vec![
                (1_000, "shared".to_string()),
                (2_500, "second".to_string()),
                (3_000, "shared".to_string()),
            ]
        );
    }

    #[test]
    fn parse_lrc_entries_ignores_metadata_and_invalid_seconds() {
        let entries = parse_lrc_entries("[ar:Artist]\n[00:60.00]bad\n[01:00.00]good");

        assert_eq!(entries, vec![(60_000, "good".to_string())]);
    }

    #[test]
    fn parse_lrc_entries_supports_millisecond_fraction_widths() {
        let entries = parse_lrc_entries("[00:01.1]a\n[00:02.12]b\n[00:03.1234]c");

        assert_eq!(
            entries,
            vec![
                (1_100, "a".to_string()),
                (2_120, "b".to_string()),
                (3_123, "c".to_string()),
            ]
        );
    }

    #[test]
    fn format_lrc_time_uses_three_digit_milliseconds() {
        assert_eq!(format_lrc_time(62_345), "01:02.345");
    }

    #[test]
    fn parse_lrc_lines_keeps_latest_text_for_duplicate_timestamps() {
        let lines = parse_lrc_lines("[00:01.00]first\n[00:01.00]second");

        assert_eq!(lines.get(&1_000), Some(&"second".to_string()));
    }
}
