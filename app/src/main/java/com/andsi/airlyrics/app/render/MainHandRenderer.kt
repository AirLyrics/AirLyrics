package com.andsi.airlyrics.app.render

import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.findInteractionScroll
import com.andsi.airlyrics.ui.state.markInteractionAnchors
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.insets.remainingTopSystemInset
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.updateTabs
import com.andsi.airlyrics.ui.pages.floating.createFloatingPage
import com.andsi.airlyrics.ui.pages.media.createMediaPage
import com.andsi.airlyrics.ui.pages.media.MediaPageBinding
import com.andsi.airlyrics.ui.pages.media.MediaArtworkMotionState
import com.andsi.airlyrics.ui.pages.media.MediaPlayerEntranceMotion
import com.andsi.airlyrics.ui.pages.settings.createSettingsPage
import com.andsi.airlyrics.ui.theme.colorBackground

/** Renderer for the existing handwritten main UI. */
internal class MainHandRenderer(
    private val graph: MainGraph
) : UiInvalidator {
    private var renderedPage: Page = Page.MEDIA
    private val mediaPlayerEntrance = MediaPlayerEntranceMotion()
    private val mediaArtworkMotion = MediaArtworkMotionState()
    private val pageTransition = MainPageTransition()
    private var mediaPageBinding: MediaPageBinding? = null

    private val host
        get() = graph.uiHost
    private val state
        get() = graph.state

    fun createMainView(): View {
        val content = FrameLayout(host)
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = pageTransition.cancel()
        })
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
        pageTransition.cancel(settleIncoming = false)
        graph.beginPageRebuild()
        rememberRenderedPageScroll()

        val oldPage = renderedPage
        val outgoingPage = container.getChildAt(container.childCount - 1)
        val pageNavigation = animateContent && outgoingPage != null && state.currentPage != oldPage
        val shouldAnimate = pageNavigation &&
            container.isAttachedToWindow &&
            ValueAnimator.areAnimatorsEnabled()
        val slideFromRight = (state.currentPage.ordinal > oldPage.ordinal) !=
            (container.layoutDirection == View.LAYOUT_DIRECTION_RTL)

        mediaPageBinding?.finish()
        mediaPageBinding = null
        graph.viewRefs.clearPageRefs()
        if (!shouldAnimate) container.removeAllViews()
        if (state.currentPage != Page.MEDIA) mediaPlayerEntrance.finishActive()
        refreshTabs(animate = animateTabs)
        if (state.currentPage != Page.FLOATING) {
            host.floatingPanelBackHandler = null
        }

        val pageView = when (state.currentPage) {
            Page.MEDIA -> {
                val mediaState = host.mediaPageState()
                if (mediaState.selectedController == null) mediaArtworkMotion.presented(null, null)
                mediaPlayerEntrance.updatePackages(mediaState.controllers.mapTo(mutableSetOf()) { it.packageName })
                createMediaPage(host, animateContent && !pageNavigation, mediaState, mediaPlayerEntrance, mediaArtworkMotion) {
                    mediaPageBinding = it
                }
            }
            Page.FLOATING -> createFloatingPage(host)
            Page.SETTINGS -> createSettingsPage(host, animateHomeChildren = !pageNavigation)
        }

        val scrollKey = "scroll.${state.currentPage.name}.${if (state.currentPage == Page.SETTINGS) state.settingsSubPage.name else "root"}"
        pageView.markInteractionAnchors(scrollKey)
        if (!pageView.hasResponsivePage()) pageView.findInteractionScroll()?.let { host.bindInteractionScroll(it, scrollKey) }
        container.addView(pageView)
        if (shouldAnimate) {
            pageTransition.start(
                container = container,
                outgoing = outgoingPage,
                incoming = pageView,
                fromRight = slideFromRight,
                distance = host.dp(AirUiTokens.Layout.PageEnterDistance).toFloat()
            )
        }
        renderedPage = state.currentPage
    }

    override fun refreshMediaContent() {
        if (state.currentPage != Page.MEDIA) return
        val container = host.contentContainer ?: return
        val latest = host.mediaPageState()
        val binding = mediaPageBinding
        if (renderedPage == Page.MEDIA && binding?.view?.parent === container && binding.update(latest)) {
            return
        }
        rebuildCurrentPage(animateContent = false, animateTabs = false)
    }

    override fun refreshTabs(animate: Boolean) {
        updateTabs(host, animate = animate)
    }

    override fun refreshFloatingChrome(animateQuickStatus: Boolean) {
        updateTabs(host, animate = false, animateQuickStatus = animateQuickStatus)
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
        finishPageTransition()
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

    fun finishPageTransition() {
        pageTransition.cancel()
    }
}

private fun View.hasResponsivePage(): Boolean = this is com.andsi.airlyrics.ui.layout.ResponsivePage ||
    (this is ViewGroup && (0 until childCount).any { getChildAt(it).hasResponsivePage() })
