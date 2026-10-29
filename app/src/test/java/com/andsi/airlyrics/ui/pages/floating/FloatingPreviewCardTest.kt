package com.andsi.airlyrics.ui.pages.floating

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.colorTextMuted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class FloatingPreviewCardTest {
    private var activityController: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun compactPreviewRetainsStyledFullTextAndDoesNotChangeSavedExpansion() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
            .also { activityController = it }.get()
        val host = activity.graph.uiHost
        host.windowLayout = com.andsi.airlyrics.ui.layout.WindowLayoutSpec(640f, 300f, 1f)
        var writes = 0
        val full = android.text.SpannableString("Original\nTranslation").apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.RED), 0, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val handle = host.createFloatingPreviewCard(
            isExpanded = { true }, setExpanded = { writes++ }, style = { host.floatingStyle() },
            isWordByWordLyricsEnabled = { false }, plainPreviewText = { full }, wordByWordPreviewText = { full }
        )
        fun measure() {
            handle.cardView.measure(android.view.View.MeasureSpec.makeMeasureSpec(500, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        }
        measure()
        assertEquals("Original", handle.lyricTextView.text.toString())
        assertEquals(1, (handle.lyricTextView.text as android.text.Spanned).getSpans(0, 8, android.text.style.ForegroundColorSpan::class.java).size)
        (handle.cardView as LinearLayout).getChildAt(1).performClick()
        measure()
        assertEquals(full.toString(), handle.lyricTextView.text.toString())
        handle.cardView.getChildAt(1).performClick()
        handle.updateText("Updated\nNew translation")
        measure()
        assertEquals("Updated", handle.lyricTextView.text.toString())
        host.windowLayout = host.windowLayout.copy(height = 700f)
        measure()
        assertEquals("Updated\nNew translation", handle.lyricTextView.text.toString())
        assertEquals(0, writes)
    }

    @Test
    fun toggleUsesAppChromeColorWhenPreviewTextIsWhiteInLightTheme() {
        val app = RuntimeEnvironment.getApplication()
        ThemeSettingsStore.setDark(app, false)
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
        val host = activity.graph.uiHost
        val previewStyle = host.floatingStyle().copy(textColor = Color.WHITE)

        val handle = host.createFloatingPreviewCard(
            isExpanded = { true },
            setExpanded = {},
            style = { previewStyle },
            isWordByWordLyricsEnabled = { false },
            plainPreviewText = { "Preview" },
            wordByWordPreviewText = { "Preview" }
        )
        val toggle = (handle.cardView as LinearLayout).getChildAt(1) as TextView

        assertEquals(Color.WHITE, handle.lyricTextView.currentTextColor)
        assertEquals(host.colorTextMuted, toggle.currentTextColor)
        assertNotEquals(previewStyle.textColor, toggle.currentTextColor)
    }

    @Test
    fun expandedPreviewKeepsTranslationVisibleAfterMultilineOriginal() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
            .also { activityController = it }.get()
        val host = activity.graph.uiHost
        host.windowLayout = com.andsi.airlyrics.ui.layout.WindowLayoutSpec(640f, 700f, 1f)
        val full = "これは歌詞プレビューの一行\nです\nThis is a lyric preview"
        val handle = host.createFloatingPreviewCard(
            isExpanded = { true },
            setExpanded = {},
            style = { host.floatingStyle() },
            isWordByWordLyricsEnabled = { false },
            plainPreviewText = { full },
            wordByWordPreviewText = { full }
        )
        val width = android.view.View.MeasureSpec.makeMeasureSpec(180, android.view.View.MeasureSpec.EXACTLY)
        val height = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        handle.cardView.measure(width, height)
        handle.cardView.layout(0, 0, 180, handle.cardView.measuredHeight)

        val textLayout = checkNotNull(handle.lyricTextView.layout)
        val translationLine = textLayout.getLineForOffset(full.indexOf("This is"))
        assertTrue(
            "translationLine=$translationLine lineCount=${textLayout.lineCount} " +
                "viewWidth=${handle.lyricTextView.width} layoutWidth=${textLayout.width} " +
                "text=${handle.lyricTextView.text}",
            translationLine >= 2
        )
        assertEquals(0, textLayout.getEllipsisCount(translationLine))
    }
}
