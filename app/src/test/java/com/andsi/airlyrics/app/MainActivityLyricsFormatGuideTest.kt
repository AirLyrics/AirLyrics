package com.andsi.airlyrics.app

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.SongIdentity
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class MainActivityLyricsFormatGuideTest {
    @Before
    fun setUp() {
        ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        ShadowDialog.reset()
    }

    @Test
    fun formatGuide_showsLrcAndTtmlOnSeparatePages() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.graph.lyricsWorkflow.showImportLyricsDialog(
                target = SONG,
                plainImportEnabled = true,
                wordByWordImportEnabled = true
            )
            val importDialog = requireNotNull(ShadowDialog.getLatestDialog())
            val guideChoice = requireNotNull(
                importDialog.findTextView {
                    it.isClickable && it.text.toString().contains(
                        activity.getString(R.string.ui_lyrics_format_guide)
                    )
                }
            )

            guideChoice.performClick()
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)

            val guideDialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertNotSame(importDialog, guideDialog)
            assertFalse(
                guideDialog.allText().contains(
                    activity.getString(R.string.ui_lyrics_format_guide)
                )
            )
            val lrcTab = requireNotNull(
                guideDialog.findTextView {
                    it.text.toString() == activity.getString(R.string.ui_lyrics_format_lrc_tab)
                }
            )
            val ttmlTab = requireNotNull(
                guideDialog.findTextView {
                    it.text.toString() == activity.getString(R.string.ui_lyrics_format_ttml_tab)
                }
            )

            assertTrue(lrcTab.isSelected)
            assertFalse(ttmlTab.isSelected)
            assertTrue(guideDialog.allText().contains("[00:12.34]<00:12.34>"))
            assertFalse(guideDialog.allText().contains("<p begin=\"00:12.340\""))

            ttmlTab.performClick()
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

            assertFalse(lrcTab.isSelected)
            assertTrue(ttmlTab.isSelected)
            assertFalse(guideDialog.allText().contains("[00:12.34]<00:12.34>"))
            assertTrue(guideDialog.allText().contains("<p begin=\"00:12.340\""))
        }
    }

    private fun Dialog.findTextView(predicate: (TextView) -> Boolean): TextView? {
        return window?.decorView?.findTextView(predicate)
    }

    private fun View.findTextView(predicate: (TextView) -> Boolean): TextView? {
        if (this is TextView && predicate(this)) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findTextView(predicate)?.let { return it }
        }
        return null
    }

    private fun Dialog.allText(): String {
        return window?.decorView?.allText().orEmpty().joinToString("\n")
    }

    private fun View.allText(): List<String> {
        val ownText = (this as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (this !is ViewGroup) return ownText
        return ownText + (0 until childCount).flatMap { getChildAt(it).allText() }
    }

    private companion object {
        val SONG = SongIdentity(
            title = "Format guide",
            artist = "AirLyrics",
            album = "",
            durationMs = 120_000L
        )
    }
}
