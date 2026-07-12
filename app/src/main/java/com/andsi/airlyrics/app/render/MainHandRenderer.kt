package com.andsi.airlyrics.app.render

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.ui.components.animatePageEnter
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.createBottomTabs
import com.andsi.airlyrics.ui.navigation.updateTabs
import com.andsi.airlyrics.ui.pages.floating.createFloatingPage
import com.andsi.airlyrics.ui.pages.media.createMediaPage
import com.andsi.airlyrics.ui.pages.settings.createSettingsPage
import com.andsi.airlyrics.ui.theme.colorBackground
import com.andsi.airlyrics.ui.tokens.AirUiTokens

/** Renderer for the existing handwritten main UI. */
internal class MainHandRenderer(
    private val graph: MainGraph
) : UiInvalidator {
    private val host
        get() = graph.uiHost
    private val state
        get() = graph.state

    fun createMainView(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(host.colorBackground)
        }

        val topSafeArea = View(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                host.dp(AirUiTokens.Layout.TopSafeAreaHeight)
            )
        }

        host.contentContainer = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val bottomTabs = createBottomTabs(host)

        root.addView(topSafeArea)
        root.addView(host.contentContainer)
        root.addView(bottomTabs)

        applyBottomNavigationInsets(root, bottomTabs)

        return root
    }

    private fun applyBottomNavigationInsets(
        root: View,
        bottomTabs: View
    ) {
        val baseBottomTabsHeight = host.dp(AirUiTokens.Layout.BottomBarHeight)
        val baseBottomTabsPaddingBottom = bottomTabs.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            bottomTabs.layoutParams = (bottomTabs.layoutParams as LinearLayout.LayoutParams).apply {
                height = baseBottomTabsHeight + navigationBars.bottom
            }

            bottomTabs.setPadding(
                bottomTabs.paddingLeft,
                bottomTabs.paddingTop,
                bottomTabs.paddingRight,
                baseBottomTabsPaddingBottom + navigationBars.bottom
            )

            insets
        }

        root.post {
            ViewCompat.requestApplyInsets(root)
        }
    }

    override fun rebuildCurrentPage(
        reason: PageRebuildReason,
        animateContent: Boolean,
        animateTabs: Boolean
    ) {
        val container = host.contentContainer ?: return
        (container.getChildAt(0) as? ScrollView)?.let { scrollView ->
            state.pageScrollY[state.renderedPage] = scrollView.scrollY
        }

        val oldPage = state.renderedPage
        val oldSubPage = state.renderedSettingsSubPage
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

        val restoreY = state.pageScrollY[state.currentPage] ?: 0
        container.addView(pageView)
        if (shouldAnimate) animatePageEnter(host, pageView, slideFromRight)
        state.renderedPage = state.currentPage
        state.renderedSettingsSubPage = state.settingsSubPage

        (pageView as? ScrollView)?.let { scrollView ->
            scrollView.scrollTo(0, restoreY)
            scrollView.post {
                scrollView.scrollTo(0, restoreY)
            }
        }
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

    override fun recreateForThemeChange() {
        graph.activity.setContentView(createMainView())
        rebuildCurrentPage(PageRebuildReason.THEME_CHANGED)
    }
}
