package com.andsi.airlyrics.ui.pages.settings

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceOrder
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourcePriorityTitle
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceTitle
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class LyricsSourceOrderCardTest {
    private lateinit var context: Context
    private var activityController: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        lyricsPreferences().edit().clear().commit()
        QuickFloatingStore.setDesiredVisible(context, false)
    }

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        lyricsPreferences().edit().clear().commit()
        QuickFloatingStore.setDesiredVisible(context, false)
    }

    @Test
    fun toggleOrderedSource_appendsNewSourceAtEnd() {
        assertEquals(
            listOf(PlainLyricsSearchSource.NETEASE, PlainLyricsSearchSource.LRCLIB),
            toggleOrderedPlainLyricsSource(
                selectedSources = listOf(PlainLyricsSearchSource.NETEASE),
                source = PlainLyricsSearchSource.LRCLIB
            )
        )
    }

    @Test
    fun toggleOrderedSource_removesSourceAndPreservesRemainingOrder() {
        assertEquals(
            listOf(PlainLyricsSearchSource.NETEASE, PlainLyricsSearchSource.LRCLIB),
            toggleOrderedPlainLyricsSource(
                selectedSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.MUSIXMATCH,
                    PlainLyricsSearchSource.LRCLIB
                ),
                source = PlainLyricsSearchSource.MUSIXMATCH
            )
        )
    }

    @Test
    fun toggleOrderedSource_keepsLastSelectedSource() {
        val selectedSources = listOf(PlainLyricsSearchSource.LRCLIB)

        assertEquals(
            selectedSources,
            toggleOrderedPlainLyricsSource(
                selectedSources = selectedSources,
                source = PlainLyricsSearchSource.LRCLIB
            )
        )
    }

    @Test
    fun toggleOrderedSource_reselectingMovesSourceToEnd() {
        val withoutFirst = toggleOrderedPlainLyricsSource(
            selectedSources = listOf(
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.MUSIXMATCH
            ),
            source = PlainLyricsSearchSource.NETEASE
        )

        assertEquals(
            listOf(
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.MUSIXMATCH,
                PlainLyricsSearchSource.NETEASE
            ),
            toggleOrderedPlainLyricsSource(
                selectedSources = withoutFirst,
                source = PlainLyricsSearchSource.NETEASE
            )
        )
    }

    @Test
    fun sourceCard_persistsMultipleSourcesRenumbersAndKeepsOne() {
        LyricsSettingsStore.setPlainLyricsSearchSources(
            context,
            listOf(PlainLyricsSearchSource.NETEASE)
        )
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)
        val activity = launchActivity()
        val host = activity.graph.uiHost
        var card = createLyricsSourceOrderCard(host, host.lyricsSettingsState())

        val lrclibButton = card.requireTextView(
            host.localizedPlainLyricsSourceTitle(PlainLyricsSearchSource.LRCLIB)
        )
        assertFalse(lrclibButton.isSelected)
        assertEquals(
            host.getString(R.string.ui_lyrics_source_not_selected_state),
            ViewCompat.getStateDescription(lrclibButton)
        )
        lrclibButton.performClick()
        assertSame(
            lrclibButton,
            card.requireTextView(
                host.localizedPlainLyricsSourcePriorityTitle(
                    PlainLyricsSearchSource.LRCLIB,
                    priority = 2
                )
            )
        )
        assertTrue(lrclibButton.isSelected)
        assertEquals(
            host.getString(R.string.ui_lyrics_source_selected_state),
            ViewCompat.getStateDescription(lrclibButton)
        )
        card.requireTextView(host.localizedPlainLyricsSourceTitle(PlainLyricsSearchSource.MUSIXMATCH))
            .performClick()

        val allSources = listOf(
            PlainLyricsSearchSource.NETEASE,
            PlainLyricsSearchSource.LRCLIB,
            PlainLyricsSearchSource.MUSIXMATCH
        )
        assertEquals(
            allSources,
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertPriorityButton(card, host, PlainLyricsSearchSource.NETEASE, priority = 1)
        assertPriorityButton(card, host, PlainLyricsSearchSource.LRCLIB, priority = 2)
        assertPriorityButton(card, host, PlainLyricsSearchSource.MUSIXMATCH, priority = 3)
        assertTrue(
            card.hasText(
                host.getString(
                    R.string.ui_lyrics_source_order_value,
                    host.localizedPlainLyricsSourceOrder(allSources)
                )
            )
        )
        card = createLyricsSourceOrderCard(host, host.lyricsSettingsState())
        assertPriorityButton(card, host, PlainLyricsSearchSource.NETEASE, priority = 1)
        assertPriorityButton(card, host, PlainLyricsSearchSource.LRCLIB, priority = 2)
        assertPriorityButton(card, host, PlainLyricsSearchSource.MUSIXMATCH, priority = 3)

        val neteaseButton = card.requireTextView(
            host.localizedPlainLyricsSourcePriorityTitle(
                PlainLyricsSearchSource.NETEASE,
                priority = 1
            )
        )
        neteaseButton.performClick()

        assertEquals(
            listOf(PlainLyricsSearchSource.LRCLIB, PlainLyricsSearchSource.MUSIXMATCH),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertSame(
            neteaseButton,
            card.requireTextView(
                host.localizedPlainLyricsSourceTitle(PlainLyricsSearchSource.NETEASE)
            )
        )
        assertFalse(neteaseButton.isSelected)
        assertPriorityButton(card, host, PlainLyricsSearchSource.LRCLIB, priority = 1)
        assertPriorityButton(card, host, PlainLyricsSearchSource.MUSIXMATCH, priority = 2)

        card.requireTextView(
            host.localizedPlainLyricsSourcePriorityTitle(
                PlainLyricsSearchSource.LRCLIB,
                priority = 1
            )
        ).performClick()
        card.requireTextView(
            host.localizedPlainLyricsSourcePriorityTitle(
                PlainLyricsSearchSource.MUSIXMATCH,
                priority = 1
            )
        ).performClick()

        assertEquals(
            listOf(PlainLyricsSearchSource.MUSIXMATCH),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        val minimumFeedback = card.requireTextView(
            host.getString(R.string.ui_keep_one_lyrics_source)
        )
        assertEquals(
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            minimumFeedback.accessibilityLiveRegion
        )
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    private fun launchActivity(): MainActivity {
        return Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
    }

    private fun assertPriorityButton(
        card: View,
        context: Context,
        source: PlainLyricsSearchSource,
        priority: Int
    ) {
        assertTrue(
            card.hasText(
                context.localizedPlainLyricsSourcePriorityTitle(source, priority)
            )
        )
    }

    private fun View.requireTextView(expectedText: String): TextView {
        return findTextView(expectedText)
            ?: error("No TextView found with text: $expectedText")
    }

    private fun View.hasText(expectedText: String): Boolean = findTextView(expectedText) != null

    private fun View.findTextView(expectedText: String): TextView? {
        if (this is TextView && text.toString() == expectedText) return this
        if (this !is ViewGroup) return null
        return (0 until childCount).firstNotNullOfOrNull { index ->
            getChildAt(index).findTextView(expectedText)
        }
    }

    private fun lyricsPreferences() =
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
}
