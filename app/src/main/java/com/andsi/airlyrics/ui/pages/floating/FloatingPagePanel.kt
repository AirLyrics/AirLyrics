package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.findInteractionScroll
import android.view.View
import android.view.ViewGroup
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.components.endFloatingPanelChanges
import android.widget.LinearLayout

internal class FloatingPanelNavigation {
    private var title: String? = null
    private var back: (() -> Unit)? = null
    private var updateHeader: ((String?, (() -> Unit)?) -> Unit)? = null
    private var headerAction: View? = null
    private var hideReset = false
    private var updateAction: ((View?, Boolean) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    fun showPage(title: String? = null, onBack: (() -> Unit)? = null) {
        this.title = title
        back = onBack
        updateHeader?.invoke(title, onBack)
    }

    fun setHeaderAction(action: View?, hideReset: Boolean = false) {
        headerAction = action
        this.hideReset = hideReset
        updateAction?.invoke(action, hideReset)
    }

    fun bind(
        update: (String?, (() -> Unit)?) -> Unit,
        updateAction: (View?, Boolean) -> Unit
    ) {
        updateHeader = update
        this.updateAction = updateAction
        update(title, back)
        updateAction(headerAction, hideReset)
    }

    fun close() { onClose?.invoke() }

    fun navigateBack(): Boolean {
        val action = back ?: return false
        action()
        return true
    }
}

internal fun FloatingPageScope.openPanel(
    anchor: View,
    title: String,
    subtitle: String,
    content: LinearLayout.() -> Unit
): (() -> Unit)? = openFloatingPanel(anchor, title, subtitle, content = { content() })

internal fun FloatingPageScope.openStylePanel(
    anchor: View,
    title: String,
    navigation: FloatingPanelNavigation? = null,
    restoreDefaults: (FloatingLyricsStyle, FloatingLyricsStyle) -> FloatingLyricsStyle,
    content: LinearLayout.(FloatingStylePanelEdits) -> Unit
): (() -> Unit)? = openFloatingPanel(anchor, title, "", navigation, restoreDefaults) { edits ->
    content(requireNotNull(edits))
}

private fun FloatingPageScope.openFloatingPanel(
    anchor: View,
    title: String,
    subtitle: String,
    navigation: FloatingPanelNavigation? = null,
    restoreDefaults: ((FloatingLyricsStyle, FloatingLyricsStyle) -> FloatingLyricsStyle)? = null,
    content: LinearLayout.(FloatingStylePanelEdits?) -> Unit
): (() -> Unit)? {
    val overlay = focusOverlay ?: return null
    val id = openingPanelId ?: return null
    activeStylePanel?.finishMotion()
    activePanelNavigation?.close()
    overlay.animate().cancel()
    overlay.getChildAt(0)?.animate()?.cancel()
    selectedTileView?.takeIf { it !== anchor }?.let { previousTile ->
        previousTile.animate().cancel()
        previousTile.scaleX = 1f
        previousTile.scaleY = 1f
        previousTile.alpha = 1f
    }
    host.interactions.panel = id
    val edits = restoreDefaults?.let { FloatingStylePanelEdits(this, it) }
    selectedTileView = anchor

    val animatePanel = !restoringPanel && ValueAnimator.areAnimatorsEnabled() &&
        (anchor.isInTouchMode || !anchor.hasFocus())
    if (animatePanel) anchor.animate()
        .scaleX(FloatingPageTokens.PANEL_SELECTED_SCALE)
        .scaleY(FloatingPageTokens.PANEL_SELECTED_SCALE)
        .alpha(FloatingPageTokens.PANEL_SELECTED_ALPHA)
        .setDuration(FloatingPageTokens.FAST_ANIMATION_MS)
        .setInterpolator(floatingPanelEaseOut)
        .start()

    val bubbleHandle = host.floatingFocusBubble(
        title, subtitle,
        onReset = edits?.let { it::reset },
        onClose = ::closePanel,
        onUndo = edits?.let { it::undo },
        onInteraction = edits?.let { it::finishMotion }
    ) {
        edits?.beginContent()
        content(edits)
    }
    val bubble = bubbleHandle.view

    fun rebuildContentIfOpen() {
        if (activeBubble === bubble) {
            bubbleHandle.rebuildContent()
            edits?.updateActions()
        }
    }

    activePanelNavigation = navigation
    navigation?.bind(
        update = { pageTitle, onBack ->
            edits?.finishMotion()
            bubbleHandle.updateNavigation(pageTitle, onBack)
        },
        updateAction = bubbleHandle.updateHeaderAction
    )

    overlay.removeAllViews()
    overlay.visibility = View.VISIBLE
    overlay.alpha = 0f
    overlay.setOnClickListener { closePanel() }
    overlay.addView(bubble)
    // Keep focus inside the overlay. Hiding the focused sub-menu otherwise makes Android run a
    // focus search over the whole window, which scrolls the settings page underneath to reveal
    // whatever it lands on.
    overlay.isFocusableInTouchMode = true
    overlay.requestFocus()
    setBackgroundBlockedByPanel(true)
    activeBubble = bubble
    activeStylePanel = edits
    edits?.bindBubble(bubbleHandle)
    bubble.findInteractionScroll()?.let { host.bindInteractionScroll(it, "panel.scroll") }

    bubble.setOnClickListener { }
    bubble.isClickable = true
    if (!animatePanel) {
        overlay.alpha = 1f
        bubble.alpha = 1f
        return ::rebuildContentIfOpen
    }

    bubble.setOnClickListener { }
    bubble.isClickable = true
    bubble.alpha = 0f
    bubble.scaleX = FloatingPageTokens.PANEL_OPEN_START_SCALE
    bubble.scaleY = FloatingPageTokens.PANEL_OPEN_START_SCALE

    overlay.post {
        if (activeBubble !== bubble || host.interactions.panel != id || !overlay.isAttachedToWindow) return@post
        val (startX, startY) = panelSourceOffset(
            anchor, overlay, bubble, FloatingPageTokens.PANEL_OPEN_START_SCALE
        )

        bubble.translationX = startX
        bubble.translationY = startY
        if (!ValueAnimator.areAnimatorsEnabled()) {
            overlay.alpha = 1f
            bubble.alpha = 1f
            bubble.scaleX = 1f
            bubble.scaleY = 1f
            bubble.translationX = 0f
            bubble.translationY = 0f
            return@post
        }
        // Source-linked motion adapted from Motion Prompt #049 · MIT License (c) 2026 Shohei Ohtaki
        // https://showheyohtaki.github.io/motion-prompt/#049
        overlay.animate().alpha(1f)
            .setDuration(FloatingPageTokens.PANEL_OVERLAY_FADE_MS)
            .setInterpolator(floatingPanelEaseOut)
            .start()
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(FloatingPageTokens.PANEL_OPEN_MS)
            .setInterpolator(floatingPanelEaseOut)
            .start()
    }
    return ::rebuildContentIfOpen
}

internal fun FloatingPageScope.installBackHandler() {
    host.floatingPanelBackHandler = back@{
        if (activePanelNavigation?.navigateBack() == true) return@back true
        val isPanelOpen = (activeBubble != null) || (focusOverlay?.visibility == View.VISIBLE)
        if (isPanelOpen) closePanel()
        isPanelOpen
    }
}

private fun FloatingPageScope.closePanel() {
    activeStylePanel?.finishMotion()
    activeStylePanel = null
    activePanelNavigation?.close()
    host.interactions.panel = null
    val overlay = focusOverlay ?: return
    val bubble = activeBubble
    activeBubble = null
    activePanelNavigation = null
    overlay.setOnClickListener { }
    bubble?.endFloatingPanelChanges()
    bubble?.animate()?.cancel()
    overlay.animate().cancel()

    fun finishClose() {
        if (bubble == null || overlay.getChildAt(0) === bubble) {
            overlay.visibility = View.GONE
            overlay.removeAllViews()
            clearContentFocus()
        }
    }

    if (bubble == null || !ValueAnimator.areAnimatorsEnabled() || !overlay.isAttachedToWindow ||
        (!bubble.isInTouchMode && bubble.findFocus() != null)) {
        finishClose()
        return
    }
    val (targetX, targetY) = selectedTileView
        ?.takeIf { it.isAttachedToWindow }
        ?.let { panelSourceOffset(it, overlay, bubble, FloatingPageTokens.PANEL_CLOSE_SCALE) }
        ?: (0f to host.dp(10).toFloat())
    bubble.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    overlay.addView(View(host).apply {
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    bubble.animate()
        .alpha(0f)
        .scaleX(FloatingPageTokens.PANEL_CLOSE_SCALE)
        .scaleY(FloatingPageTokens.PANEL_CLOSE_SCALE)
        .translationX(targetX)
        .translationY(targetY)
        .setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
        .setInterpolator(floatingPanelEaseOut)
        .withEndAction(::finishClose)
        .start()
    overlay.animate().alpha(0f)
        .setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
        .setInterpolator(floatingPanelEaseOut)
        .start()
}

private fun FloatingPageScope.clearContentFocus() {
    setBackgroundBlockedByPanel(false)
    activePanelNavigation = null
    activeDisplayScopePanelRefresh = null
    selectedTileView?.let { tile ->
        tile.animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled() && tile.isAttachedToWindow) {
            tile.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
                .setInterpolator(floatingPanelEaseOut).start()
        } else {
            tile.scaleX = 1f
            tile.scaleY = 1f
            tile.alpha = 1f
        }
        if (tile.isAttachedToWindow && tile.isFocusable) tile.requestFocus()
    }
    selectedTileView = null
}

private fun FloatingPageScope.panelSourceOffset(
    anchor: View,
    overlay: View,
    bubble: View,
    scale: Float
): Pair<Float, Float> {
    val overlayLocation = IntArray(2)
    val anchorLocation = IntArray(2)
    overlay.getLocationOnScreen(overlayLocation)
    anchor.getLocationOnScreen(anchorLocation)
    val dx = anchorLocation[0] + anchor.width / 2f - overlayLocation[0] - overlay.width / 2f
    val dy = anchorLocation[1] + anchor.height / 2f - overlayLocation[1] - overlay.height / 2f
    val maxX = minOf(
        host.dp(FloatingPageTokens.PANEL_SOURCE_OFFSET_X_DP).toFloat(),
        (overlay.width - bubble.width * scale) / 2f - host.dp(4)
    ).coerceAtLeast(0f)
    val maxY = minOf(
        host.dp(FloatingPageTokens.PANEL_SOURCE_OFFSET_Y_DP).toFloat(),
        (overlay.height - bubble.height * scale) / 2f - host.dp(4)
    ).coerceAtLeast(0f)
    return dx.coerceIn(-maxX, maxX) to dy.coerceIn(-maxY, maxY)
}
