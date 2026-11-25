package com.andsi.airlyrics.lyrics

/**
 * Decides whether locally cached lyrics still belong to what the player is playing.
 *
 * A cached file is written under the *played* song's identity, but its content came from whatever
 * the source matched at download time. When that match was wrong (e.g. an earlier build picked
 * David Tao's "Wo Tai Sha" for "Susan Said"), the wrong text would otherwise be reused forever, because local
 * lyrics are served before any online lookup.
 *
 * Entries written before this provenance existed carry no matched title and are trusted as before.
 */
internal object CachedMatchGuard {

    fun isTrusted(
        playedTitle: String,
        cachedMatchedTitle: String,
        aliasTitle: String?
    ): Boolean {
        return cachedMatchedTitle.isBlank() ||
            sameTitle(playedTitle, cachedMatchedTitle) ||
            (!aliasTitle.isNullOrBlank() && sameTitle(aliasTitle, cachedMatchedTitle))
    }

    fun sameTitle(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        return a.isNotEmpty() && b.isNotEmpty() &&
            (a == b || a.contains(b) || b.contains(a))
    }

    private fun normalize(raw: String): String {
        val withoutSuffix = raw
            .replace(Regex("""(?i)\s*[\(\[]\s*(feat\.?|ft\.?|with)\s.*$"""), "")
            .replace(Regex("""(?i)\s+feat\.?\s.*$"""), "")
        return withoutSuffix.lowercase().filter { it.isLetterOrDigit() }
    }
}
