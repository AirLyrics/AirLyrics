//! Optional Apple storefront metadata aliases. Does not change provider matching policy.
use crate::matching::{score_metadata_alias, MatchMetadata};
use reqwest::Client;
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};

static CACHE: OnceLock<Mutex<HashMap<String, (Instant, Value)>>> = OnceLock::new();

pub(crate) fn resolve(title: &str, artist: &str, album: &str, duration: u64, lookup: i64) -> Value {
    let result = (|| -> Result<Value, String> {
        let _guard = crate::begin_lookup_cancellation(lookup)?;
        let key = json!([title, artist, album, duration]).to_string();
        let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
        if let Some((_, value)) = cache
            .lock()
            .map_err(|_| "cache_error")?
            .get(&key)
            .filter(|(at, _)| at.elapsed() < Duration::from_secs(86400))
        {
            return Ok(value.clone());
        }
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| "native_error")?;
        let value = runtime.block_on(crate::await_with_cancellation(
            async {
                tokio::time::timeout(
                    Duration::from_secs(12),
                    discover(title, artist, album, duration),
                )
                .await
                .map_err(|_| "timeout".to_owned())?
            },
            lookup,
        ))??;
        if value.get("title").is_some() {
            let mut entries = cache.lock().map_err(|_| "cache_error")?;
            entries.retain(|_, (at, _)| at.elapsed() < Duration::from_secs(86400));
            if entries.len() >= 256 {
                entries.clear();
            }
            entries.insert(key, (Instant::now(), value.clone()));
        }
        Ok(value)
    })();
    result.unwrap_or_else(|reason| json!({"reason": reason}))
}

fn region(title: &str, artist: &str) -> Option<&'static str> {
    let text = format!("{title} {artist}");
    if text
        .chars()
        .any(|c| matches!(c as u32, 0x3040..=0x30ff | 0xff66..=0xff9f))
    {
        Some("JP")
    } else if text
        .chars()
        .any(|c| matches!(c as u32, 0xac00..=0xd7af | 0x1100..=0x11ff | 0x3130..=0x318f))
    {
        Some("KR")
    } else if text
        .chars()
        .any(|c| matches!(c as u32, 0x3400..=0x9fff | 0x20000..=0x2fa1f))
    {
        Some("CN")
    } else {
        None
    }
}

async fn search(client: &Client, term: &str, country: &str) -> Result<Value, reqwest::Error> {
    client
        .get("https://itunes.apple.com/search")
        .query(&[
            ("term", term),
            ("country", country),
            ("media", "music"),
            ("entity", "song"),
            ("limit", "25"),
            ("lang", if country == "JP" { "ja_jp" } else { "en_us" }),
        ])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await
}

fn song(value: &Value) -> bool {
    value["kind"].as_str() == Some("song")
        && value["trackName"]
            .as_str()
            .is_some_and(|s| !s.trim().is_empty())
        && value["trackId"].as_u64().is_some()
}

fn best(response: &Value, query: MatchMetadata<'_>) -> Option<Value> {
    let mut best: Option<(f64, &Value)> = None;
    for candidate in response["results"].as_array()?.iter().filter(|v| song(v)) {
        let score = score_metadata_alias(
            query,
            MatchMetadata {
                title: candidate["trackName"].as_str().unwrap_or_default(),
                artist: candidate["artistName"].as_str().unwrap_or_default(),
                album: candidate["collectionName"].as_str().unwrap_or_default(),
                duration_ms: candidate["trackTimeMillis"].as_u64(),
            },
        )
        .total;
        if best.as_ref().is_none_or(|(current, _)| score > *current) {
            best = Some((score, candidate));
        }
    }
    best.map(|(_, candidate)| candidate.clone())
}

async fn discover(title: &str, artist: &str, album: &str, duration: u64) -> Result<Value, String> {
    let client = Client::builder()
        .timeout(Duration::from_secs(5))
        .connect_timeout(Duration::from_secs(3))
        .build()
        .map_err(|_| "network_error")?;
    let term = format!("{title} {artist}");
    let query = MatchMetadata {
        title,
        artist,
        album,
        duration_ms: Some(duration),
    };
    let local_region = region(title, artist);
    let (us, local) = tokio::join!(search(&client, &term, "US"), async {
        match local_region {
            Some(country) => search(&client, &term, country).await.ok(),
            None => None,
        }
    });
    let mut selected = None;
    if let Some(candidate) = local.as_ref().and_then(|v| best(v, query)) {
        let id = candidate["trackId"].as_u64().unwrap();
        let response = client
            .get("https://itunes.apple.com/lookup")
            .query(&[
                ("id", id.to_string()),
                ("country", "US".to_owned()),
                ("entity", "song".to_owned()),
                ("lang", "en_us".to_owned()),
            ])
            .send()
            .await;
        if let Ok(response) = response {
            if let Ok(response) = response.error_for_status() {
                if let Ok(value) = response.json::<Value>().await {
                    selected = value["results"]
                        .as_array()
                        .and_then(|rows| {
                            rows.iter()
                                .find(|v| song(v) && v["trackId"].as_u64() == Some(id))
                        })
                        .cloned();
                }
            }
        }
    }
    let method = if selected.is_some() {
        "storefront_lookup"
    } else {
        "us_search"
    };
    if selected.is_none() {
        selected = us.as_ref().ok().and_then(|v| best(v, query));
    }
    Ok(match selected {
        Some(value) => json!({
            "title": value["trackName"], "artist": value["artistName"],
            "album": value["collectionName"], "appleId": value["trackId"], "method": method,
        }),
        None => json!({"reason": if us.is_err() { "network_error" } else { "no_title" }}),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn japanese_aria_prefers_nanatsukaze_catalogue_recording() {
        let results = json!({"results": [
            {"kind":"song", "trackId":1, "trackName":"アリア", "artistName":"Other artist",
                "collectionName":"アリア - Single", "trackTimeMillis":226000},
            {"kind":"song", "trackId":1819934506u64, "trackName":"アリア", "artistName":"ナナツカゼ",
                "collectionName":"アリア - Single", "trackTimeMillis":226000},
        ]});
        let query = MatchMetadata {
            title: "アリア",
            artist: "ナナツカゼ",
            album: "アリア",
            duration_ms: Some(226000),
        };
        assert_eq!(best(&results, query).unwrap()["trackId"], 1819934506u64);
    }
}
