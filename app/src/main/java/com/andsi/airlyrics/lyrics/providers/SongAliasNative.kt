package com.andsi.airlyrics.lyrics.providers

/**
 * Pushes user-confirmed title/artist aliases into the native matching engine.
 *
 * The engine never invents an alias: it only uses what is registered here (or what ships in
 * `lyrics-core/data/aliases.json`). Registering is best-effort — a failure means the lookup
 * falls back to the plain metadata path.
 */
object SongAliasNative {
    init {
        LyricsNativeLibrary.ensureLoaded()
    }

    /** @param json JSON array of `{original_title, original_artist, original_artist_aliases, alias_title, alias_artist, source, confidence}`. */
    external fun registerJson(json: String): Boolean
}
