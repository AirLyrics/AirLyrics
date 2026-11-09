//! Manual QQ catalogue and exact song-ID lyric lookup; never participates in automatic matching.
//! Protocol checked against LDDC v0.9.2 qm.py and Lyrics-Helper 39e5883 qq_music/api.rs.
//! The existing manual catalogue owns ranking, query fallback, cancellation and persistence.
use crate::manual::Track;
use crate::manual_words::{line_version, lrc, response, version, MAX_BYTES};
use base64::{engine::general_purpose::STANDARD, Engine};
use quick_xml::{events::Event, Reader};
use regex::Regex;
use serde_json::{json, Value};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const URL: &str = "https://u.y.qq.com/cgi-bin/musicu.fcg";
type Session = Option<(Instant, Value)>;
static SESSION: LazyLock<Mutex<Session>> = LazyLock::new(|| Mutex::new(None));

fn code(value: &Value) -> Option<i64> {
    value.as_i64().or_else(|| value.as_str()?.parse().ok())
}
fn api_data(value: Value) -> Result<Value, String> {
    for status in [&value["code"], &value["request"]["code"]] {
        match code(status) {
            Some(0) => {}
            Some(2001) => return Err("RateLimited".into()),
            Some(_) => return Err("HttpError".into()),
            None => return Err("ParseError".into()),
        }
    }
    value["request"]["data"].as_object().ok_or("ParseError")?;
    Ok(value["request"]["data"].clone())
}
fn anonymous_comm() -> Value {
    json!({"ct":11,"cv":"1003006","v":"1003006","os_ver":"15","phonetype":"24122RKC7C",
        "rom":"Redmi/miro/miro:15/AE3A.240806.005/OS2.0.105.0.VOMCNXM:user/release-keys",
        "tmeAppID":"qqmusiclight","nettype":"NETWORK_WIFI","udid":"0","uid":"0"})
}
async fn request(
    client: &reqwest::Client,
    comm: &Value,
    method: &str,
    module: &str,
    param: Value,
) -> Result<Value, String> {
    api_data(
        response(
            client
                .post(URL)
                .header("User-Agent", "okhttp/3.14.9")
                .header("Cookie", "tmeLoginType=-1;")
                .header("Referer", "https://y.qq.com/")
                .json(
                    &json!({"comm":comm,"request":{"method":method,"module":module,"param":param}}),
                ),
        )
        .await?,
    )
}
async fn comm(client: &reqwest::Client) -> Result<Value, String> {
    if let Some((_, cached)) = SESSION
        .lock()
        .map_err(|_| "NativeError")?
        .as_ref()
        .filter(|(created, _)| created.elapsed() < Duration::from_secs(3600))
    {
        return Ok(cached.clone());
    }
    // Session bootstrap is optional and bounded. Do not cache transient failures forever.
    let mut comm = anonymous_comm();
    match tokio::time::timeout(
        Duration::from_secs(3),
        request(
            client,
            &comm,
            "GetSession",
            "music.getSession.session",
            json!({"caller":0,"uid":"0","vkey":0}),
        ),
    )
    .await
    {
        Ok(Ok(data)) => {
            if let Some(session) = data["session"].as_object() {
                for key in ["uid", "sid", "userip"] {
                    if let Some(value) = session.get(key).filter(|v| v.is_string() || v.is_number())
                    {
                        comm[key] = value.clone();
                    }
                }
                *SESSION.lock().map_err(|_| "NativeError")? = Some((Instant::now(), comm.clone()));
            }
        }
        Ok(Err(error)) if error == "RateLimited" => return Err(error),
        _ => {}
    }
    Ok(comm)
}
fn search_params(term: &str, page: u32) -> Value {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    // Same search-ID bit fields as the mobile protocol, without a new randomness dependency.
    let search_id = (millis % 20 + 1) * 18_014_398_509_481_984u128
        + (millis % 4_194_305) * 4_294_967_296u128
        + millis % 86_400_000;
    json!({"search_id":search_id.to_string(),"remoteplace":"search.android.keyboard",
        "query":term,"search_type":0,"num_per_page":20,"page_num":page,
        "highlight":0,"nqc_flag":0,"page_id":1,"grp":1})
}
pub(crate) async fn catalog(
    client: &reqwest::Client,
    term: &str,
    page: u32,
) -> Result<(Vec<Track>, bool), String> {
    let comm = comm(client).await?;
    let params = search_params(term, page);
    let data = match request(
        client,
        &comm,
        "DoSearchForQQMusicMobile",
        "music.search.SearchCgiService",
        params.clone(),
    )
    .await
    {
        Ok(data) => data,
        // A compatibility fallback, never a long rate-limit sleep or retry loop.
        Err(error) if error == "HttpError" || error == "ParseError" => {
            request(
                client,
                &comm,
                "DoSearchForQQMusicLite",
                "music.search.SearchCgiService",
                params,
            )
            .await?
        }
        Err(error) => return Err(error),
    };
    catalog_data(&data, page)
}
fn catalog_data(data: &Value, page: u32) -> Result<(Vec<Track>, bool), String> {
    let songs = data
        .pointer("/body/item_song")
        .and_then(Value::as_array)
        .ok_or("ParseError")?;
    let tracks: Vec<_> = songs
        .iter()
        .filter_map(|song| {
            let id = song["id"].as_u64().filter(|id| *id > 0)?;
            let title = song["title"].as_str()?.trim();
            if title.is_empty() {
                return None;
            }
            let subtitle = song["subtitle"].as_str().unwrap_or_default().trim();
            Some(Track {
                id: id.to_string(),
                title: if !subtitle.is_empty() && !title.contains(subtitle) {
                    format!("{title} ({subtitle})")
                } else {
                    title.into()
                },
                artist: song["singer"]
                    .as_array()?
                    .iter()
                    .filter_map(|s| s["name"].as_str())
                    .filter(|s| !s.trim().is_empty())
                    .collect::<Vec<_>>()
                    .join(", "),
                album: song
                    .pointer("/album/name")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .into(),
                duration_ms: song["interval"].as_u64().unwrap_or(0).saturating_mul(1000),
                score: 0.0,
            })
        })
        .collect();
    if !songs.is_empty() && tracks.is_empty() {
        return Err("ParseError".into());
    }
    let more = songs.len() == 20
        && data
            .pointer("/meta/sum")
            .and_then(Value::as_u64)
            .map(|total| u64::from(page).saturating_mul(20) < total)
            .unwrap_or(true);
    Ok((tracks, more))
}
fn lyric_params(track: &Track) -> Result<Value, String> {
    let id = track
        .id
        .parse::<u64>()
        .ok()
        .filter(|id| *id > 0)
        .ok_or("ParseError")?;
    Ok(json!({"songID":id,"songName":STANDARD.encode(&track.title),
        "singerName":STANDARD.encode(&track.artist),"albumName":STANDARD.encode(&track.album),
        "interval":track.duration_ms/1000,"crypt":1,"ct":19,"cv":2111,
        "lrc_t":0,"qrc_t":0,"trans_t":0,"roma_t":0,"qrc":1,"trans":1,"roma":0,"type":0}))
}
pub(crate) async fn options(client: &reqwest::Client, track: &Track) -> Result<Value, String> {
    let params = lyric_params(track)?;
    let data = request(
        client,
        &comm(client).await?,
        "GetPlayLyricInfo",
        "music.musichallSong.PlayLyricInfo",
        params,
    )
    .await?;
    lyric_versions(&data, track)
}
fn decoded_field(data: &Value, key: &str, flags: &[&str]) -> Result<Option<String>, String> {
    let encrypted = data[key].as_str().ok_or("ParseError")?;
    if encrypted.trim().is_empty() {
        return Ok(None);
    }
    let present: Vec<_> = flags.iter().filter_map(|key| code(&data[*key])).collect();
    if present.is_empty() {
        return Err("ParseError".into());
    }
    if present.iter().all(|v| *v == 0) {
        return Ok(None);
    }
    extract_content(&crate::qrc_crypto::decrypt(encrypted, MAX_BYTES)?).map(Some)
}
fn lyric_versions(data: &Value, track: &Track) -> Result<Value, String> {
    let Some(raw) = decoded_field(data, "lyric", &["qrc_t", "lrc_t"])? else {
        return Ok(json!({"ok":true,"versions":[]}));
    };
    let (translation, error) = if data.get("trans").is_none() || data["trans"].as_str() == Some("")
    {
        (None, None)
    } else {
        match decoded_field(data, "trans", &["trans_t"])
            .and_then(|raw| raw.map(|raw| timed_text(&raw)).transpose())
        {
            Ok(text) => (text.flatten(), None),
            Err(error) => (None, Some(error)),
        }
    };
    let lines = parse_qrc(&raw)?;
    let versions = if lines.is_empty() {
        timed_text(&raw)?
            .and_then(crate::matching::normalize_usable_lrc)
            .map(|text| {
                vec![line_version(
                    track,
                    "qq",
                    &track.id,
                    text,
                    translation,
                    false,
                )]
            })
            .unwrap_or_default()
    } else {
        vec![version(track, "qq", &track.id, lines, translation, false)]
    };
    Ok(json!({"ok":true,"versions":versions,"partial_error":error}))
}
/// Read attributes without XML whitespace normalization: real QRC keeps newlines inside LyricContent.
fn extract_content(raw: &str) -> Result<String, String> {
    let mut text = raw.trim_start_matches('\u{feff}').trim().to_owned();
    for _ in 0..3 {
        if !text.starts_with('<') {
            return Ok(text);
        }
        let mut reader = Reader::from_str(&text);
        let mut content = None;
        loop {
            match reader.read_event().map_err(|_| "ParseError")? {
                Event::Start(element) | Event::Empty(element)
                    if element.name().as_ref() == b"Lyric_1" =>
                {
                    for attr in element.attributes() {
                        let attr = attr.map_err(|_| "ParseError")?;
                        if attr.key.as_ref() == b"LyricContent" {
                            let value = std::str::from_utf8(attr.value.as_ref())
                                .map_err(|_| "ParseError")?;
                            content = Some(
                                quick_xml::escape::unescape(value)
                                    .map_err(|_| "ParseError")?
                                    .into_owned(),
                            );
                        }
                    }
                }
                Event::DocType(_) => return Err("ParseError".into()),
                Event::Eof => break,
                _ => {}
            }
        }
        text = content.ok_or("ParseError")?;
    }
    Err("ParseError".into())
}
fn timed_text(raw: &str) -> Result<Option<String>, String> {
    let lines = parse_qrc(raw)?;
    if !lines.is_empty() {
        return Ok(Some(lrc(&lines)));
    }
    let line_re = Regex::new(r"(?m)^\[(\d+),(\d+)\](.*)\r?$").unwrap();
    let rows: Vec<_> = line_re.captures_iter(raw).map(|line| {
        Ok(json!({"startMs":line[1].parse::<u64>().map_err(|_|"ParseError")?,"text":line[3].trim_end_matches('\r')}))
    }).collect::<Result<_,String>>()?;
    if !rows.is_empty() {
        return Ok(Some(lrc(&rows)));
    }
    Ok(crate::matching::normalize_usable_lrc(raw.into()))
}
fn parse_qrc(raw: &str) -> Result<Vec<Value>, String> {
    let line_re = Regex::new(r"^\[(\d+),(\d+)\](.*)$").unwrap();
    let word_re = Regex::new(r"\((\d+),(\d+)\)").unwrap();
    // A line-only response is valid, but never gets a word label from a provider flag.
    if !raw
        .lines()
        .any(|line| line_re.is_match(line) && word_re.is_match(line))
    {
        return Ok(Vec::new());
    }
    let mut lines = Vec::new();
    for raw_line in raw.lines() {
        let Some(line) = line_re.captures(raw_line.trim_end_matches('\r')) else {
            continue;
        };
        let number = |s: &str| s.parse::<u64>().map_err(|_| "ParseError".to_owned());
        let start = number(&line[1])?;
        let mut end = start.checked_add(number(&line[2])?).ok_or("ParseError")?;
        let body = &line[3];
        if body.trim().is_empty() {
            continue;
        }
        let mut tokens: Vec<Value> = Vec::new();
        let mut consumed = 0;
        let mut previous_start = start;
        let mut prefix = String::new();
        for word in word_re.captures_iter(body) {
            let tag = word.get(0).unwrap();
            let text = &body[consumed..tag.start()];
            consumed = tag.end();
            let a = number(&word[1])?;
            let b = a.checked_add(number(&word[2])?).ok_or("ParseError")?;
            if a < start || a < previous_start || b > end.saturating_add(2) {
                return Err("ParseError".into());
            }
            previous_start = a;
            end = end.max(b);
            if text.is_empty() {
                continue;
            }
            if b == a || text.trim().is_empty() {
                if let Some(last) = tokens.last_mut() {
                    last["text"] = json!(format!("{}{text}", last["text"].as_str().unwrap()));
                } else {
                    prefix.push_str(text);
                }
            } else {
                tokens.push(json!({"text":format!("{prefix}{text}"),"startMs":a,"endMs":b}));
                prefix.clear();
            }
        }
        // Zero-length credit lines have no highlight interval; do not reject the song.
        if tokens.is_empty() && end == start {
            continue;
        }
        if tokens.is_empty() {
            return Err("ParseError".into());
        }
        if let Some(last) = tokens.last_mut() {
            last["text"] = json!(format!(
                "{}{}{}",
                last["text"].as_str().unwrap(),
                prefix,
                &body[consumed..]
            ));
        }
        let text = tokens
            .iter()
            .filter_map(|word| word["text"].as_str())
            .collect::<String>();
        if !text.trim().is_empty() {
            lines.push(json!({"startMs":start,"endMs":end,"text":text,"tokens":tokens}));
        }
    }
    if lines.is_empty() {
        return Err("ParseError".into());
    }
    lines.sort_by_key(|line| line["startMs"].as_u64().unwrap());
    Ok(lines)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn track() -> Track {
        Track {
            id: "123".into(),
            title: "Song (Live)".into(),
            artist: "Artist".into(),
            album: "Album".into(),
            duration_ms: 201000,
            score: 0.0,
        }
    }
    #[test]
    fn catalogue_pagination_and_recording_versions_do_not_download_or_filter() {
        let params = search_params("Edited query", 3);
        assert_eq!(params["page_num"], 3);
        assert_eq!(params["num_per_page"], 20);
        assert_eq!(params["query"], "Edited query");
        let songs: Vec<_> = (1..=20).map(|id| json!({"id":id,"mid":"unused","title":"Song",
            "subtitle":if id==20 {"Live"} else {""},"singer":[{"name":"Artist"}],"album":{"name":"Album"},"interval":201})).collect();
        let data = json!({"body":{"item_song":songs},"meta":{"sum":41}});
        let (rows, more) = catalog_data(&data, 2).unwrap();
        assert_eq!(rows.len(), 20);
        assert!(more);
        assert_eq!(rows[19].title, "Song (Live)");
        assert_eq!(rows[19].id, "20");
        assert_eq!(rows[19].duration_ms, 201000);
        assert!(!catalog_data(&data, 3).unwrap().1);
        assert!(catalog_data(&json!({}), 1).is_err());
    }
    #[test]
    fn selected_numeric_id_and_metadata_are_used_without_a_new_search() {
        let params = lyric_params(&track()).unwrap();
        assert_eq!(params["songID"], 123);
        assert_eq!(params["interval"], 201);
        assert_eq!(params["songName"], STANDARD.encode("Song (Live)"));
        assert_eq!(params["singerName"], STANDARD.encode("Artist"));
        assert_eq!(params["qrc"], 1);
        assert_eq!(params["crypt"], 1);
        assert!(lyric_params(&Track {
            id: "other:mid".into(),
            ..track()
        })
        .is_err());
    }
    #[test]
    fn qrc_postfix_times_preserve_parentheses_and_the_line_end() {
        let lines = parse_qrc("[1000,3000]((1000,200)Come (1200,300)on)(1500,500) tail").unwrap();
        assert_eq!(lines[0]["text"], "(Come on) tail");
        assert_eq!(lines[0]["endMs"], 4000);
        assert_eq!(lines[0]["tokens"][0]["text"], "(");
        assert_eq!(lines[0]["tokens"][2]["endMs"], 2000);
        assert_eq!(lrc(&lines), "[00:01.000](Come on) tail");
        let lines = parse_qrc("[1000,1000]「(1000,0)你(1000,500)好(1500,501)」(2001,0)").unwrap();
        assert_eq!(lines[0]["text"], "「你好」");
        assert_eq!(lines[0]["endMs"], 2001);
        assert_eq!(lines[0]["tokens"].as_array().unwrap().len(), 2);
        let lines = parse_qrc("[0,0]Credits(0,0)\n[1000,1000]你(1000,400)(1400,0)好(1400,600)").unwrap();
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0]["text"], "你好");
    }
    #[test]
    fn xml_keeps_newlines_and_decodes_entities_without_accepting_dtd() {
        let raw = "<?xml version=\"1.0\"?><QrcInfos><LyricInfo><Lyric_1 LyricContent=\"[1000,500]你(1000,500)\n[2000,500]A&amp;B(2000,500)\"/></LyricInfo></QrcInfos>";
        let content = extract_content(raw).unwrap();
        let lines = parse_qrc(&content).unwrap();
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[1]["text"], "A&B");
        assert!(extract_content("<!DOCTYPE x><QrcInfos/>").is_err());
        assert!(extract_content("<QrcInfos/>").is_err());
        assert!(extract_content("<QrcInfos>").is_err());
    }
    #[test]
    fn encrypted_response_keeps_only_its_own_translation_and_survives_translation_failure() {
        let encrypted = crate::qrc_crypto::encrypt_fixture(
            "<?xml version=\"1.0\"?><QrcInfos><LyricInfo><Lyric_1 LyricContent=\"[1000,1000]你(1000,400)好(1400,600)\"/></LyricInfo></QrcInfos>");
        let translated = crate::qrc_crypto::encrypt_fixture("[00:01.000]Hello");
        let data = json!({"lyric":encrypted,"qrc_t":1,"lrc_t":0,"trans":translated,"trans_t":1});
        let result = lyric_versions(&data, &track()).unwrap();
        let choice = &result["versions"][0];
        assert_eq!(choice["provider"], "qq");
        assert_eq!(choice["id"], "123");
        assert_eq!(choice["track"]["id"], "123");
        assert_eq!(choice["lines"][0]["tokens"][1]["endMs"], 2000);
        assert_eq!(choice["translated_lrc"], "[00:01.000]Hello");
        let mut broken = data.clone();
        broken["trans"] = json!("corrupt");
        let result = lyric_versions(&broken, &track()).unwrap();
        assert_eq!(result["versions"].as_array().unwrap().len(), 1);
        assert!(result["versions"][0]["translated_lrc"].is_null());
        assert_eq!(result["partial_error"], "ParseError");
        let line = json!({"lyric":crate::qrc_crypto::encrypt_fixture("[00:01.000]Line"),
            "qrc_t":0,"lrc_t":1});
        let result = lyric_versions(&line, &track()).unwrap();
        assert_eq!(result["versions"][0]["kind"], "line");
        assert!(result["versions"][0]["lines"]
            .as_array()
            .unwrap()
            .is_empty());
        let plain =
            json!({"lyric":crate::qrc_crypto::encrypt_fixture("Plain lyrics"),"qrc_t":0,"lrc_t":1});
        assert!(lyric_versions(&plain, &track()).unwrap()["versions"]
            .as_array()
            .unwrap()
            .is_empty());
    }
    #[test]
    fn line_only_empty_and_failures_are_distinct() {
        assert!(parse_qrc("[00:01.000]Line").unwrap().is_empty());
        assert_eq!(
            timed_text("[1000,500]Line").unwrap().as_deref(),
            Some("[00:01.000]Line")
        );
        assert_eq!(timed_text("Plain lyrics").unwrap(), None);
        assert!(parse_qrc("[00:01.000]Literal (1000,20)")
            .unwrap()
            .is_empty());
        assert!(
            lyric_versions(&json!({"lyric":"","qrc_t":0,"lrc_t":0}), &track()).unwrap()["versions"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        assert!(lyric_versions(&json!({"lyric":"broken","qrc_t":1}), &track()).is_err());
        assert!(lyric_versions(&json!({}), &track()).is_err());
        for raw in [
            "[1000,1000]Bad(999,10)",
            "[1000,1000]Bad(1000,1003)",
            "[1000,1000]Bad(1000,0)",
        ] {
            assert!(parse_qrc(raw).is_err());
        }
        assert_eq!(
            api_data(json!({"code":0,"request":{"code":2001}})).unwrap_err(),
            "RateLimited"
        );
        assert!(api_data(json!({"code":0,"request":{"code":1,"data":{}}})).is_err());
    }
}
