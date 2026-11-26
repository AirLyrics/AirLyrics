package com.andsi.airlyrics.ui.components

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.theme.colorTextMuted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveLabelValueLayoutInstrumentedTest {
    @Test
    fun productionSettingRowsAdaptToNarrowWidthAtRealDeviceTextMetrics() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = activity.graph.uiHost
                val rowWidth = host.dp(280)
                val song = "Hello / How are you (翻自 初音ミク) - warma · 树莓蛋奶酥"
                val songRow = settingRow(host, host.getString(R.string.ui_plain_lyrics), song) as AdaptiveLabelValueLayout

                measureAndLayout(songRow, rowWidth)

                assertStacked(songRow)
                val songValue = songRow.valueView as TextView
                assertEquals(song, songValue.text.toString())
                assertTrue(songValue.lineCount > 1)

                val iconSize = host.dp(AirUiTokens.Layout.CompactIconButtonSize)
                val infoButton = host.airIconView(
                    iconRes = R.drawable.ic_air_info,
                    tint = host.colorTextMuted,
                    contentDescription = host.getString(R.string.ui_local_word_by_word_lyrics_title)
                ).apply {
                    layoutParams = ViewGroup.LayoutParams(iconSize, iconSize)
                }
                val statusRow = settingRow(
                    activity = host,
                    name = "Word-by-word lyrics",
                    value = "Available · local word-by-word lyrics",
                    trailingView = infoButton
                ) as AdaptiveLabelValueLayout

                measureAndLayout(statusRow, rowWidth)

                assertStacked(statusRow)
                assertContainedHorizontally(statusRow, infoButton)
                assertTrue(infoButton.top >= statusRow.labelView.bottom)
                assertSeparatedHorizontally(statusRow.valueView, infoButton)
                assertEquals(iconSize, infoButton.width)
                assertEquals(iconSize, infoButton.height)

                val shortRow = settingRow(
                    host,
                    host.getString(R.string.ui_current_offset),
                    host.getString(R.string.ui_no_offset)
                ) as AdaptiveLabelValueLayout

                measureAndLayout(shortRow, rowWidth)

                val label = shortRow.labelView
                val value = shortRow.valueView
                assertContainedHorizontally(shortRow, label)
                assertContainedHorizontally(shortRow, value)
                assertTrue(label.top < value.bottom && value.top < label.bottom)
                assertSeparatedHorizontally(label, value)
            }
        }
    }

    private fun assertStacked(row: AdaptiveLabelValueLayout) {
        assertTrue(row.labelView.bottom < row.valueView.top)
        assertContainedHorizontally(row, row.labelView)
        assertContainedHorizontally(row, row.valueView)
    }

    private fun measureAndLayout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun assertContainedHorizontally(parent: View, child: View) {
        assertTrue(child.width > 0)
        assertTrue(child.left >= parent.paddingLeft)
        assertTrue(child.right <= parent.width - parent.paddingRight)
    }

    private fun assertSeparatedHorizontally(first: View, second: View) {
        assertTrue(first.right < second.left || second.right < first.left)
    }
}
