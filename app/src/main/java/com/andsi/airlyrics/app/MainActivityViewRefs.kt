package com.andsi.airlyrics.app

import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

/**
 * View references owned by [MainActivity].
 *
 * Keeping them grouped separately makes MainActivity less like a drawer full of
 * unrelated mutable fields, without changing the existing UI flow.
 */
internal class MainActivityViewRefs {
    var contentContainer: FrameLayout? = null
    val tabViews: MutableMap<Page, TextView> = mutableMapOf()
    var tabRow: LinearLayout? = null
    var tabHighlight: WaterTabHighlightView? = null
    var floatingPanelBackHandler: (() -> Boolean)? = null
}
