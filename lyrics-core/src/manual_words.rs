//! On-demand word versions for one manually selected recording. No automatic lookup or writes.
//! Protocol references: Lyrics-Helper (Apache-2.0), netease/api.rs, netease/eapi.rs,
//! and krc/decrypter.rs. Implemented against AirLyrics' reqwest and cancellation boundary.
use crate::{manual::Track, *};
use base64::{engine::general_purpose::STANDARD, Engine};
use regex::Regex;
use serde_json::{json, Value};
use std::io::Read;
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_BYTES: usize = 2 * 1024 * 1024;
const KRC_KEY: &[u8; 16] = b"@Gaw^2tGQ61-\xce\xd2ni";

fn parse_error<T>() -> Result<T, String> { Err("ParseError".into()) }
fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder().timeout(Duration::from_secs(10))
        .user_agent("Mozilla/5.0").build().map_err(|_| "NetworkError".into())
}
async fn response(request: reqwest::RequestBuilder) -> Result<Value, String> {
    let mut r = request.send().await.map_err(|_| "NetworkError")?;
    if r.status().as_u16() == 429 { return Err("RateLimited".into()); }
    if !r.status().is_success() { return Err("HttpError".into()); }
    let mut bytes = Vec::new();
    while let Some(chunk) = r.chunk().await.map_err(|_| "NetworkError")? {
        if bytes.len() + chunk.len() > MAX_BYTES { return parse_error(); }
        bytes.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&bytes).map_err(|_| "ParseError".into())
}
fn success(versions: Vec<Value>) -> Value { json!({"ok":true,"versions":versions}) }

/// Catalogue search never downloads lyrics or removes low-scoring recordings.
pub(crate) async fn catalog(request: &crate::manual::Request, lookup: i64) -> Result<Value, String> {
    let client = client()?;
    let query = &request.track;
    if query.title.trim().is_empty() { return parse_error(); }
    let page = request.page.max(1);
    let term = if request.keyword.is_empty() {
        [query.title.trim(), query.artist.trim()].into_iter().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" ")
    } else { request.keyword.clone() };
    check_lookup_cancelled(lookup)?;
    let (mut tracks, mut more) = catalog_page(&client, &request.provider, &term, page).await?;
    let mut keyword = term;
    let mut partial = None;
    // A broader query is a search fallback, never a change to the user's matching evidence.
    if page == 1 && request.keyword.is_empty() && !tracks.iter().any(|t| related(query,t)) {
        let mut terms=Vec::new();
        for plan in crate::matching::build_search_queries(&query.title,&query.artist) {
            let term=[Some(plan.title.as_str()),plan.artist.as_deref()].into_iter().flatten()
                .filter(|s|!s.is_empty()).collect::<Vec<_>>().join(" ");
            if term!=keyword && !terms.contains(&term) { terms.push(term); }
        }
        // Bound extra network work, but never truncate the catalogue or lyric options.
        for term in terms.into_iter().take(2) {
            check_lookup_cancelled(lookup)?;
            match catalog_page(&client,&request.provider,&term,1).await {
                Ok((extra,has_more)) => {
                    tracks.extend(extra); more=has_more; keyword=term;
                    if tracks.iter().any(|t|related(query,t)) { break; }
                },
                Err(e) => { partial=Some(e); break; }
            }
        }
    }
    let mut ids = HashSet::new();
    tracks.retain(|t| ids.insert(t.id.clone()));
    let mut rows: Vec<_> = tracks.into_iter().map(|mut track| {
        let score = crate::matching::score_metadata_alias(query.metadata(), track.metadata());
        let related = related(query, &track);
        track.score = score.total;
        let uncertain = !related || score.artist_similarity.is_some_and(|s| s < 0.65)
            || score.version_penalty > 0.0 || recording_markers(query) != recording_markers(&track)
            || (query.duration_ms > 0 && track.duration_ms > 0 && query.duration_ms.abs_diff(track.duration_ms) > 5_000);
        json!({"track":track,"related":related,"requires_confirmation":uncertain})
    }).collect();
    rows.sort_by(|a,b| b["related"].as_bool().cmp(&a["related"].as_bool())
        .then_with(|| b["track"]["score"].as_f64().unwrap_or(0.0).total_cmp(&a["track"]["score"].as_f64().unwrap_or(0.0))));
    Ok(json!({"ok":true,"recordings":rows,"page":page,"has_more":more,"keyword":keyword,"partial_error":partial}))
}
fn related(query: &Track, track: &Track) -> bool {
    // Title evidence determines the default group; performer and length cannot rescue an unrelated title.
    crate::matching::score_metadata_alias(query.metadata(), track.metadata()).title_similarity >= 0.55
}
async fn catalog_page(client: &reqwest::Client, provider: &str, term: &str, page: u32) -> Result<(Vec<Track>,bool),String> {
    let size = 20u32;
    if provider == "netease" {
        let api = NcmApi::new(false, "");
        let response = api.search(term, Some(json!({"limit":size,"offset":(page-1)*size}))).await
            .map_err(|e| format!("netease network error during search: {e}"))?;
        let value: Value = response.deserialize().map_err(|_| "ParseError")?;
        if value["code"].as_i64() != Some(200) { return Err("HttpError".into()); }
        let songs = value.pointer("/result/songs").and_then(Value::as_array).cloned().unwrap_or_default();
        let more = value.pointer("/result/songCount").and_then(Value::as_u64)
            .map(|n| u64::from(page*size) < n).unwrap_or(songs.len() == size as usize);
        let tracks = songs.iter().filter_map(|s| {
            let artists = s.get("ar").or_else(|| s.get("artists"))?.as_array()?;
            let album = s.get("al").or_else(|| s.get("album"));
            Some(Track { id:s["id"].as_u64()?.to_string(),title:s["name"].as_str()?.into(),
                artist:artists.iter().filter_map(|a|a["name"].as_str()).collect::<Vec<_>>().join(", "),
                album:album.and_then(|a|a["name"].as_str()).unwrap_or_default().into(),
                duration_ms:s.get("dt").or_else(||s.get("duration")).and_then(Value::as_u64).unwrap_or(0),score:0.0 })
        }).collect();
        return Ok((tracks,more));
    }
    if provider != "kugou" { return parse_error(); }
    // Keep the existing HTTPS client and use a signed catalogue request, with the legacy endpoint as fallback.
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|_|"NativeError")?.as_millis();
    let mid = openssl::hash::hash(openssl::hash::MessageDigest::md5(),now.to_string().as_bytes())
        .map_err(|_|"NativeError")?.iter().map(|b|format!("{b:02x}")).collect::<String>();
    let mut params = std::collections::BTreeMap::<String,String>::from([
        ("keyword".into(),term.into()),("page".into(),page.to_string()),("pagesize".into(),size.to_string()),
        ("sorttype".into(),"0".into()),("userid".into(),"0".into()),("appid".into(),"3116".into()),
        ("token".into(),"".into()),("clienttime".into(),(now/1000).to_string()),("iscorrection".into(),"1".into()),
        ("uuid".into(),"-".into()),("mid".into(),mid),("dfid".into(),"-".into()),
        ("clientver".into(),"11070".into()),("platform".into(),"AndroidFilter".into())]);
    let sign_body = params.iter().map(|(k,v)|format!("{k}={v}")).collect::<String>();
    let digest = openssl::hash::hash(openssl::hash::MessageDigest::md5(),
        format!("LnT6xpN3khm36zse0QzvmgTZ3waWdRSA{sign_body}LnT6xpN3khm36zse0QzvmgTZ3waWdRSA").as_bytes()).map_err(|_|"NativeError")?;
    params.insert("signature".into(),digest.iter().map(|b|format!("{b:02x}")).collect());
    let modern = response(client.get("https://complexsearch.kugou.com/v2/search/song")
        .header("User-Agent","Android14-1070-11070-201-0-SearchSong-wifi")
        .header("x-router","complexsearch.kugou.com").query(&params)).await;
    let data = match modern {
        Ok(v) if v.pointer("/data/lists").is_some() => v,
        Err(e) if e == "RateLimited" => return Err(e),
        _ => response(client.get("https://mobiles.kugou.com/api/v3/search/song")
            .query(&[("format","json"),("keyword",term),("page",&page.to_string()),("pagesize","20"),("showtype","1")])).await?,
    };
    if data["status"].as_i64() == Some(0) { return Err("HttpError".into()); }
    let songs = data.pointer("/data/lists").or_else(||data.pointer("/data/info")).and_then(Value::as_array).ok_or("ParseError")?;
    let more = data.pointer("/data/total").and_then(Value::as_u64)
        .map(|n|u64::from(page*size)<n).unwrap_or(songs.len()==size as usize);
    let tracks = songs.iter().filter_map(|s| Some(Track {
        id:s.get("FileHash").or_else(||s.get("hash"))?.as_str()?.into(),
        title:{
            let title=s.get("SongName").or_else(||s.get("songname"))?.as_str()?;
            let subtitle=s.get("Auxiliary").or_else(||s.get("topic")).and_then(Value::as_str).unwrap_or_default().trim();
            if !subtitle.is_empty() && !title.contains(subtitle) { format!("{title} ({subtitle})") } else { title.into() }
        },
        artist:s.get("singername").and_then(Value::as_str).map(|s|s.replace('、',", "))
            .unwrap_or_else(||s["Singers"].as_array().map(|a|a.iter().filter_map(|x|x["name"].as_str()).collect::<Vec<_>>().join(", ")).unwrap_or_default()),
        album:s.get("AlbumName").or_else(||s.get("album_name")).and_then(Value::as_str).unwrap_or_default().into(),
        duration_ms:s.get("Duration").or_else(||s.get("duration")).and_then(Value::as_u64).unwrap_or(0)*1000,score:0.0
    })).collect();
    Ok((tracks,more))
}
pub(crate) async fn options(provider: &str, track: &Track) -> Result<Value,String> {
    let client = client()?;
    if provider == "netease" { return netease(&client,track).await; }
    if provider != "kugou" { return parse_error(); }
    let r = response(client.get("https://lyrics.kugou.com/search").query(&[
        ("ver","1"),("man","yes"),("client","pc"),("keyword",&crate::manual::search_term(track)),
        ("hash",&track.id),("timelength",&track.duration_ms.to_string())])).await?;
    if r["status"].as_i64()==Some(0) { return Err("HttpError".into()); }
    let candidates = r["candidates"].as_array().ok_or("ParseError")?;
    let mut rows = Vec::new();
    let mut partial = None;
    for c in candidates {
        let id = c["id"].as_str().map(str::to_owned).or_else(||c["id"].as_u64().map(|n|n.to_string()));
        let Some((id,key)) = id.zip(c["accesskey"].as_str()) else { partial=Some("ParseError"); continue };
        let mut song = track.clone();
        if let Some(s)=c["song"].as_str().filter(|s|!s.is_empty()) { song.title=s.into(); }
        if let Some(s)=c["singer"].as_str().filter(|s|!s.is_empty()) { song.artist=s.into(); }
        if let Some(n)=c["duration"].as_u64().filter(|n|*n>0) { song.duration_ms=n; }
        rows.push(json!({"id":id,"access_key":key,"track":song,
            "creator":c["nickname"],"rating":c["score"],
            "requires_confirmation":!related(track,&song) || recording_markers(track)!=recording_markers(&song)
                || (track.duration_ms>0 && song.duration_ms>0 && track.duration_ms.abs_diff(song.duration_ms)>5000)
                || crate::matching::score_metadata_alias(track.metadata(),song.metadata()).artist_similarity.is_some_and(|s|s<0.65)}));
    }
    Ok(json!({"ok":true,"versions":[],"options":rows,"partial_error":partial}))
}
pub(crate) async fn download(request: &crate::manual::Request) -> Result<Value,String> {
    if request.provider!="kugou" { return parse_error(); }
    let id = request.lyric["id"].as_str().ok_or("ParseError")?;
    let key = request.lyric["access_key"].as_str().ok_or("ParseError")?;
    let r = response(client()?.get("https://lyrics.kugou.com/download").query(&[
        ("ver","1"),("client","pc"),("id",id),("accesskey",key),("fmt","krc"),("charset","utf8")])).await?;
    let Some(raw)=downloaded_krc(&r)? else { return Ok(json!({"ok":true,"versions":[],"content_state":"plain"})) };
    let mut track=request.track.clone();
    let selected: Track=serde_json::from_value(request.lyric["track"].clone()).map_err(|_|"ParseError")?;
    let hash=track.id.clone();
    track.title=selected.title; track.artist=selected.artist; track.duration_ms=selected.duration_ms;
    let identity=format!("{hash}:{id}");
    let uncertain=request.lyric["requires_confirmation"].as_bool().unwrap_or(false);
    if !raw.contains('<') {
        let line_re=Regex::new(r"(?m)^\[(\d+),(\d+)\](.*)$").unwrap();
        let lines: Vec<Value>=line_re.captures_iter(&raw).map(|c|json!({"startMs":c[1].parse::<u64>().unwrap_or(0),"text":&c[3]})).collect();
        if lines.is_empty() { return Ok(success(vec![])); }
        return Ok(success(vec![line_version(&track,"kugou",&identity,lrc(&lines),None,uncertain)]));
    }
    let lines=parse_words(&raw,true)?;
    if lines.is_empty() { return Ok(success(vec![])); }
    let (translation,error)=match krc_translation(&raw,&lines) { Ok(t)=>(t,None),Err(e)=>(None,Some(e)) };
    Ok(json!({"ok":true,"versions":[version(&track,"kugou",&identity,lines,translation,uncertain)],"partial_error":error}))
}
fn eapi_params(data: &Value) -> Result<String, String> {
    let path = "/api/song/lyric/v1";
    let data = data.to_string();
    let digest = openssl::hash::hash(openssl::hash::MessageDigest::md5(),
        format!("nobody{path}use{data}md5forencrypt").as_bytes()).map_err(|_| "NativeError")?;
    let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    let body = format!("{path}-36cd479b6b5-{data}-36cd479b6b5-{}", hex(&digest));
    let encrypted = openssl::symm::encrypt(openssl::symm::Cipher::aes_128_ecb(),
        b"e82ckenh8dichen8", None, body.as_bytes()).map_err(|_| "NativeError")?;
    Ok(hex(&encrypted).to_uppercase())
}
async fn netease(client: &reqwest::Client, track: &Track) -> Result<Value, String> {
    let id=track.id.parse::<u64>().map_err(|_|"ParseError")?;
    let data=json!({"id":id,"cp":"false","lv":"-1","kv":"-1","tv":"-1","rv":"-1",
        "yv":"-1","ytv":"-1","yrv":"-1","csrf_token":"",
        "header":json!({"os":"android","appver":"8.0.0","versioncode":"140"}).to_string()});
    let r=response(client.post("https://interface3.music.163.com/eapi/song/lyric/v1")
        .header("Referer","https://music.163.com/").header("Cookie","os=android; appver=8.0.0")
        .form(&[("params",eapi_params(&data)?)])).await?;
    if r["code"].as_i64()!=Some(200) { return Err("HttpError".into()); }
    netease_versions(&r,track)
}
fn netease_versions(r:&Value, track:&Track)->Result<Value,String> {
    let mut versions=Vec::new();
    let mut error=None;
    let text=|key:&str|r.get(key).and_then(|v|v["lyric"].as_str()).filter(|s|!s.trim().is_empty());
    if let Some(raw)=text("lrc").and_then(|s|crate::matching::normalize_usable_lrc(s.into())) {
        versions.push(line_version(track,"netease",&track.id,raw,text("tlyric").map(str::to_owned),false));
    }
    if let Some(raw)=text("yrc") {
        match parse_words(raw,false) {
            Ok(lines) if !lines.is_empty() => {
                let translation=match text("ytlrc") {
                    Some(s) if Regex::new(r"(?m)^\[\d+,\d+\]").unwrap().is_match(s) => match parse_words(s,false) {
                        Ok(lines)=>Some(lrc(&lines)),Err(e)=>{error=Some(e);None}
                    },
                    value=>value.map(str::to_owned),
                };
                versions.push(version(track,"netease",&track.id,lines,translation,false));
            },
            _=>error=Some("ParseError".into()),
        }
    }
    Ok(json!({"ok":true,"versions":versions,"partial_error":error}))
}
fn line_version(t:&Track,provider:&str,id:&str,lrc:String,translation:Option<String>,uncertain:bool)->Value {
    json!({"id":id,"provider":provider,"track":t,"kind":"line","lrc":lrc,
        "translated_lrc":translation,"lines":[],"requires_confirmation":uncertain})
}
fn version(t: &Track, provider: &str, id: &str, lines: Vec<Value>, translation: Option<String>, uncertain: bool) -> Value {
    json!({"id":id,"provider":provider,"track":t,"lrc":lrc(&lines),
        "translated_lrc":translation,"lines":lines,"requires_confirmation":uncertain})
}
fn lrc(lines: &[Value]) -> String {
    lines.iter().map(|line| {
        let ms = line["startMs"].as_u64().unwrap();
        format!("[{:02}:{:02}.{:03}]{}",ms/60000,ms/1000%60,ms%1000,line["text"].as_str().unwrap())
    }).collect::<Vec<_>>().join("\n")
}

/// YRC word times are absolute; KRC word times are relative to the line start.
/// Reject malformed timed content instead of relabelling a line-only payload as word timed.
fn parse_words(raw: &str, krc: bool) -> Result<Vec<Value>, String> {
    let line_re = Regex::new(r"^\[(\d+),(\d+)\](.*)$").unwrap();
    let word_re = Regex::new(if krc { r"<(\d+),(\d+),\d+>" } else { r"\((\d+),(\d+),\d+\)" }).unwrap();
    let mut lines = Vec::new();
    for line in raw.trim_start_matches('\u{feff}').lines() {
        let Some(c) = line_re.captures(line) else { continue };
        let number = |s: &str| s.parse::<u64>().map_err(|_| "ParseError".to_owned());
        let start = number(&c[1])?;
        let mut end = start.checked_add(number(&c[2])?).ok_or("ParseError")?;
        if end <= start || end > 86_400_000 { return parse_error(); }
        let body = &c[3];
        let mut tokens: Vec<Value> = Vec::new();
        let mut consumed = 0;
        let mut previous_start = start;
        let mut prefix = String::new();
        let words: Vec<_> = word_re.captures_iter(body).collect();
        for (index, w) in words.iter().enumerate() {
            let m = w.get(0).unwrap();
            if m.start() != consumed { return parse_error(); }
            consumed = words.get(index+1).map(|next| next.get(0).unwrap().start()).unwrap_or(body.len());
            let text = &body[m.end()..consumed];
            let a = number(&w[1])?.checked_add(if krc { start } else { 0 }).ok_or("ParseError")?;
            let b = a.checked_add(number(&w[2])?).ok_or("ParseError")?;
            // Provider line lengths sometimes round one millisecond short. Preserve the
            // actual word bounds and extend the enclosing line by at most two milliseconds.
            if a < start || a < previous_start || b > end.saturating_add(2) { return parse_error(); }
            previous_start = a;
            end = end.max(b);
            if text.is_empty() { continue; }
            // Zero-duration punctuation/spacing has no highlight interval of its own.
            // Preserve its text on an adjacent real segment, without inventing a time.
            if b == a || text.trim().is_empty() {
                if let Some(previous) = tokens.last_mut() {
                    previous["text"] = json!(format!("{}{text}",previous["text"].as_str().unwrap()));
                } else {
                    prefix.push_str(text);
                }
            } else {
                tokens.push(json!({"text":format!("{prefix}{text}"),"startMs":a,"endMs":b}));
                prefix.clear();
            }
        }
        if consumed != body.len() || tokens.is_empty() { return parse_error(); }
        let text = tokens.iter().filter_map(|t| t["text"].as_str()).collect::<String>();
        if text.trim().is_empty() { continue; }
        lines.push(json!({"startMs":start,"endMs":end,"text":text,"tokens":tokens}));
    }
    lines.sort_by_key(|v| v["startMs"].as_u64().unwrap());
    Ok(lines)
}
fn decode_krc(content: &str) -> Result<String, String> {
    let bytes = STANDARD.decode(content).map_err(|_| "ParseError")?;
    if !bytes.starts_with(b"krc1") || bytes.len() <= 4 || bytes.len() > MAX_BYTES { return parse_error(); }
    let zipped: Vec<u8> = bytes[4..].iter().zip(KRC_KEY.iter().cycle()).map(|(a,b)| a^b).collect();
    let mut decoded = Vec::new();
    flate2::read::ZlibDecoder::new(&zipped[..]).take((MAX_BYTES+1) as u64)
        .read_to_end(&mut decoded).map_err(|_| "ParseError")?;
    if decoded.len() > MAX_BYTES { return parse_error(); }
    String::from_utf8(decoded).map_err(|_| "ParseError".into())
}
fn downloaded_krc(response: &Value) -> Result<Option<String>, String> {
    // Kugou can explicitly return plain text even when fmt=krc was requested.
    // That is a valid absence of word timing, not a decryption failure.
    if response["contenttype"].as_u64() == Some(2) { return Ok(None); }
    decode_krc(response["content"].as_str().ok_or("ParseError")?).map(Some)
}
fn krc_translation(raw: &str, lines: &[Value]) -> Result<Option<String>, String> {
    let Some(encoded) = raw.trim_start_matches('\u{feff}').lines().find_map(|s| s.strip_prefix("[language:").and_then(|s| s.strip_suffix(']'))) else { return Ok(None) };
    let bytes = STANDARD.decode(encoded).map_err(|_| "ParseError")?;
    let language: Value = serde_json::from_slice(&bytes).map_err(|_| "ParseError")?;
    let Some(items) = language["content"].as_array() else { return parse_error() };
    for item in items {
        if item["type"].as_u64() != Some(1) { continue; }
        let rows = item["lyricContent"].as_array().ok_or("ParseError")?;
        // Index-based translations are only safe when the complete line counts agree.
        if rows.len() != lines.len() { continue; }
        let translated: Vec<Value> = rows.iter().zip(lines).map(|(row,line)| {
            let text = row.as_array().map(|r| r.iter().filter_map(Value::as_str).collect::<String>()).unwrap_or_default();
            json!({"startMs":line["startMs"],"text":text})
        }).collect();
        return Ok(Some(lrc(&translated)));
    }
    Ok(None)
}
fn recording_markers(track: &Track) -> (bool, bool) {
    let text = format!("{} {}",track.title,track.album).to_lowercase();
    let live = ["live","concert","现场","現場","演唱会","演唱會"].iter().any(|s| text.contains(s));
    let rerecorded = ["re-record","rerecord","重新录","重新錄","重录","重錄","taylor's version"]
        .iter().any(|s| text.contains(s));
    (live,rerecorded)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn absolute_yrc_and_relative_krc_match() {
        let y = parse_words("[1000,1000](1000,400,0)你(1400,600,0)好",false).unwrap();
        let k = parse_words("[1000,1000]<0,400,0>你<400,600,0>好",true).unwrap();
        assert_eq!(y,k);
        assert_eq!(y[0]["tokens"][1]["endMs"],2000);
        assert_eq!(lrc(&y),"[00:01.000]你好");
    }
    #[test] fn explicit_plain_text_download_is_absence_but_broken_krc_is_failure() {
        assert_eq!(downloaded_krc(&json!({"contenttype":2,"content":STANDARD.encode("plain lyrics")})).unwrap(),None);
        assert!(downloaded_krc(&json!({"contenttype":0,"content":"corrupt"})).is_err());
    }
    #[test] fn rejects_broken_timing_and_plain_text() {
        for s in ["[1,100]hello","[1,100](0,10,0)bad","[1,100](1,0,0)bad","[1,100](1,104,0)bad"] {
            assert!(parse_words(s,false).is_err());
        }
        assert!(parse_words("[00:01.00]plain",false).unwrap().is_empty());
        assert!(decode_krc("a3JjMQ==").is_err());
    }
    #[test] fn zero_duration_text_and_rounding_keep_real_word_intervals() {
        let lines=parse_words("[1000,1000](1000,0,0)「(1000,500,0)你(1500,501,0)好(2001,0,0)」",false).unwrap();
        assert_eq!(lines[0]["text"],"「你好」");
        assert_eq!(lines[0]["endMs"],2001);
        assert_eq!(lines[0]["tokens"].as_array().unwrap().len(),2);
        assert_eq!(lines[0]["tokens"][0]["startMs"],1000);
        assert_eq!(lines[0]["tokens"][0]["endMs"],1500);
        assert_eq!(lines[0]["tokens"][1]["endMs"],2001);
    }
    #[test] fn encrypted_krc_roundtrip_keeps_translation_and_punctuation() {
        use std::io::Write;
        let language=STANDARD.encode(json!({"content":[{"type":1,"lyricContent":[["Hello"]]}]}).to_string());
        let raw=format!("[language:{language}]\n[1000,1000]<0,400,0>你<400,600,0>好 (世界)");
        let mut encoder=flate2::write::ZlibEncoder::new(Vec::new(),flate2::Compression::default());
        encoder.write_all(format!("\u{feff}{raw}").as_bytes()).unwrap();
        let mut bytes=b"krc1".to_vec();
        bytes.extend(encoder.finish().unwrap().iter().zip(KRC_KEY.iter().cycle()).map(|(a,b)| a^b));
        let decoded=decode_krc(&STANDARD.encode(bytes)).unwrap();
        let lines=parse_words(&decoded,true).unwrap();
        assert_eq!(lines[0]["text"],"你好 (世界)");
        assert_eq!(krc_translation(&decoded,&lines).unwrap().as_deref(),Some("[00:01.000]Hello"));
    }
    #[test] fn catalogue_keeps_unrelated_recordings_out_of_the_default_group() {
        let q=Track{id:"1".into(),title:"Song".into(),artist:"Artist".into(),album:"".into(),duration_ms:200000,score:0.0};
        assert!(related(&q,&q));
        assert!(!related(&q,&Track{title:"Unrelated".into(),..q.clone()}));
        assert!(related(&q,&Track{title:"Song (Live)".into(),..q.clone()}));
    }
    #[test] fn independent_line_and_word_versions_and_partial_parse_failure() {
        let q=Track{id:"42".into(),title:"Song".into(),artist:"Artist".into(),album:"".into(),duration_ms:0,score:0.0};
        let both=json!({"lrc":{"lyric":"[00:01.00]你好"},"yrc":{"lyric":"[1000,1000](1000,400,0)你(1400,600,0)好"}});
        let parsed=netease_versions(&both,&q).unwrap();
        assert_eq!(parsed["versions"].as_array().unwrap().len(),2);
        let broken=json!({"lrc":{"lyric":"[00:01.00]你好"},"yrc":{"lyric":"[1000,1000]broken"}});
        let parsed=netease_versions(&broken,&q).unwrap();
        assert_eq!(parsed["versions"].as_array().unwrap().len(),1);
        assert_eq!(parsed["partial_error"],"ParseError");
    }
}
