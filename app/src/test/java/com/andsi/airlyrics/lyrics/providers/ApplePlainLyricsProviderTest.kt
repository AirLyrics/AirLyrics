package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.core.text.ChineseText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePlainLyricsProviderTest {
    private fun translationsFor(
        starts: List<Long>,
        translatedLrc: String,
        fields: List<String> = List(starts.size) { "" },
        originalStarts: List<Long> = starts
    ): List<String> {
        val words = starts.mapIndexed { index, start ->
            """{"start_ms":$start,"end_ms":${start + 500},"text":"line$index",
                "segments":[{"text":"line$index","start_ms":$start,"end_ms":${start + 500}}]${fields[index]}}"""
        }.joinToString(",")
        val originalLrc = originalStarts.joinToString("\n") { start ->
            "[00:${(start / 1000).toString().padStart(2, '0')}.${(start % 1000).toString().padStart(3, '0')}]original"
        }
        val json = org.json.JSONObject().put("ok", true).put("lrc", originalLrc)
            .put("translated_lrc", translatedLrc).put("word_by_word", org.json.JSONArray("[$words]"))
        return ApplePlainLyricsProvider.parse(json.toString(), "", "", "", 0)!!
            .wordByWordLines.map { it.translatedText }
    }

    @Test
    fun equalCountsDoNotPairShiftedTranslations() {
        assertEquals(listOf("", "second"), translationsFor(
            listOf(1000, 2000), "[00:02.00]second\n[00:03.00]third",
            originalStarts = listOf(1000, 2000, 3000)
        ))
    }

    @Test
    fun explicitTranslationAndNullOverrideLegacyFallback() {
        assertEquals(listOf("", "direct"), translationsFor(
            listOf(1000, 2000), "[00:01.00]wrong\n[00:02.00]wrong",
            listOf(",\"translated_text\":null", ",\"translated_text\":\"direct\"")
        ))
    }

    @Test
    fun legacyUniqueTimesAllowOnlyTenMillisecondTruncation() {
        assertEquals(listOf("first", "second", ""), translationsFor(
            listOf(1009, 2000, 3000), "[00:01.00]first\n[00:02.00]second\n[00:02.99]outside"
        ))
    }

    @Test
    fun duplicateTimesAndTruncationCollisionsStayEmpty() {
        assertEquals(listOf(""), translationsFor(listOf(1000), "[00:01.00]a\n[00:01.00]b"))
        for (starts in listOf(listOf(1000L, 1000L), listOf(1001L, 1009L))) {
            assertEquals(listOf("", ""), translationsFor(starts, "[00:01.00]single"))
        }
        assertEquals(listOf(""), translationsFor(
            listOf(1001), "[00:01.00]single", originalStarts = listOf(1001, 1009)
        ))
    }

    @Test
    fun failedNativeResponsesPreserveClassificationAndMessage() {
        for (nativeType in listOf("NotFound", "NetworkError", "ParseError", "NativeError", "NeedCredential", "RateLimited", "Unexpected", "")) {
            val response = org.json.JSONObject().put("ok", false).put("error", "original message")
            if (nativeType.isNotEmpty()) response.put("error_type", nativeType)
            val parsed = runCatching { ApplePlainLyricsProvider.parse(response.toString(), "", "", "", 0) }
            if (nativeType == "NotFound") {
                assertTrue(parsed.isSuccess)
                assertEquals(null, parsed.getOrThrow())
            } else {
                val error = parsed.exceptionOrNull() as com.andsi.airlyrics.lyrics.LyricsLookupException
                val expected = if (nativeType in listOf("Unexpected", "")) "NativeError" else nativeType
                assertEquals(expected, error.errorType.name)
                assertEquals("original message", error.detailMessage)
            }
        }
    }

    @Test
    fun translatedLrc_isAttachedToEachWordByWordLine() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说'))
        val json = """
            {
              "ok": true,
              "title": "苏三说",
              "artist": "陶喆",
              "duration_ms": 261826,
              "lrc": "[00:15.44]蘇三說\n[00:25.37]Susan\n",
              "translated_lrc": "[00:15.44]苏三说\n[00:25.37]Susan\n",
              "word_by_word": [
                {"start_ms": 15440, "end_ms": 25370, "text": "蘇三說",
                 "segments": [{"text": "蘇", "start_ms": 15440, "end_ms": 18000},
                              {"text": "三說", "start_ms": 18000, "end_ms": 25370}]}
              ]
            }
        """.trimIndent()

        val result = ApplePlainLyricsProvider.parse(json, "苏三说", "陶喆", "", 261826)

        assertEquals("苏三说", result?.wordByWordLines?.single()?.translatedText)
        val segments = result?.wordByWordLines?.single()?.translatedSegments.orEmpty()
        assertEquals("苏三说", segments.joinToString("") { it.text })
        assertEquals(15440L, segments.first().startMs)
        assertEquals(25370L, segments.last().endMs)
        assertTrue(segments.all { it.endMs > it.startMs })
    }

    @Test
    fun translatedLrc_leavesUnverifiedTimeDriftEmpty() {
        ChineseText.installTableForTest(mapOf('愛' to '爱', '你' to '你', '越' to '越', '久' to '久', '我' to '我', '被' to '被', '動' to '动'))
        val json = """
            {
              "ok": true, "title": "被動", "artist": "伍佰", "duration_ms": 240000,
              "lrc": "[01:10.06]愛你越久我越被動\n",
              "translated_lrc": "[01:10.46]爱你越久我越被动\n",
              "word_by_word": [
                {"start_ms": 70060, "end_ms": 72340, "text": "愛你越久我越被動",
                 "segments": [{"text": "愛", "start_ms": 70060, "end_ms": 70620},
                              {"text": "你", "start_ms": 70620, "end_ms": 71030},
                              {"text": "越久我越", "start_ms": 71030, "end_ms": 71860},
                              {"text": "被動", "start_ms": 71860, "end_ms": 72340}]}
              ]
            }
        """.trimIndent()

        val line = ApplePlainLyricsProvider.parse(json, "被動", "伍佰", "", 240000)
            ?.wordByWordLines?.single()

        assertEquals("", line?.translatedText)
        assertTrue(line?.translatedSegments.orEmpty().isEmpty())
    }

    @Test
    fun pureScriptConversion_ignoresAnExtraSpaceInTheTranslation() {
        ChineseText.installTableForTest(mapOf('愛' to '爱', '越' to '越', '被' to '被', '動' to '动'))
        val json = """
            {
              "ok": true, "title": "被動", "artist": "伍佰", "duration_ms": 240000,
              "lrc": "[01:10.06]愛你越久我越被動\n",
              "translated_lrc": "[01:10.06]爱你 越久我越被动\n",
              "word_by_word": [
                {"start_ms": 70060, "end_ms": 72340, "text": "愛你越久我越被動",
                 "segments": [{"text": "愛", "start_ms": 70060, "end_ms": 70620},
                              {"text": "你", "start_ms": 70620, "end_ms": 71030},
                              {"text": "越久我越", "start_ms": 71030, "end_ms": 71860},
                              {"text": "被動", "start_ms": 71860, "end_ms": 72340}]}
              ]
            }
        """.trimIndent()

        val line = ApplePlainLyricsProvider.parse(json, "被動", "伍佰", "", 240000)
            ?.wordByWordLines?.single()

        assertEquals("爱你越久我越被动", line?.translatedSegments?.joinToString("") { it.text })
        assertEquals(70060L, line?.translatedSegments?.first()?.startMs)
        assertEquals(72340L, line?.translatedSegments?.last()?.endMs)
    }

    @Test
    fun pureScriptConversion_reusesOriginalWordTiming() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说', '臺' to '台'))
        val json = """
            {
              "ok": true, "title": "測試", "artist": "測試", "duration_ms": 60000,
              "lrc": "[00:10.00]蘇三說\n",
              "translated_lrc": "[00:10.00]苏三说\n",
              "word_by_word": [
                {"start_ms": 10000, "end_ms": 15000, "text": "蘇三說",
                 "segments": [{"text": "蘇", "start_ms": 10000, "end_ms": 11000},
                              {"text": "三", "start_ms": 11000, "end_ms": 13000},
                              {"text": "說", "start_ms": 13000, "end_ms": 15000}]}
              ]
            }
        """.trimIndent()

        val line = ApplePlainLyricsProvider.parse(json, "測試", "測試", "", 60000)?.wordByWordLines?.single()
        val translated = line?.translatedSegments.orEmpty()

        assertEquals(listOf("苏", "三", "说"), translated.map { it.text })
        assertEquals(listOf(10000L, 11000L, 13000L), translated.map { it.startMs })
        assertEquals(listOf(11000L, 13000L, 15000L), translated.map { it.endMs })
    }

    @Test
    fun realTranslation_hasNoFakeWordTiming() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说'))
        val json = """
            {
              "ok": true, "title": "測試", "artist": "測試", "duration_ms": 60000,
              "lrc": "[00:10.00]蘇三說\n",
              "translated_lrc": "[00:10.00]苏三他这样说\n",
              "word_by_word": [
                {"start_ms": 10000, "end_ms": 15000, "text": "蘇三說",
                 "segments": [{"text": "蘇", "start_ms": 10000, "end_ms": 11000},
                              {"text": "三說", "start_ms": 11000, "end_ms": 15000}]}
              ]
            }
        """.trimIndent()

        val translated = ApplePlainLyricsProvider.parse(json, "測試", "測試", "", 60000)
            ?.wordByWordLines?.single()?.translatedSegments.orEmpty()

        // 真实翻译无法逐字对齐：不给时间轴，UI 走“原文+译文”且译文不高亮。
        assertTrue(translated.isEmpty())
    }
    @Test
    fun singleCharacterRewrites_haveNoWordTiming() {
        ChineseText.installTableForTest(emptyMap())
        for (translation in listOf("我今天真的很想他", "我今天真的不想你")) {
            assertTrue(translatedSegmentsFor("我今天真的很想你", translation).isEmpty())
        }
    }

    @Test
    fun phraseAcrossTokens_reusesTimingWhenTheConversionKeepsLength() {
        // 甦醒/瞭解 split across tokens: the line-level conversion is authoritative when the
        // character count is preserved, so the tokens keep their timings.
        ChineseText.installTableForTest(emptyMap(), phrases = mapOf("甲乙" to "丙丁"))
        assertEquals(listOf("丙", "丁"), translatedSegmentsFor("甲乙", "丙丁").map { it.text })
    }

    @Test
    fun phraseAcrossTokens_doesNotReuseTimingWhenTheLengthChanges() {
        ChineseText.installTableForTest(emptyMap(), phrases = mapOf("甲乙" to "丙丁戊"))
        assertTrue(translatedSegmentsFor("甲乙", "丙丁戊").isEmpty())
    }

    private fun translatedSegmentsFor(original: String, translation: String) =
        ApplePlainLyricsProvider.parse(
            org.json.JSONObject()
                .put("ok", true)
                .put("lrc", "[00:01.00]$original")
                .put("translated_lrc", "[00:01.00]$translation")
                .put("word_by_word", org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("start_ms", 1000).put("end_ms", 2000).put("text", original)
                        .put("segments", org.json.JSONArray().apply {
                            original.forEachIndexed { index, character ->
                                put(org.json.JSONObject().put("text", character.toString())
                                    .put("start_ms", 1000 + index * 100)
                                    .put("end_ms", 1100 + index * 100))
                            }
                        })
                )).toString(), "", "", "", 0
        )!!.wordByWordLines.single().translatedSegments


    @Test
    fun repeatedLineReusesTheTranslationCarriedByItsOtherOccurrence() {
        ChineseText.installTableForTest(
            mapOf('說' to '说', '們' to '们', '賴' to '赖', '礙' to '碍')
        )
        val words = """
            [{"start_ms":1000,"end_ms":3000,"text":"妳說依賴是我們的阻礙",
              "segments":[{"text":"妳說依賴是我們的阻礙","start_ms":1000,"end_ms":3000}],
              "translated_text":"你说依赖是我们的阻碍"},
             {"start_ms":5000,"end_ms":7000,"text":"妳說依賴是我們的阻礙的阻礙)",
              "segments":[{"text":"妳說依賴是我們的阻礙的阻礙)","start_ms":5000,"end_ms":7000}]}]
        """.trimIndent()
        val json = org.json.JSONObject().put("ok", true)
            .put("lrc", "[00:01.00]original\n[00:05.00]original")
            .put("translated_lrc", "[00:01.00]你说依赖是我们的阻碍")
            .put("word_by_word", org.json.JSONArray(words))
        val lines = ApplePlainLyricsProvider.parse(json.toString(), "", "", "", 0)!!.wordByWordLines
        assertEquals("你说依赖是我们的阻碍", lines[1].translatedText)
        assertTrue(lines[1].translatedSegments.isNotEmpty())
    }
}
