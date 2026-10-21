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
    fun `simplified preference converts the original when there is no translation`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.AUTO,
            original = "我愛你 這是我心裡的話",
            translation = null,
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我爱你 这是我心里的话", rendered.primary)
        assertNull(rendered.secondary)
    }

    @Test
    fun `simplified preference shows one line for a script conversion`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.AUTO,
            original = "我喜歡你 這是我心裡的話",
            translation = "我喜欢你 这是我心里的话",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我喜欢你 这是我心里的话", rendered.primary)
        assertNull(rendered.secondary)
    }

    @Test
    fun `simplified preference converts a traditional translation`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.AUTO,
            original = "我喜歡你",
            translation = "我喜歡你",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我喜欢你", rendered.primary)
    }

    @Test
    fun `a real translation is always shown next to the original`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.AUTO,
            original = "我喜歡你",
            translation = "我中意你",
            scriptPreference = LyricsScriptPreference.SIMPLIFIED
        )
        assertEquals("我喜歡你", rendered.primary)
        assertEquals("我中意你", rendered.secondary)
    }

    @Test
    fun `traditional preference drops a translation that only changes the script`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.AUTO,
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
            LyricsContentDisplayMode.AUTO,
            original = "我喜歡你",
            translation = "我中意你",
            scriptPreference = LyricsScriptPreference.TRADITIONAL
        )
        assertEquals("我喜歡你", rendered.primary)
        assertEquals("我中意你", rendered.secondary)
    }

    @Test
    fun `no conversion keeps both lines even when they only differ by script`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.AUTO,
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
            LyricsContentDisplayMode.AUTO,
            original = "I love you in the morning light",
            translation = "我在晨光里爱着你"
        )
        assertEquals("I love you in the morning light", rendered.primary)
        assertEquals("我在晨光里爱着你", rendered.secondary)
    }

    @Test
    fun `a line without translation keeps its original`() {
        val rendered = LyricsDisplayPolicy.render(
            LyricsContentDisplayMode.AUTO,
            original = "我喜歡你",
            translation = null,
            scriptPreference = LyricsScriptPreference.TRADITIONAL
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
            contentMode = LyricsContentDisplayMode.AUTO,
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
            contentMode = LyricsContentDisplayMode.AUTO,
            scriptPreference = LyricsScriptPreference.SIMPLIFIED,
            lineMode = LyricsLineDisplayMode.CURRENT_ONLY
        )
        assertEquals("Hello\n你好", text)
    }
}
