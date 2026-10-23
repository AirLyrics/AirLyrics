package com.andsi.airlyrics.core.text

import android.content.Context

/**
 * Script helpers for lyric display.
 *
 * Uses the OpenCC *tw2s* chain (Traditional Chinese, Taiwan standard → Simplified Chinese):
 *
 *  1. `TWVariantsRevPhrases` plus the inverse of `TWVariants`, so the traditional variant of the
 *     continuative particle (U+8457 -> U+7740) is converted while dictionary phrases such as the
 *      "famous/works" compounds stay untouched (the phrase wins the longest match);
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
                // TWVariants maps the simplified form to the traditional variant; t2s needs the
                // reverse direction.
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

    /** Source UTF-16 boundaries mapped through both full-text, phrase-aware stages.
     * Interior boundaries of length-changing replacements are deliberately unavailable.
     */
    internal fun simplifiedBoundaries(text: String): Pair<String, IntArray> {
        val first = IntArray(text.length + 1) { -1 }
        val intermediate = convert(text, twStage, first)
        val second = IntArray(intermediate.length + 1) { -1 }
        val result = convert(intermediate, tsStage, second)
        return result to IntArray(first.size) { index ->
            first[index].takeIf { it >= 0 }?.let { second[it] } ?: -1
        }
    }

    /**
     * True when [translation] is just [original] rewritten in simplified characters.
     *
     * Whitespace and punctuation are ignored: translators routinely swap full-width punctuation,
     * and that must not turn a script conversion into a "real" translation.
     */
    fun isScriptConversion(original: String, translation: String): Boolean {
        if (original.isBlank() || translation.isBlank()) return false
        return comparisonForm(original) == comparisonForm(translation)
    }

    /**
     * Drops a repeated tail that singers add for emphasis, so it cannot make a script duplicate
     * look like a real translation: "…睡得著(睡得著)", "…還是會繞會繞", "…是一種解藥解藥".
     *
     * Only the comparison uses this — the displayed line keeps everything the artist sang.
     */
    internal fun stripPerformanceRepeat(text: String): String {
        var value = text.trim()
        // A parenthetical echo can lose its opening bracket when the parser only keeps the sung
        // span ("…的阻礙的阻礙)"), so drop complete groups first, then stray closers.
        value = value.replace(Regex("\\s*[（(][^（）()]{1,24}[）)]\\s*$"), "").trim()
        value = value.trimEnd(')', '）', ' ', '\u3000').trim()
        val length = value.length
        // The repeated block must be at least two characters; single-character echoes ("想想")
        // are ordinary words far more often than performance repeats.
        for (unit in (length / 2) downTo 2) {
            val tail = value.substring(length - unit)
            if (value.regionMatches(length - 2 * unit, tail, 0, unit)) {
                return value.substring(0, length - unit).trim()
            }
            // The echo often repeats the tail behind a particle whose opening bracket was lost:
            // "…是我們的阻礙" + "(的阻礙)" arrives as "…阻礙的阻礙".
            if (length - 2 * unit - 1 >= 0) {
                val particle = value[length - unit - 1]
                if ((particle == '\u7684' || particle == '\u5730' || particle == '\u5F97') &&
                    value.regionMatches(length - 2 * unit - 1, tail, 0, unit)
                ) {
                    return value.substring(0, length - unit - 1).trim()
                }
            }
        }
        return value
    }

    /**
     * Comparison-only form: simplified, script-folded, whitespace/punctuation removed, and the
     * singer's echoed tail dropped. Cleaning comes before the echo scan so a space or bracket
     * inside the echo ("…阻礙 的阻礙") cannot hide the repeat.
     */
    fun comparisonForm(text: String): String =
        comparable(stripPerformanceRepeat(comparable(toSimplified(text))))

    private fun comparable(text: String): String {
        val stripped = buildString(text.length) {
            text.forEach { character ->
                if (character.isWhitespace() ||
                character in "\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A,.!?;:\u00B7\u2026\u2014") return@forEach
                append(foldComparisonVariant(character))
            }
        }
        return stripped
    }

    /**
     * Characters that OpenCC's tw2s tables do not fold but that lyric translators regularly swap.
     * Folding is symmetric on both sides and comparison-only, so the displayed text keeps the
     * exact characters the lyric shipped with.
     */
    private fun foldComparisonVariant(character: Char): Char = when (character) {
        '\u59B3' -> '\u4F60'   // 妳 -> 你
        '\u7942' -> '\u4ED6'   // 祂 -> 他
        '\u7260' -> '\u5B83'   // 牠 -> 它
        '\u8457' -> '\u7740'   // 著 -> 着 (lyric usage; tw2s only covers a few phrases)
        else -> character
    }

    private fun convert(text: String, stage: Stage, boundaries: IntArray? = null): String {
        val builder = StringBuilder(text.length)
        fun appendMapped(start: Int, end: Int, replacement: String) {
            val outputStart = builder.length
            boundaries?.set(start, outputStart)
            if (text.codePointCount(start, end) == replacement.codePointCount(0, replacement.length)) {
                var source = start
                var target = 0
                while (source < end) {
                    source += Character.charCount(text.codePointAt(source))
                    target += Character.charCount(replacement.codePointAt(target))
                    boundaries?.set(source, outputStart + target)
                }
            }
            builder.append(replacement)
            boundaries?.set(end, builder.length)
        }
        boundaries?.set(0, 0)
        var index = 0
        while (index < text.length) {
            var matched = false
            if (stage.maxKey > 1) {
                val limit = minOf(stage.maxKey, text.length - index)
                for (length in limit downTo 2) {
                    val replacement = stage.phrases[text.substring(index, index + length)]
                    if (replacement != null) {
                        appendMapped(index, index + length, replacement)
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
                val width = Character.charCount(text.codePointAt(index))
                appendMapped(index, index + width,
                    single ?: stage.chars[character] ?: text.substring(index, index + width))
                index += width
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

    /** True when the phrase-aware traditional→simplified conversion changes the text. */
    fun containsTraditional(text: String): Boolean = toSimplified(text) != text

    /** True when the text has any alphabetic content at all. */
    fun hasLetters(text: String): Boolean = text.any { it.isLetter() }

    private fun isHan(character: Char): Boolean {
        val code = character.code
        return code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF
    }
}
