package com.andsi.airlyrics.app.render

import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.findInteractionScroll
import com.andsi.airlyrics.ui.state.markInteractionAnchors
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.ui.components.animatePageEnter
import com.andsi.airlyrics.ui.insets.remainingTopSystemInset
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.updateTabs
import com.andsi.airlyrics.ui.pages.floating.createFloatingPage
import com.andsi.airlyrics.ui.pages.media.createMediaPage
import com.andsi.airlyrics.ui.pages.settings.createSettingsPage
import com.andsi.airlyrics.ui.theme.colorBackground

/** Renderer for the existing handwritten main UI. */
internal class MainHandRenderer(
    private val graph: MainGraph
) : UiInvalidator {
    private var renderedPage: Page = Page.MEDIA
    private var renderedSettingsSubPage = com.andsi.airlyrics.ui.navigation.SettingsSubPage.HOME

    private val host
        get() = graph.uiHost
    private val state
        get() = graph.state

    fun createMainView(): View {
        val content = FrameLayout(host)
        host.contentContainer = content
        graph.viewRefs.feedbackAnchor = content
        return com.andsi.airlyrics.ui.layout.AdaptiveWindowLayout(host, content).apply {
            setBackgroundColor(host.colorBackground)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left, view.remainingTopSystemInset(safe.top), safe.right, maxOf(safe.bottom, ime.bottom))
                insets
            }
            post { ViewCompat.requestApplyInsets(this) }
        }
    }

    override fun rebuildCurrentPage(
        animateContent: Boolean,
        animateTabs: Boolean
    ) {
        val container = host.contentContainer ?: return
        graph.beginPageRebuild()
        rememberRenderedPageScroll()

        val oldPage = renderedPage
        val oldSubPage = renderedSettingsSubPage
        val shouldAnimate = animateContent &&
            container.isNotEmpty() &&
            (state.currentPage != oldPage || state.settingsSubPage != oldSubPage)
        val slideFromRight = when {
            state.currentPage != oldPage -> state.currentPage.ordinal > oldPage.ordinal
            state.currentPage == Page.SETTINGS -> state.settingsSubPage.ordinal > oldSubPage.ordinal
            else -> true
        }

        graph.viewRefs.clearPageRefs()
        container.removeAllViews()
        refreshTabs(animate = animateTabs)
        if (state.currentPage != Page.FLOATING) {
            host.floatingPanelBackHandler = null
        }

        val pageView = when (state.currentPage) {
            Page.MEDIA -> createMediaPage(host, animateContent = animateContent)
            Page.FLOATING -> createFloatingPage(host)
            Page.SETTINGS -> createSettingsPage(host)
        }

        val scrollKey = "scroll.${state.currentPage.name}.${if (state.currentPage == Page.SETTINGS) state.settingsSubPage.name else "root"}"
        pageView.markInteractionAnchors(scrollKey)
        if (!pageView.hasResponsivePage()) pageView.findInteractionScroll()?.let { host.bindInteractionScroll(it, scrollKey) }
        container.addView(pageView)
        if (shouldAnimate) animatePageEnter(host, pageView, slideFromRight)
        renderedPage = state.currentPage
        renderedSettingsSubPage = state.settingsSubPage

    }

    override fun refreshTabs(animate: Boolean) {
        updateTabs(host, animate = animate)
    }

    override fun refreshFloatingChrome() {
        refreshTabs(animate = true)
        refreshFloatingControls()
    }

    override fun refreshFloatingControls() {
        val refs = graph.viewRefs.floatingPageRefs ?: return
        refs.displayControlSubtitle?.text = host.floatingDisplaySummary()
        refs.lockButton?.text = host.floatingLockButtonText()
        refs.clickThroughButton?.text = host.floatingClickThroughButtonText()
    }

    override fun refreshFloatingDisplayScope() {
        graph.viewRefs.floatingPageRefs?.refreshDisplayScopeControls?.invoke()
    }

    override fun refreshLyricsSettingsContent() {
        host.lyricsSettingsContentRefresh?.invoke()
    }

    override fun recreateMainView() {
        rememberRenderedPageScroll()
        graph.feedback.dismiss()
        host.auxiliaryRestoreJob?.cancel()
        host.interactionUi.releaseWindows()
        host.auxiliaryDialogs.clear()
        host.activeConfirmationId = null
        graph.uiHost.applySystemBarsTheme()
        graph.activity.setContentView(createMainView())
        graph.uiHost.applySystemBarsTheme()
        rebuildCurrentPage()
        host.windowGeneration.value += 1L
        graph.restoreInteractionWindows()
    }

    private fun rememberRenderedPageScroll() {
        host.interactionUi.capture()
    }
}

private fun View.hasResponsivePage(): Boolean = this is com.andsi.airlyrics.ui.layout.ResponsivePage ||
    (this is ViewGroup && (0 until childCount).any { getChildAt(it).hasResponsivePage() })
