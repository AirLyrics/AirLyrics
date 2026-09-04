package com.andsi.airlyrics.app.render

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isNotEmpty
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.ui.components.animatePageEnter
import com.andsi.airlyrics.ui.insets.remainingTopSystemInset
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.createBottomTabs
import com.andsi.airlyrics.ui.navigation.updateTabs
import com.andsi.airlyrics.ui.pages.floating.createFloatingPage
import com.andsi.airlyrics.ui.pages.media.createMediaPage
import com.andsi.airlyrics.ui.pages.settings.createSettingsPage
import com.andsi.airlyrics.ui.theme.colorBackground
import com.andsi.airlyrics.design.tokens.AirUiTokens

/** Renderer for the existing handwritten main UI. */
internal class MainHandRenderer(
    private val graph: MainGraph
) : UiInvalidator {
    private val pageScrollY: MutableMap<Page, Int> = mutableMapOf()
    private var renderedPage: Page = Page.MEDIA
    private var renderedSettingsSubPage = com.andsi.airlyrics.ui.navigation.SettingsSubPage.HOME

    private val host
        get() = graph.uiHost
    private val state
        get() = graph.state

    fun createMainView(): View {
        val contentColumn = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(host.colorBackground)
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topSafeArea = View(host).apply {
            setBackgroundColor(host.colorBackground)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            )
        }

        host.contentContainer = FrameLayout(host).apply {
            setBackgroundColor(host.colorBackground)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val bottomTabs = createBottomTabs(host)
        graph.viewRefs.feedbackAnchor = bottomTabs

        contentColumn.addView(topSafeArea)
        contentColumn.addView(host.contentContainer)
        contentColumn.addView(bottomTabs)

        val root = CoordinatorLayout(host).apply {
            setBackgroundColor(host.colorBackground)
            addView(contentColumn)
        }

        applySystemBarInsets(root, topSafeArea, bottomTabs)

        return root
    }

    private fun applySystemBarInsets(
        root: View,
        topSafeArea: View,
        bottomTabs: View
    ) {
        val baseBottomTabsHeight = host.dp(AirUiTokens.Layout.BottomBarHeight)
        val baseBottomTabsPaddingLeft = bottomTabs.paddingLeft
        val baseBottomTabsPaddingTop = bottomTabs.paddingTop
        val baseBottomTabsPaddingRight = bottomTabs.paddingRight
        val baseBottomTabsPaddingBottom = bottomTabs.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val topInset = view.remainingTopSystemInset(safeInsets.top)

            topSafeArea.layoutParams = (topSafeArea.layoutParams as LinearLayout.LayoutParams).apply {
                height = topInset
            }

            host.contentContainer?.setPadding(
                safeInsets.left,
                0,
                safeInsets.right,
                0
            )

            bottomTabs.layoutParams = (bottomTabs.layoutParams as LinearLayout.LayoutParams).apply {
                height = baseBottomTabsHeight + safeInsets.bottom
            }

            bottomTabs.setPadding(
                baseBottomTabsPaddingLeft + safeInsets.left,
                baseBottomTabsPaddingTop,
                baseBottomTabsPaddingRight + safeInsets.right,
                baseBottomTabsPaddingBottom + safeInsets.bottom
            )

            insets
        }

        root.post {
            ViewCompat.requestApplyInsets(root)
        }
    }

    override fun rebuildCurrentPage(
        animateContent: Boolean,
        animateTabs: Boolean
    ) {
        val container = host.contentContainer ?: return
        graph.beginPageRebuild()
        rememberRenderedPageScroll(container)

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

        val restoreY = if (
            state.currentPage == Page.SETTINGS &&
            state.settingsSubPage != oldSubPage
        ) {
            0
        } else {
            pageScrollY[state.currentPage] ?: 0
        }
        container.addView(pageView)
        if (shouldAnimate) animatePageEnter(host, pageView, slideFromRight)
        renderedPage = state.currentPage
        renderedSettingsSubPage = state.settingsSubPage

        pageView.findPageScrollView()?.let { scrollView ->
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

    override fun refreshFloatingDisplayScope() {
        graph.viewRefs.floatingPageRefs?.refreshDisplayScopeControls?.invoke()
    }

    override fun refreshLyricsSettingsContent() {
        host.lyricsSettingsContentRefresh?.invoke()
    }

    override fun recreateMainView() {
        rememberRenderedPageScroll(host.contentContainer)
        graph.feedback.dismiss()
        graph.uiHost.applySystemBarsTheme()
        graph.activity.setContentView(createMainView())
        graph.uiHost.applySystemBarsTheme()
        rebuildCurrentPage()
    }

    private fun rememberRenderedPageScroll(container: FrameLayout?) {
        container?.getChildAt(0)?.findPageScrollView()?.let { scrollView ->
            pageScrollY[renderedPage] = scrollView.scrollY
        }
    }

    private fun View.findPageScrollView(): ScrollView? {
        if (this is ScrollView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findPageScrollView()?.let { return it }
        }
        return null
    }
}
