package com.andsi.airlyrics.app.host

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class MainFloatingPanelUiHostImplTest {
    private var activityController: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun settingGrid_expandsTilesAndKeepsRowsEvenAtLargeFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
        val host = activity.graph.uiHost
        val subtitles = mutableListOf<TextView>()
        val grid = host.settingGrid(
            tile("Background bubble", "On", subtitles),
            tile("Font size", "18 sp", subtitles),
            tile("A setting title that wraps onto multiple lines", "System default", subtitles)
        )

        val width = host.dp(400)
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        grid.layout(0, 0, width, grid.measuredHeight)

        val firstRow = grid.getChildAt(0) as LinearLayout
        val secondRow = grid.getChildAt(1) as LinearLayout
        val firstTile = firstRow.getChildAt(0) as LinearLayout
        val secondTile = firstRow.getChildAt(1) as LinearLayout
        val thirdTile = secondRow.getChildAt(0) as LinearLayout
        val filler = secondRow.getChildAt(1)
        val minimumHeight = host.dp(AirUiTokens.Layout.FloatingTileMinHeight)

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, firstTile.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, filler.layoutParams.height)
        assertEquals(minimumHeight, firstTile.minimumHeight)
        assertEquals(firstTile.measuredHeight, secondTile.measuredHeight)
        assertTrue(firstTile.measuredHeight > minimumHeight)
        assertTrue(thirdTile.measuredHeight > minimumHeight)

        subtitles.forEach { subtitle ->
            val tile = subtitle.parent as LinearLayout
            val requiredTextHeight = subtitle.compoundPaddingTop +
                subtitle.layout.height +
                subtitle.compoundPaddingBottom
            assertTrue(subtitle.measuredHeight >= requiredTextHeight)
            assertTrue(subtitle.bottom <= tile.height - tile.paddingBottom)
        }
    }

    private fun tile(
        title: String,
        subtitle: String,
        subtitles: MutableList<TextView>
    ): FloatingSettingTile {
        return FloatingSettingTile(
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_air_chat_bubble,
            onClick = {},
            onSubtitleViewCreated = subtitles::add
        )
    }
}
