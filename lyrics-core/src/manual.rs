//! Explicit candidate search. No automatic-match fallback and no durable writes.
use crate::{
    matching::{score_metadata_alias, MatchMetadata},
    *,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum UnavailableReason {
    SourceEmpty,
    Unknown,
    NoTimeline,
    UnsupportedFormat,
    NoUsableLines,
}

pub(crate) fn unavailable(reason: UnavailableReason) -> Value {
    json!({"ok":true,"content_state":"empty","reason":reason})
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub(crate) struct Track {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub duration_ms: u64,
    #[serde(default)]
    pub score: f64,
}
impl Track {
    pub fn metadata(&self) -> MatchMetadata<'_> {
        MatchMetadata {
            title: &self.title,
            artist: &self.artist,
            album: &self.album,
            duration_ms: (self.duration_ms > 0).then_some(self.duration_ms),
        }
    }
    pub fn lyrics(&self, source: &'static str, lrc: String, translated: Option<String>) -> Value {
        json!({"ok":true,"source":source,"id":self.id,"title":self.title,"artist":self.artist,
            "album":self.album,"duration_ms":self.duration_ms,"lrc":lrc,"translated_lrc":translated})
    }
}
#[derive(Deserialize)]
pub(crate) struct Request {
    pub provider: String,
    pub operation: String,
    pub track: Track,
    #[serde(default)]
    pub language: String,
}
pub(crate) fn ranked(mut tracks: Vec<Track>, query: &Track) -> Vec<Track> {
    let mut ids = HashSet::new();
    tracks.retain(|t| !t.id.is_empty() && ids.insert(t.id.clone()));
    for track in &mut tracks {
        track.score = score_metadata_alias(query.metadata(), track.metadata()).total;
    }
    tracks.sort_by(|a, b| b.score.total_cmp(&a.score));
    tracks.truncate(50);
    tracks
}

#[no_mangle]
pub extern "system" fn Java_com_andsi_airlyrics_lyrics_providers_ManualLyricsNative_requestJson(
    mut env: JNIEnv,
    _this: JObject,
    request: JString,
    key: JString,
    lookup: jni::sys::jlong,
) -> jstring {
    let request = jstring_to_string(&mut env, request);
    let key = jstring_to_string(&mut env, key);
    let result = std::panic::catch_unwind(|| execute(&request, &key, lookup))
        .unwrap_or_else(|_| Err("NativeError".into()));
    let value = result.unwrap_or_else(|e| error_response(&request, &e));
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
fn error_response(request: &str, error: &str) -> Value {
    let kind = match error {
        "NotFound" | "NeedCredential" | "RateLimited" | "ParseError" | "NativeError"
        | "NetworkError" | "HttpError" | "RestrictedLyrics" => error,
        _ => classify_error(error),
    };
    if kind == "NotFound"
        && serde_json::from_str::<Request>(request)
            .is_ok_and(|request| request.operation == "fetch")
    {
        unavailable(UnavailableReason::Unknown)
    } else {
        json!({"ok":false,"error_type":kind})
    }
}
fn execute(raw: &str, key: &str, lookup: i64) -> Result<Value, String> {
    let request: Request = serde_json::from_str(raw).map_err(|_| "ParseError")?;
    if !matches!(request.operation.as_str(), "search" | "fetch") {
        return Err("ParseError".into());
    }
    let _guard = begin_lookup_cancellation(lookup)?;
    if request.provider == "lrclib" {
        return crate::lrclib::manual(&request, lookup);
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "NativeError")?;
    runtime.block_on(async {
        await_with_cancellation(
            async {
                tokio::time::timeout(Duration::from_secs(25), async {
                    match request.provider.as_str() {
                        "netease" => netease(&request, lookup).await,
                        "musixmatch" => crate::musixmatch::manual(&request, lookup).await,
                        "spicy" => crate::spicy::manual(&request, key).await,
                        _ => Err("ParseError".into()),
                    }
                })
                .await
                .map_err(|_| "NetworkError".to_owned())?
            },
            lookup,
        )
        .await?
    })
}
async fn netease(request: &Request, lookup: i64) -> Result<Value, String> {
    let api = NcmApi::new(false, "");
    let t = &request.track;
    if request.operation == "search" {
        let keyword = format!("{} {} {}", t.title, t.artist, t.album);
        let response = api.search_songs(keyword.trim()).await?;
        if response.code != 200 {
            return Err(netease_api_code_error("search", response.code));
        }
        let tracks = response
            .result
            .map(|r| r.songs)
            .unwrap_or_default()
            .into_iter()
            .map(|s| Track {
                id: s.id.to_string(),
                title: s.name,
                artist: s
                    .artists
                    .iter()
                    .filter_map(|a| a.name.as_deref())
                    .collect::<Vec<_>>()
                    .join(", "),
                album: s.album.name.unwrap_or_default(),
                duration_ms: s.duration as u64,
                score: 0.0,
            })
            .collect();
        Ok(json!({"ok":true,"candidates":ranked(tracks,t)}))
    } else {
        let id = t.id.parse().map_err(|_| "ParseError")?;
        check_lookup_cancelled(lookup)?;
        let response = await_with_cancellation(api.fetch_lyrics(id), lookup).await??;
        check_lookup_cancelled(lookup)?;
        if response.code == 404 {
            return Ok(unavailable(UnavailableReason::Unknown));
        }
        if response.code != 200 {
            return Err(netease_api_code_error("lyric", response.code));
        }
        Ok(netease_content(t, response))
    }
}

fn netease_content(track: &Track, response: LyricResp) -> Value {
    let has_text = response
        .lrc
        .as_ref()
        .is_some_and(|v| !v.lyric.trim().is_empty())
        || response
            .tlyric
            .as_ref()
            .is_some_and(|v| !v.lyric.trim().is_empty());
    match candidate_lyrics(response) {
        Some(lyrics) => track.lyrics(
            "netease-rust",
            lyrics.lrc.unwrap_or(lyrics.merged_lrc),
            lyrics.translated_lrc,
        ),
        None => unavailable(if has_text {
            UnavailableReason::NoTimeline
        } else {
            UnavailableReason::SourceEmpty
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn track(id: &str, title: &str) -> Track {
        Track {
            id: id.into(),
            title: title.into(),
            artist: "Artist".into(),
            album: "".into(),
            duration_ms: 200000,
            score: 0.0,
        }
    }
    #[test]
    fn netease_empty_and_unusable_timing_are_distinct() {
        for (body, reason) in [
            ("", "source_empty"),
            ("untimed", "no_timeline"),
            ("[00:01.00]", "no_timeline"),
        ] {
            let response = LyricResp {
                code: 200,
                lrc: Some(ncmapi::types::Lyric {
                    version: 1,
                    lyric: body.into(),
                }),
                ..LyricResp::default()
            };
            assert_eq!(
                netease_content(&track("1", "Song"), response)["reason"],
                reason
            );
        }
        let response = LyricResp {
            code: 200,
            lrc: Some(ncmapi::types::Lyric {
                version: 1,
                lyric: "[00:01.00]Hello".into(),
            }),
            ..LyricResp::default()
        };
        assert!(netease_content(&track("1", "Song"), response)["lrc"]
            .as_str()
            .unwrap()
            .contains("Hello"));
    }
    #[test]
    fn only_missing_fetch_content_is_an_empty_preview() {
        let request = |operation| {
            json!({
                "provider":"netease", "operation":operation,
                "track":{"id":"42","title":"Song","artist":"","album":"","duration_ms":0}
            })
            .to_string()
        };
        assert_eq!(
            error_response(&request("fetch"), "NotFound")["reason"],
            "unknown"
        );
        assert_eq!(error_response(&request("search"), "NotFound")["ok"], false);
        for error in [
            "NetworkError",
            "NeedCredential",
            "RateLimited",
            "ParseError",
            "RestrictedLyrics",
        ] {
            let response = error_response(&request("fetch"), error);
            assert_eq!(response["ok"], false);
            assert_eq!(response["error_type"], error);
        }
    }

    #[test]
    fn manual_ranking_keeps_low_confidence_candidates_and_deduplicates_ids() {
        let result = ranked(
            vec![
                track("2", "Unrelated"),
                track("1", "Song"),
                track("1", "Song"),
            ],
            &track("", "Song"),
        );
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].id, "1");
        assert_eq!(result[1].id, "2");
    }
    #[test]
    fn unknown_operation_and_provider_are_rejected() {
        let raw =
            json!({"provider":"unknown","operation":"fetch","track":track("1","Song")}).to_string();
        assert!(execute(&raw, "", 0).is_err());
    }
}
