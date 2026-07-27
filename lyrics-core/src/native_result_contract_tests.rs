use super::NativeResult;
use serde_json::Value;

const SHARED_CONTRACT: &str = include_str!("../testdata/native-contract/native-results.json");

#[test]
fn native_result_serialization_matches_shared_contract_fixture() {
    let fixture: Value =
        serde_json::from_str(SHARED_CONTRACT).expect("shared native result fixture must be valid");

    assert_eq!(
        serialize(NativeResult {
            ok: true,
            source: "netease-rust",
            id: Some("contract-track-42".to_string()),
            title: Some("Contract Song".to_string()),
            artist: Some("Contract Artist".to_string()),
            album: Some("Contract Album".to_string()),
            duration_ms: Some(123_000),
            lrc: Some("[00:01.00]Original line".to_string()),
            translated_lrc: Some("[00:01.00]Translated line".to_string()),
            merged_lrc: Some("[00:01.00]Original line / Translated line".to_string()),
            karaoke_json: None,
            error_type: None,
            error: None,
        }),
        fixture["success"],
    );
    assert_eq!(
        serialize(NativeResult {
            ok: true,
            source: "musixmatch-rust",
            id: Some("contract-subtitle-translated".to_string()),
            title: Some("Translated Contract Song".to_string()),
            artist: Some("Translated Contract Artist".to_string()),
            album: Some("Translated Contract Album".to_string()),
            duration_ms: Some(181_000),
            lrc: Some("[00:03.00]Original subtitle".to_string()),
            translated_lrc: Some("[00:03.00]Translated subtitle".to_string()),
            merged_lrc: Some("[00:03.00]Original subtitle / Translated subtitle".to_string(),),
            karaoke_json: None,
            error_type: None,
            error: None,
        }),
        fixture["translated_success"],
    );
    assert_eq!(
        serialize(NativeResult {
            ok: false,
            source: "musixmatch-rust",
            id: None,
            title: None,
            artist: None,
            album: None,
            duration_ms: None,
            lrc: None,
            translated_lrc: None,
            merged_lrc: None,
            karaoke_json: None,
            error_type: Some("RateLimited"),
            error: Some("musixmatch rate limit exceeded".to_string()),
        }),
        fixture["provider_error"],
    );
    assert_eq!(
        serialize(NativeResult {
            ok: true,
            source: "musixmatch-rust",
            id: Some("contract-subtitle-7".to_string()),
            title: Some("Nullable Contract Song".to_string()),
            artist: Some("Nullable Contract Artist".to_string()),
            album: None,
            duration_ms: None,
            lrc: Some("[00:02.00]Original only".to_string()),
            translated_lrc: None,
            merged_lrc: Some("[00:02.00]Original only".to_string()),
            karaoke_json: None,
            error_type: None,
            error: None,
        }),
        fixture["nullable_success"],
    );
}

fn serialize(result: NativeResult) -> Value {
    serde_json::to_value(result).expect("production NativeResult must serialize")
}
