use std::fmt;
use std::time::Instant;

#[cfg(not(target_os = "android"))]
pub(crate) const LOG_TAG: &str = "AirLyricsLyrics";

pub(crate) struct LookupTrace {
    source: &'static str,
    started_at: Instant,
}

impl LookupTrace {
    pub(crate) fn new(source: &'static str) -> Self {
        Self {
            source,
            started_at: Instant::now(),
        }
    }

    pub(crate) fn event(&self, stage: &str, details: impl fmt::Display) {
        write_debug(&format!(
            "source={} stage={} elapsedMs={} {}",
            self.source,
            stage,
            self.started_at.elapsed().as_millis(),
            details,
        ));
    }
}

#[cfg(target_os = "android")]
fn write_debug(message: &str) {
    use std::ffi::{c_char, c_int, CString};

    const ANDROID_LOG_DEBUG: c_int = 3;
    const TAG: &[u8] = b"AirLyricsLyrics\0";

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    let Ok(message) = CString::new(message) else {
        return;
    };
    // SAFETY: TAG and message are valid, nul-terminated C strings for the duration of the call.
    unsafe {
        __android_log_write(
            ANDROID_LOG_DEBUG,
            TAG.as_ptr().cast::<c_char>(),
            message.as_ptr(),
        );
    }
}

#[cfg(not(target_os = "android"))]
fn write_debug(message: &str) {
    if cfg!(test) {
        eprintln!("{LOG_TAG}: {message}");
    }
}
