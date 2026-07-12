package com.andsi.airlyrics.ui.refs

import android.widget.TextView

/** Live view references for Floating page fragments that support local refreshes. */
internal class FloatingPageRefs {
    val tileSubtitles: MutableMap<String, TextView> = mutableMapOf()
    var displayControlSubtitle: TextView? = null
    var lockButton: TextView? = null
    var clickThroughButton: TextView? = null

    fun registerTileSubtitle(title: String, view: TextView) {
        tileSubtitles[title] = view
    }

    fun clear() {
        tileSubtitles.clear()
        displayControlSubtitle = null
        lockButton = null
        clickThroughButton = null
    }
}
