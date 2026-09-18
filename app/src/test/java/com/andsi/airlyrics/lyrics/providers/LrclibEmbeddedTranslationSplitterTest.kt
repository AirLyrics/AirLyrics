package com.andsi.airlyrics.lyrics.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrclibEmbeddedTranslationSplitterTest {
    @Test
    fun highConfidenceBilingualRowsAreSplitIntoSeparateTracks() {
        val lrc =
            """
            [ar:Example Artist]
            [00:01.00]君の声
            [00:01.00]你的声音
            [00:02.00]夜を越えて
            [00:02.00]跨越黑夜
            [00:03.00]また会える
            [00:03.00]还会再见
            [00:04.00]終わり
            """.trimIndent()

        val tracks = splitLrclibEmbeddedTranslations(lrc)

        assertEquals(
            """
            [ar:Example Artist]
            [00:01.00]君の声
            [00:02.00]夜を越えて
            [00:03.00]また会える
            [00:04.00]終わり
            """.trimIndent(),
            tracks.originalLrc
        )
        assertEquals(
            """
            [00:01.00]你的声音
            [00:02.00]跨越黑夜
            [00:03.00]还会再见
            """.trimIndent(),
            tracks.translatedLrc
        )
    }

    @Test
    fun isolatedSameTimestampRowsRemainUntouched() {
        val lrc =
            """
            [00:01.00]Lead vocal
            [00:01.00]伴唱
            [00:02.00]Second line
            [00:03.00]Third line
            """.trimIndent()

        val tracks = splitLrclibEmbeddedTranslations(lrc)

        assertEquals(lrc, tracks.originalLrc)
        assertNull(tracks.translatedLrc)
    }

    @Test
    fun repeatedSameScriptRowsRemainUntouched() {
        val lrc =
            """
            [00:01.00]First singer
            [00:01.00]Second singer
            [00:02.00]Lead vocal
            [00:02.00]Backing vocal
            [00:03.00]Call me
            [00:03.00]Answer me
            """.trimIndent()

        val tracks = splitLrclibEmbeddedTranslations(lrc)

        assertEquals(lrc, tracks.originalLrc)
        assertNull(tracks.translatedLrc)
    }

    @Test
    fun locallyUnsupportedPairRemainsInOriginalTrack() {
        val lrc =
            """
            [00:01.00]君の声
            [00:01.00]你的声音
            [00:02.00]夜を越えて
            [00:02.00]跨越黑夜
            [00:03.00]また会える
            [00:03.00]还会再见
            [00:04.00]Go
            [00:04.00]Да
            """.trimIndent()

        val tracks = splitLrclibEmbeddedTranslations(lrc)

        assertEquals(
            """
            [00:01.00]君の声
            [00:02.00]夜を越えて
            [00:03.00]また会える
            [00:04.00]Go
            [00:04.00]Да
            """.trimIndent(),
            tracks.originalLrc
        )
        assertEquals(
            """
            [00:01.00]你的声音
            [00:02.00]跨越黑夜
            [00:03.00]还会再见
            """.trimIndent(),
            tracks.translatedLrc
        )
    }

    @Test
    fun timestampThatWouldOverflowMillisecondsLeavesInputUntouched() {
        val lrc =
            """
            [153722867280912:55.999]君の声
            [153722867280912:55.999]你的声音
            [00:02.00]夜を越えて
            [00:02.00]跨越黑夜
            [00:03.00]また会える
            [00:03.00]还会再见
            """.trimIndent()

        val tracks = splitLrclibEmbeddedTranslations(lrc)

        assertEquals(lrc, tracks.originalLrc)
        assertNull(tracks.translatedLrc)
    }

    @Test
    fun ambiguousMultiTimestampSyntaxRemainsUntouched() {
        val lrc =
            """
            [00:01.00]君の声
            [00:01.00]你的声音
            [00:02.00]夜を越えて
            [00:02.00]跨越黑夜
            [00:03.00][00:04.00]また会える
            [00:03.00]还会再见
            """.trimIndent()

        val tracks = splitLrclibEmbeddedTranslations(lrc)

        assertEquals(lrc, tracks.originalLrc)
        assertNull(tracks.translatedLrc)
    }
}
