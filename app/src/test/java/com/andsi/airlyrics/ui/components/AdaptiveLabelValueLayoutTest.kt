package com.andsi.airlyrics.ui.components

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.design.tokens.AirUiTokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class AdaptiveLabelValueLayoutTest {
    private var activityController: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
    }

    @Test
    fun longValueStacksWithoutShrinkingOrCoveringLabel() {
        val host = createHost()
        val label = "Plain lyrics"
        val value = "Hello / How are you (cover by Hatsune Miku) - a very long artist name ".repeat(6)
        val row = settingRow(host, label, value) as AdaptiveLabelValueLayout

        measureAndLayout(row, host.dp(280))

        assertTrue(
            "label=${row.labelView.measuredWidth}, value=${row.valueView.measuredWidth}, row=${row.width}",
            row.isStacked
        )
        assertEquals(label, (row.labelView as TextView).text.toString())
        assertTrue(row.labelView.measuredWidth > 0)
        assertTrue(row.labelView.bottom <= row.valueView.top)
        assertContainedHorizontally(row, row.labelView)
        assertContainedHorizontally(row, row.valueView)
    }

    @Test
    fun shortValueKeepsCompactHorizontalLayout() {
        val host = createHost()
        val row = settingRow(host, "Current offset", "No offset") as AdaptiveLabelValueLayout

        measureAndLayout(row, host.dp(280))

        assertFalse(row.isStacked)
        assertTrue(row.labelView.right < row.valueView.left)
        assertTrue(row.labelView.top < row.valueView.bottom)
        assertTrue(row.valueView.top < row.labelView.bottom)
    }

    @Test
    fun longLocalizedStatusStacksAndKeepsTrailingActionVisible() {
        val host = createHost()
        val trailingAction = View(host).apply {
            layoutParams = ViewGroup.LayoutParams(
                host.dp(AirUiTokens.Layout.CompactIconButtonSize),
                host.dp(AirUiTokens.Layout.CompactIconButtonSize)
            )
        }
        val row = settingRow(
            activity = host,
            name = "Word-by-word lyrics",
            value = "Available · local word-by-word lyrics ".repeat(6),
            trailingView = trailingAction
        ) as AdaptiveLabelValueLayout

        measureAndLayout(row, host.dp(280))

        assertTrue(
            "label=${row.labelView.measuredWidth}, value=${row.valueView.measuredWidth}, " +
                "action=${trailingAction.measuredWidth}, row=${row.width}",
            row.isStacked
        )
        assertTrue(row.labelView.bottom <= row.valueView.top)
        assertTrue(row.valueView.right < trailingAction.left)
        assertContainedHorizontally(row, row.labelView)
        assertContainedHorizontally(row, row.valueView)
        assertContainedHorizontally(row, trailingAction)
    }

    @Test
    fun wordByWordEditorTitleKeepsCategoryBeforeTruncatedSongTitle() {
        val host = createHost()
        val songTitle = "A song title long enough to be truncated by the dialog header"

        val title = host.getString(R.string.ui_item_title_word_by_word_lyrics, songTitle)

        assertTrue(title.endsWith(songTitle))
        assertTrue(title.indexOf(songTitle) > 0)
    }

    @Test
    fun stackedRtlLayoutKeepsValueAndActionWithinBounds() {
        val host = createHost()
        val trailingAction = View(host).apply {
            layoutParams = ViewGroup.LayoutParams(host.dp(32), host.dp(32))
        }
        val row = settingRow(
            activity = host,
            name = "Word-by-word lyrics",
            value = "Available · local word-by-word lyrics ".repeat(6),
            trailingView = trailingAction
        ) as AdaptiveLabelValueLayout
        row.layoutDirection = View.LAYOUT_DIRECTION_RTL

        measureAndLayout(row, host.dp(280))

        assertTrue(row.isStacked)
        assertTrue(trailingAction.right < row.valueView.left)
        assertContainedHorizontally(row, row.labelView)
        assertContainedHorizontally(row, row.valueView)
        assertContainedHorizontally(row, trailingAction)
    }

    private fun createHost() = Robolectric.buildActivity(MainActivity::class.java)
        .setup()
        .also { activityController = it }
        .get()
        .graph
        .uiHost

    private fun measureAndLayout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun assertContainedHorizontally(parent: View, child: View) {
        assertTrue(child.left >= parent.paddingLeft)
        assertTrue(child.right <= parent.width - parent.paddingRight)
    }
}
