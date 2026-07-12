package com.andsi.airlyrics.ui.pages.floating

import android.view.View
import com.andsi.airlyrics.ui.model.MainUiHost

internal fun createFloatingPage(activity: MainUiHost): View {
    return FloatingPageScope(activity).createView()
}
