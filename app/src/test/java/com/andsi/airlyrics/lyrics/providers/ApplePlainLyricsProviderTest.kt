package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.core.text.ChineseText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplePlainLyricsProviderTest {
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
    fun translatedLrc_pairsByPositionWhenLineTimesDrift() {
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

        assertEquals("爱你越久我越被动", line?.translatedText)
        assertEquals("爱你越久我越被动", line?.translatedSegments?.joinToString("") { it.text })
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
}
