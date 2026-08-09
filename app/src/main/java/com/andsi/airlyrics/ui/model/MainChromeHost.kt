package com.andsi.airlyrics.ui.model

import android.os.Handler
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.refs.FloatingPageRefs
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

internal interface MainChromeHost {
    val tabViews: MutableMap<Page, TextView>
    var tabRow: LinearLayout?
    var tabHighlight: WaterTabHighlightView?
    var floatingPanelBackHandler: (() -> Boolean)?
    var contentContainer: FrameLayout?
    var floatingPageRefs: FloatingPageRefs?
    val mediaRefreshHandler: Handler

    fun rebuildCurrentPage(
        reason: PageRebuildReason,
        animateContent: Boolean = true,
        animateTabs: Boolean = true
    )

    fun rebuildMainView(reason: PageRebuildReason = PageRebuildReason.THEME_CHANGED)
    fun dp(value: Int): Int
    fun isDarkTheme(): Boolean
    fun themeAccent(): ThemeAccent
}
