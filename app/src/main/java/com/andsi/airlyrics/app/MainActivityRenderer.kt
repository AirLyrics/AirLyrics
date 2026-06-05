package com.andsi.airlyrics.app

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.andsi.airlyrics.ui.components.animatePageEnter
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.createBottomTabs
import com.andsi.airlyrics.ui.navigation.updateTabs
import com.andsi.airlyrics.ui.pages.createFloatingPage
import com.andsi.airlyrics.ui.pages.createMediaPage
import com.andsi.airlyrics.ui.pages.createSettingsPage
import com.andsi.airlyrics.ui.theme.colorBackground
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal fun MainActivity.createMainView(): View {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(uiHost.colorBackground)
    }

    val topSafeArea = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(AirUiTokens.Layout.TopSafeAreaHeight)
        )
    }

    contentContainer = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    }

    root.addView(topSafeArea)
    root.addView(contentContainer)
    root.addView(createBottomTabs(uiHost))
    return root
}

internal fun MainActivity.renderCurrentPage(animateContent: Boolean = true, animateTabs: Boolean = true) {
    val container = contentContainer ?: return
    (container.getChildAt(0) as? ScrollView)?.let { scrollView ->
        pageScrollY[renderedPage] = scrollView.scrollY
    }

    val oldPage = renderedPage
    val oldSubPage = renderedSettingsSubPage
    val shouldAnimate = animateContent && container.childCount > 0 && (currentPage != oldPage || settingsSubPage != oldSubPage)
    val slideFromRight = when {
        currentPage != oldPage -> currentPage.ordinal > oldPage.ordinal
        currentPage == Page.SETTINGS -> settingsSubPage.ordinal > oldSubPage.ordinal
        else -> true
    }

    container.removeAllViews()
    updateTabs(uiHost, animate = animateTabs)
    if (currentPage != Page.FLOATING) {
        floatingPanelBackHandler = null
    }

    val pageView = when (currentPage) {
        Page.MEDIA -> createMediaPage(uiHost, animateContent = animateContent)
        Page.FLOATING -> createFloatingPage(uiHost)
        Page.SETTINGS -> createSettingsPage(uiHost)
    }

    val restoreY = pageScrollY[currentPage] ?: 0
    container.addView(pageView)
    if (shouldAnimate) animatePageEnter(uiHost, pageView, slideFromRight)
    renderedPage = currentPage
    renderedSettingsSubPage = settingsSubPage

    (pageView as? ScrollView)?.let { scrollView ->
        scrollView.scrollTo(0, restoreY)
        scrollView.post {
            scrollView.scrollTo(0, restoreY)
        }
    }
}


internal fun MainActivity.recreateMainViewForThemeChange() {
    setContentView(createMainView())
    renderCurrentPage()
}
