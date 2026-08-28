package com.andsi.airlyrics.app.render

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.ui.refs.FloatingPageRefs
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

/** View references owned by [MainGraph] for the handwritten main UI. */
internal class MainActivityViewRefs {
    var contentContainer: FrameLayout? = null
    var feedbackAnchor: View? = null
    val tabViews: MutableMap<Page, TextView> = mutableMapOf()
    var tabRow: LinearLayout? = null
    var tabHighlight: WaterTabHighlightView? = null
    var floatingPanelBackHandler: (() -> Boolean)? = null
    var floatingPageRefs: FloatingPageRefs? = null
    var lyricsSettingsContentRefresh: (() -> Unit)? = null

    fun clearPageRefs() {
        floatingPageRefs?.clear()
        floatingPageRefs = null
        lyricsSettingsContentRefresh = null
    }
}
