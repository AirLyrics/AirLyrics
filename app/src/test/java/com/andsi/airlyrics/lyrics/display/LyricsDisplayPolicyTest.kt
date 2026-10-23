package com.andsi.airlyrics.lyrics.display

import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.text.LyricsDisplayPolicy
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.lyrics.parser.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LyricsDisplayPolicyTest {

    @Before
    fun setUp() {
        ChineseText.installTableForTest(
            mapOf(
                '這' to '这', '裡' to '里', '說' to '说', '愛' to '爱', '誰' to '谁', '歡' to '欢',
                '時' to '时', '間' to '间', '個' to '个', '夢' to '梦', '話' to '话', '樣' to '样'
            )
        )
    }

    @Test
    fun `a traditional line without a translation is never converted`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "我愛你 這是我心裡的話",
            translation = null,
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我愛你 這是我心裡的話", rendered.primary)
        assertNull(rendered.secondary)
    }

    @Test
    fun `simplified preference keeps the simplified copy of a duplicate pair`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "我喜歡你 這是我心裡的話",
            translation = "我喜欢你 这是我心里的话",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我喜欢你 这是我心里的话", rendered.primary)
        assertNull(rendered.secondary)
    }

    @Test
    fun `simplified preference leaves a traditional duplicate alone`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "我喜歡你",
            translation = "我喜歡你",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我喜歡你", rendered.primary)
    }

    @Test
    fun `simplified preference dedupes a line with a repeated performance tail`() {
        ChineseText.installTableForTest(
            mapOf(
                '這' to '这', '裡' to '里', '說' to '说', '愛' to '爱', '誰' to '谁', '歡' to '欢',
                '時' to '时', '間' to '间', '個' to '个', '夢' to '梦', '話' to '话', '樣' to '样',
                '見' to '见', '妳' to '你', '麼' to '么', '著' to '着', '會' to '会', '繞' to '绕'
            )
        )
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "看不見妳的笑 我怎麼睡得著(睡得著)",
            translation = "看不见你的笑 我怎么睡得着",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("看不见你的笑 我怎么睡得着", rendered.primary)
        assertNull(rendered.secondary)
    }

    @Test
    fun `a real translation is always shown next to the original`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "我喜歡你",
            translation = "我中意你",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我喜歡你", rendered.primary)
        assertEquals("我中意你", rendered.secondary)
    }

    @Test
    fun `traditional preference keeps the traditional copy of a duplicate pair`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "我喜歡你",
            translation = "我喜欢你",
            scriptPreference = LyricsScriptPreference.TRADITIONAL
        )
        assertEquals("我喜歡你", rendered.primary)
        assertNull(rendered.secondary)
    }

    @Test
    fun `traditional preference keeps a real translation`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "我喜歡你",
            translation = "我中意你",
            scriptPreference = LyricsScriptPreference.TRADITIONAL
        )
        assertEquals("我喜歡你", rendered.primary)
        assertEquals("我中意你", rendered.secondary)
    }

    @Test
    fun `no preference keeps both lines even when they only differ by script`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "我喜歡你",
            translation = "我喜欢你",
            scriptPreference = LyricsScriptPreference.NONE
        )
        assertEquals("我喜歡你", rendered.primary)
        assertEquals("我喜欢你", rendered.secondary)
    }

    @Test
    fun `a non chinese line keeps the original with the translation`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "I love you in the morning light",
            translation = "我在晨光里爱着你"
        )
        assertEquals("I love you in the morning light", rendered.primary)
        assertEquals("我在晨光里爱着你", rendered.secondary)
    }

    @Test
    fun `original only ignores the translation`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_ONLY,
            original = "我喜歡你",
            translation = "我喜欢你"
        )
        assertEquals("我喜歡你", rendered.primary)
        assertNull(rendered.secondary)
    }

    @Test
    fun `the formatter emits one line for a translated chinese lyric`() {
        val lines = listOf(
            LrcLine(timeMs = 0, text = "我喜歡你", translation = "我喜欢你"),
            LrcLine(timeMs = 5_000, text = "這樣說", translation = "这样说")
        )
        val text = PlainLyricsDisplayFormatter.format(
            plainLines = lines,
            currentIndex = 0,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            scriptPreference = LyricsScriptPreference.SIMPLIFIED,
            lineMode = LyricsLineDisplayMode.CURRENT_ONLY
        )
        assertEquals("我喜欢你", text)
    }

    @Test
    fun `the formatter keeps both lines for a translated english lyric`() {
        val lines = listOf(LrcLine(timeMs = 0, text = "Hello", translation = "你好"))
        val text = PlainLyricsDisplayFormatter.format(
            plainLines = lines,
            currentIndex = 0,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            scriptPreference = LyricsScriptPreference.SIMPLIFIED,
            lineMode = LyricsLineDisplayMode.CURRENT_ONLY
        )
        assertEquals("Hello\n你好", text)
    }
    @Test
    fun `single character rewrites keep both lines`() {
        for (translation in listOf("我今天真的很想他", "我今天真的不想你")) {
            val rendered = LyricsDisplayPolicy.render(
                LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
                original = "我今天真的很想你",
                translation = translation,
                scriptPreference = LyricsScriptPreference.SIMPLIFIED
            )
            assertEquals("我今天真的很想你", rendered.primary)
            assertEquals(translation, rendered.secondary)
        }
    }

    @Test
    fun `real dictionaries respect protected zhe phrases and convert the particle`() {
        val directory = listOf(
            java.io.File("src/main/assets/opencc"),
            java.io.File("app/src/main/assets/opencc")
        ).first { it.isDirectory }
        ChineseText.resetForTest()
        ChineseText.loadFromDirectory(directory)
        for ((preference, expected) in listOf(
            LyricsScriptPreference.SIMPLIFIED to "这部著作",
            LyricsScriptPreference.TRADITIONAL to "這部著作"
        )) {
            val rendered = LyricsDisplayPolicy.render(
                LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
                original = "這部著作",
                translation = "这部著作",
                scriptPreference = preference
            )
            assertEquals(expected, rendered.primary)
            assertNull(rendered.secondary)
        }
        assertEquals("看着这部著作", ChineseText.toSimplified("看著這部著作"))
        org.junit.Assert.assertTrue(ChineseText.containsTraditional("看著这部著作"))
    }


    @Test
    fun `simplified preference keeps a zh-Hans copy that still has one traditional particle`() {
        ChineseText.installTableForTest(
            mapOf('見' to '见', '妳' to '你', '麼' to '么', '著' to '着', '說' to '说',
                '們' to '们', '賴' to '赖', '礙' to '碍')
        )
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            original = "看不見妳的笑 我怎麼睡得著",
            translation = "看不见你的笑 我怎么睡得著",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("看不见你的笑 我怎么睡得著", rendered.primary)
        assertNull(rendered.secondary)
    }

}
