package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.core.text.LyricsDisplayPolicy
import com.andsi.airlyrics.lyrics.parser.LrcParser
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in offline audit of the production parser and display policy. */
class AppleLyricsAuditTest {
    @Test
    fun audit() {
        val input = System.getenv("AIRLYRICS_AUDIT_INPUT")
        assumeTrue("Set AIRLYRICS_AUDIT_INPUT to audit a native payload", !input.isNullOrBlank())
        val output = File(requireNotNull(System.getenv("AIRLYRICS_AUDIT_OUTPUT")) {
            "AIRLYRICS_AUDIT_OUTPUT is required"
        })
        val dictionaries = listOf(File("src/main/assets/opencc"), File("app/src/main/assets/opencc"))
            .firstOrNull { it.isDirectory } ?: error("OpenCC dictionary directory missing")
        for (name in listOf("TSCharacters.txt", "TSPhrases.txt", "TWVariants.txt", "TWVariantsRevPhrases.txt")) {
            check(File(dictionaries, name).let { it.isFile && it.length() > 0 }) { "Missing dictionary: $name" }
        }
        ChineseText.resetForTest()
        try {
            ChineseText.loadFromDirectory(dictionaries)
            val raw = JSONObject(File(requireNotNull(input)).readText())
            val parsed = requireNotNull(ApplePlainLyricsProvider.parse(raw.toString(), "", "", "", 0))
            val trace = System.getenv("AIRLYRICS_AUDIT_TRACE")?.takeIf { it.isNotBlank() }
                ?.let { JSONArray(File(it).readText()) }
            val words = raw.optJSONArray("word_by_word") ?: JSONArray()
            val plain = LrcParser.parseWithTranslation(parsed.plainLrc, parsed.translatedLrc).filterNot { it.isMetadata }
            val rows = plain.mapIndexed { index, line ->
                val side = trace?.optJSONObject(index)
                val start = side?.optLong("startMs") ?: line.timeMs
                // Match occurrences in order: duplicate timestamps must not reuse another line.
                val occurrence = plain.take(index).count { it.timeMs == line.timeMs && it.text == line.text }
                val native = (0 until words.length()).mapNotNull { words.optJSONObject(it) }
                    .filter { it.optLong("start_ms") / 10 == line.timeMs / 10 && it.optString("text") == line.text }
                    .getOrNull(occurrence)
                val word = parsed.wordByWordLines.filter {
                    it.startMs / 10 == line.timeMs / 10 && it.text == line.text
                }.getOrNull(occurrence)
                val translation = word?.translatedText ?: line.translation.orEmpty()
                val state = when {
                    native == null || !native.has("translated_text") -> "missing"
                    native.isNull("translated_text") -> "null"
                    native.optString("translated_text").isEmpty() -> "empty"
                    else -> "value"
                }
                val conversion = ChineseText.isScriptConversion(line.text, translation)
                fun render(preference: LyricsScriptPreference): JSONObject {
                    val rendered = LyricsDisplayPolicy.render(
                        LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION, line.text, translation, preference
                    )
                    return JSONObject().put("primary", rendered.primary)
                        .put("secondary", rendered.secondary ?: JSONObject.NULL)
                }
                val simplified = render(LyricsScriptPreference.SIMPLIFIED)
                val reason = when {
                    translation.isBlank() -> "no_translation"
                    line.text.isBlank() -> "translation_only"
                    conversion -> "script_conversion_collapsed"
                    else -> "distinct_translation_kept"
                }
                JSONObject().put("lineIndex", index + 1)
                    .put("key", side?.opt("key") ?: JSONObject.NULL).put("startMs", start)
                    .put("text", line.text).put("translatedText", JSONObject()
                        .put("state", state).put("value", native?.opt("translated_text") ?: JSONObject.NULL))
                    .put("effectiveTranslatedText", translation)
                    .put("comparisonForm", JSONObject().put("text", ChineseText.comparisonForm(line.text))
                        .put("translation", ChineseText.comparisonForm(translation)))
                    .put("isScriptConversion", conversion)
                    .put("translatedSegments", word?.translatedSegments?.size ?: 0)
                    .put("primary", simplified.get("primary")).put("secondary", simplified.get("secondary"))
                    .put("NONE", render(LyricsScriptPreference.NONE))
                    .put("TRADITIONAL", render(LyricsScriptPreference.TRADITIONAL))
                    .put("reason", reason).put("traceReason", side?.opt("reason") ?: JSONObject.NULL)
            }
            check(rows.isNotEmpty()) { "No audit rows" }
            output.mkdirs()
            File(output, "report.jsonl").writeText(rows.joinToString("\n", postfix = "\n") { it.toString() })
            fun cell(value: Any?) = value.toString().replace("|", "\\|").replace("\n", "<br>")
            File(output, "report.md").writeText(
                "| 行 | key | startMs | 原文 | 译文状态 | 实际译文 | comparisonForm 原文 / 译文 | isScriptConversion | translatedSegments | primary | secondary | NONE | TRADITIONAL | reason | traceReason |\n" +
                    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n" +
                    rows.joinToString("\n", postfix = "\n") { row ->
                        val comparison = row.getJSONObject("comparisonForm")
                        listOf(row.get("lineIndex"), row.get("key"), row.get("startMs"), row.get("text"),
                            row.getJSONObject("translatedText").get("state"), row.get("effectiveTranslatedText"),
                            comparison.getString("text") + " / " + comparison.getString("translation"),
                            row.get("isScriptConversion"), row.get("translatedSegments"), row.get("primary"),
                            row.get("secondary"), row.get("NONE"), row.get("TRADITIONAL"),
                            row.get("reason"), row.get("traceReason")).joinToString(" | ", "| ", " |", transform = ::cell)
                    }
            )
        } finally {
            ChineseText.resetForTest()
        }
    }
}
