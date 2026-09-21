package com.andsi.airlyrics.ui.components

import android.content.Context
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveLabelValueLayoutInstrumentedTest {
    @Test
    fun longSongAndStatusPreserveLabelsAtRealDeviceTextMetrics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(
                instrumentation.targetContext,
                R.style.Theme_AirLyrics
            )
            verifyRows(context)
        }
    }

    private fun verifyRows(context: Context) {
        val rowWidth = context.dp(280)
        val songRow = createRow(
            context = context,
            name = context.getString(R.string.ui_plain_lyrics),
            value = "Hello / How are you (翻自 初音ミク) - warma · 树莓蛋奶酥"
        )

        measureAndLayout(songRow, rowWidth)

        assertTrue(songRow.isStacked)
        assertTrue(songRow.labelView.measuredWidth > 0)
        assertTrue(songRow.labelView.bottom <= songRow.valueView.top)
        assertContainedHorizontally(songRow, songRow.labelView)
        assertContainedHorizontally(songRow, songRow.valueView)

        val infoAction = View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                context.dp(AirUiTokens.Layout.CompactIconButtonSize),
                context.dp(AirUiTokens.Layout.CompactIconButtonSize)
            )
        }
        val statusRow = createRow(
            context = context,
            name = "Word-by-word lyrics",
            value = "Available · local word-by-word lyrics",
            trailingView = infoAction
        )

        measureAndLayout(statusRow, rowWidth)

        assertTrue(statusRow.isStacked)
        assertTrue(statusRow.labelView.bottom <= statusRow.valueView.top)
        assertContainedHorizontally(statusRow, statusRow.labelView)
        assertContainedHorizontally(statusRow, statusRow.valueView)
        assertContainedHorizontally(statusRow, infoAction)

        val shortRow = createRow(
            context = context,
            name = context.getString(R.string.ui_current_offset),
            value = context.getString(R.string.ui_no_offset)
        )

        measureAndLayout(shortRow, rowWidth)

        assertFalse(shortRow.isStacked)
        assertTrue(shortRow.labelView.right < shortRow.valueView.left)
    }

    private fun createRow(
        context: Context,
        name: String,
        value: String,
        trailingView: View? = null
    ): AdaptiveLabelValueLayout {
        val labelView = TextView(context).apply {
            text = name
            textSize = AirUiTokens.TextSize.Button
        }
        val valueView = TextView(context).apply {
            text = value
            textSize = AirUiTokens.TextSize.BodySmall
            gravity = Gravity.END
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        return AdaptiveLabelValueLayout(
            context = context,
            labelView = labelView,
            valueView = valueView,
            trailingView = trailingView,
            horizontalGapPx = context.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
            verticalGapPx = context.dp(AirUiTokens.Space.Sm),
            trailingGapPx = context.dp(AirUiTokens.Space.Xl)
        ).apply {
            setPadding(0, context.dp(AirUiTokens.Space.Xxl), 0, context.dp(AirUiTokens.Space.Sm))
        }
    }

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

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
