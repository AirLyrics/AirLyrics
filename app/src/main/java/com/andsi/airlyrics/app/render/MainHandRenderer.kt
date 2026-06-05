package com.andsi.airlyrics.app.render

import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.createMainView
import com.andsi.airlyrics.app.recreateMainViewForThemeChange
import com.andsi.airlyrics.app.renderCurrentPage

/**
 * Transitional shell around the current hand-written MainActivity UI.
 *
 * This class intentionally delegates to the existing functions for now, so
 * page layout, animation timings, spacing, and the final easter egg stay
 * byte-for-byte under the old renderer's control during this step.
 */
internal class MainHandRenderer(
    private val activity: MainActivity
) : UiInvalidator {

    fun createMainView(): View {
        return activity.createMainView()
    }

    override fun refresh(
        animateContent: Boolean,
        animateTabs: Boolean
    ) {
        activity.renderCurrentPage(
            animateContent = animateContent,
            animateTabs = animateTabs
        )
    }

    override fun refreshFloatingState() {
        activity.renderCurrentPage(
            animateContent = false,
            animateTabs = true
        )
    }

    override fun recreateForThemeChange() {
        activity.recreateMainViewForThemeChange()
    }
}
