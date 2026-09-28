package com.andsi.airlyrics.ui.components

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.ui.model.MainUiHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveTextLayoutTest {
    private var controller: ActivityController<MainActivity>? = null

    @After fun tearDown() {
        controller?.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun host(): MainUiHost = Robolectric.buildActivity(MainActivity::class.java).setup()
        .also { controller = it }.get().graph.uiHost

    private fun layout(view: View, width: Int, height: Int = 0) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, if (height == 0) View.MeasureSpec.UNSPECIFIED else View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun gridReflowsWithoutReplacingChildrenAndRestoresColumns() {
        val host = host()
        val grid = AdaptiveGridLayout(host, 2, host.dp(8), host.dp(8))
        val first = TextView(host).apply { text = "Restaurar configuración"; textSize = 18f }
        val second = TextView(host).apply { text = "Cancelar"; textSize = 18f }
        grid.addView(first)
        grid.addView(second)
        layout(grid, host.dp(240))
        assertEquals(1, grid.columns)
        assertTrue(first.bottom <= second.top)
        layout(grid, host.dp(800))
        assertEquals(2, grid.columns)
        assertSame(first, grid.getChildAt(0))
        assertEquals(first.height, second.height)
        grid.layoutDirection = View.LAYOUT_DIRECTION_RTL
        layout(grid, host.dp(800))
        assertTrue(first.left > second.left)
    }

    @Test fun headerKeepsCloseAtTopAndMovesWholeTextActionBelowTitle() {
        val host = host()
        val title = TextView(host).apply { text = "Tamaño de fuente de los subtítulos"; textSize = 24f }
        val action = TextView(host).apply { text = "Restaurar configuración"; textSize = 16f }
        val close = View(host).apply { layoutParams = ViewGroup.LayoutParams(host.dp(40), host.dp(40)) }
        val header = host.adaptiveHeader(title, action, close) as AdaptiveLabelValueLayout
        layout(header, host.dp(260))
        assertTrue(header.isStacked)
        assertTrue(action.top >= title.bottom)
        assertEquals(0, close.top)
        assertEquals(header.width, close.right)
        assertEquals(0, action.left)
        assertTrue(title.right <= close.left)
        assertEquals(0, title.layout.getEllipsisCount(title.lineCount - 1))
    }

    @Test fun fullStatusAndLocalizedLabelsStayReadableAtLargeFontScales() {
        val host = host()
        val labels = listOf("Font settings", "Configuración de subtítulos", "悬浮歌词显示设置", "懸浮歌詞顯示設定", "إعدادات كلمات الأغاني")
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            for (width in listOf(320, 360, 600)) for (label in labels) {
                val row = settingRow(host, label, "Available · local word-by-word lyrics ".repeat(8)) as AdaptiveLabelValueLayout
                layout(row, host.dp(width))
                val value = row.valueView as TextView
                assertTrue(row.isStacked)
                assertTrue(row.labelView.bottom <= value.top)
                assertNull(value.ellipsize)
                assertTrue(value.right <= row.width)
                assertTrue(value.bottom <= row.height)
                assertEquals(0, value.left)
            }
        }
    }

    @Test fun expandingSummaryDoesNotClickParentAndBindingRestoresOnlyMatchingItem() {
        val host = host()
        var parentClicks = 0
        val state = mutableSetOf<String>()
        val text = TextView(host).apply { this.text = "A very long song name / 路径/".repeat(20); textSize = 18f }
        val summary = host.expandableText(text)
        val parent = LinearLayout(host).apply {
            addView(summary)
            setOnClickListener { parentClicks++ }
        }
        summary.bindExpansion(state, "first")
        layout(parent, host.dp(240))
        val collapsedHeight = summary.height
        val action = summary.getChildAt(1)
        assertEquals(View.VISIBLE, action.visibility)
        action.performClick()
        layout(parent, host.dp(240))
        assertTrue(summary.height > collapsedHeight)
        assertEquals(0, parentClicks)
        assertTrue("first" in state)
        summary.bindExpansion(state, "second")
        layout(parent, host.dp(240))
        assertEquals(2, text.maxLines)
        summary.bindExpansion(state, "first")
        layout(parent, host.dp(240))
        assertEquals(Int.MAX_VALUE, text.maxLines)
        text.text = "Short"
        layout(parent, host.dp(240))
        assertEquals(View.GONE, action.visibility)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
