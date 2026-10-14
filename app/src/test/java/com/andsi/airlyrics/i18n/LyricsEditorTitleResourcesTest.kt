package com.andsi.airlyrics.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsEditorTitleResourcesTest {
    @Test
    fun categoryPrecedesUnmodifiedSongNameInEveryBundledLocale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val song = "A long song / 歌曲 %s"
        for (tag in listOf("en", "es", "zh-CN", "zh-TW")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val localized = context.createConfigurationContext(configuration)
            val title = localized.getString(R.string.ui_item_title_word_by_word_lyrics, song)
            assertTrue("$tag must preserve the song name at the end", title.endsWith(song))
            assertTrue("$tag must have a category before the song", title.removeSuffix(song).isNotBlank())
        }
    }
}
