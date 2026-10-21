package com.andsi.airlyrics.core.text

import android.content.Context

/**
 * Script helpers for lyric display.
 *
 * Uses the OpenCC *tw2s* chain (Traditional Chinese, Taiwan standard → Simplified Chinese):
 *
 *  1. `TWVariantsRevPhrases` plus the inverse of `TWVariants`, so variant characters such as
 *     著→着 are converted while 著名/著作 stay untouched (the phrase wins the longest match);
 *  2. `TSPhrases` plus `TSCharacters`.
 *
 * Only the dictionaries are bundled, not the OpenCC engine: lyrics are short strings, so a
 * longest-match lookup over a few thousand entries is enough and keeps the APK small.
 */
object ChineseText {

    private const val ASSET_DIR = "opencc"

    private class Stage(
        val phrases: Map<String, String> = emptyMap(),
        // Values are strings: a few rare characters map to a supplementary code point (𫇭) which
        // does not fit in a single Char.
        val chars: Map<Char, String> = emptyMap()
    ) {
        val maxKey: Int = phrases.keys.maxOfOrNull { it.length } ?: 0
    }

    @Volatile
    private var twStage = Stage()

    @Volatile
    private var tsStage = Stage()

    @Volatile
    private var loaded = false

    /** Loads the conversion tables; safe to call repeatedly and from any thread. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        load { path -> context.assets.open(path) }
    }

    /** Test hook: drop installed dictionaries so a test can load its own. */
    internal fun resetForTest() {
        synchronized(this) {
            loaded = false
            twStage = Stage()
            tsStage = Stage()
        }
    }

    /** Test hook: load the real dictionaries from a directory instead of the APK assets. */
    internal fun loadFromDirectory(directory: java.io.File) {
        load { path -> java.io.File(directory, path.substringAfterLast('/')).inputStream() }
    }

    private fun load(open: (String) -> java.io.InputStream) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val twVariants = runCatching { readPairs(open("$ASSET_DIR/TWVariants.txt")) }
                .getOrDefault(emptyList())
            val inverted = buildMap {
                // TWVariants maps 着→著; t2s needs the reverse direction.
                twVariants.forEach { (source, target) ->
                    if (source.length == 1 && target.isNotEmpty() && target[0] !in this) {
                        put(target[0], source)
                    }
                }
            }
            val twPhrases = runCatching {
                readPairs(open("$ASSET_DIR/TWVariantsRevPhrases.txt"))
            }.getOrDefault(emptyList()).toMap()
            twStage = Stage(phrases = twPhrases, chars = inverted)

            val tsCharacterPairs = runCatching {
                readPairs(open("$ASSET_DIR/TSCharacters.txt"))
            }.getOrDefault(emptyList()).filter { it.second.isNotEmpty() }
            val tsPhrases = runCatching {
                readPairs(open("$ASSET_DIR/TSPhrases.txt"))
            }.getOrDefault(emptyList()).toMap() + tsCharacterPairs.filter { it.first.length > 1 }
            tsStage = Stage(
                phrases = tsPhrases,
                chars = tsCharacterPairs
                    .filter { it.first.length == 1 }
                    .associate { it.first[0] to it.second }
            )
            loaded = true
        }
    }

    /** Test hook: install dictionaries without an Android [Context]. */
    internal fun installTableForTest(
        entries: Map<Char, Char>,
        variants: Map<Char, Char> = emptyMap(),
        variantPhrases: Map<String, String> = emptyMap(),
        phrases: Map<String, String> = emptyMap()
    ) {
        synchronized(this) {
            tsStage = Stage(chars = entries.mapValues { it.value.toString() }, phrases = phrases)
            twStage = Stage(chars = variants.mapValues { it.value.toString() }, phrases = variantPhrases)
            loaded = true
        }
    }

    fun toSimplified(text: String): String {
        if (text.isEmpty() || !loaded) return text
        return convert(convert(text, twStage), tsStage)
    }

    /**
     * True when [translation] is just [original] rewritten in simplified characters.
     *
     * Whitespace and punctuation are ignored: translators routinely swap full-width punctuation,
     * and that must not turn a script conversion into a "real" translation.
     */
    fun isScriptConversion(original: String, translation: String): Boolean {
        if (original.isBlank() || translation.isBlank()) return false
        val converted = comparable(toSimplified(original))
        val target = comparable(toSimplified(translation))
        if (converted == target) return true
        // Translators occasionally pick a different but equivalent orthography for one word
        // (甚么/什么, 著/着, 祇/只). Accept a few same-length substitutions on long lines; short
        // lines must match exactly, otherwise a real rewrite like 我愛你/我恨你 slips through.
        if (converted.length != target.length || converted.length < 8) return false
        val allowed = maxOf(1, converted.length / 12)
        var differences = 0
        for (index in converted.indices) {
            if (converted[index] != target[index] && ++differences > allowed) return false
        }
        return true
    }

    private fun comparable(text: String): String {
        val stripped = buildString(text.length) {
            text.forEach { character ->
                if (character.isWhitespace() || character in "，。！？、；：,.!?;:·…—") return@forEach
                append(character)
            }
        }
        return stripped
    }

    private fun convert(text: String, stage: Stage): String {
        if (stage.phrases.isEmpty() && stage.chars.isEmpty()) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            var matched = false
            if (stage.maxKey > 1) {
                val limit = minOf(stage.maxKey, text.length - index)
                for (length in limit downTo 2) {
                    val replacement = stage.phrases[text.substring(index, index + length)]
                    if (replacement != null) {
                        builder.append(replacement)
                        index += length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                val character = text[index]
                // Some entries are a single character with several candidate values; the first
                // one is the documented default and must win over the plain character table.
                val single = stage.phrases[character.toString()]
                builder.append(single ?: stage.chars[character] ?: character.toString())
                index++
            }
        }
        return builder.toString()
    }

    private fun readPairs(input: java.io.InputStream): List<Pair<String, String>> =
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotEmpty() && !it.startsWith('#') }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 2) return@mapNotNull null
                    val target = parts[1].trim().split(' ').firstOrNull().orEmpty()
                    if (parts[0].isEmpty() || target.isEmpty()) null else parts[0] to target
                }
                .toList()
        }

    /** True when the text carries Han characters (Chinese script). */
    fun containsHan(text: String): Boolean = text.any(::isHan)

    /** True when at least one Han character has a traditional→simplified mapping. */
    fun containsTraditional(text: String): Boolean = text.any { character ->
        if (!isHan(character)) return@any false
        val mapped = tsStage.chars[character] ?: twStage.chars[character]
        mapped != null && mapped != character.toString()
    }

    /** True when the text has any alphabetic content at all. */
    fun hasLetters(text: String): Boolean = text.any { it.isLetter() }

    private fun isHan(character: Char): Boolean {
        val code = character.code
        return code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF
    }
}
