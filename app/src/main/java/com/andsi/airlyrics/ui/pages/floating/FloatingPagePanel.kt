package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.os.Bundle
import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.findInteractionScroll
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout

internal data class FloatingPanelReset(
    val isAtDefault: () -> Boolean,
    val reset: () -> Bundle,
    val undo: (Bundle) -> Unit
)

internal fun FloatingPageScope.openPanel(
    anchor: View,
    title: String,
    subtitle: String,
    reset: FloatingPanelReset? = null,
    content: LinearLayout.() -> Unit
): (() -> Unit)? {
    val overlay = focusOverlay ?: return null
    val id = openingPanelId ?: return null
    overlay.animate().cancel()
    overlay.getChildAt(0)?.animate()?.cancel()
    selectedTileView?.takeIf { it !== anchor }?.let { previousTile ->
        previousTile.animate().cancel()
        previousTile.scaleX = 1f
        previousTile.scaleY = 1f
        previousTile.alpha = 1f
    }
    host.interactions.panel = id
    selectedTileView = anchor

    if (!restoringPanel) anchor.animate()
        .scaleX(FloatingPageTokens.PANEL_SELECTED_SCALE)
        .scaleY(FloatingPageTokens.PANEL_SELECTED_SCALE)
        .alpha(FloatingPageTokens.PANEL_SELECTED_ALPHA)
        .setDuration(FloatingPageTokens.FAST_ANIMATION_MS)
        .setInterpolator(DecelerateInterpolator())
        .start()

    lateinit var bubbleHandle: com.andsi.airlyrics.ui.model.FloatingFocusBubbleHandle
    var pendingUndo = host.interactions.read("panel")?.getBundle("undo")

    fun rebuildContentIfOpen() {
        if (activeBubble === bubbleHandle.view) {
            bubbleHandle.rebuildContent()
        }
    }

    fun performResetOrUndo() {
        val resetAction = reset ?: return
        pendingUndo?.let { undo ->
            pendingUndo = null
            host.interactions.write("panel") { remove("undo") }
            host.interactions.removePrefix("panel.control.")
            resetAction.undo(undo)
            rebuildContentIfOpen()
            updateActivePanelResetState()
            return
        }
        if (resetAction.isAtDefault()) return

        pendingUndo = resetAction.reset()
        host.interactions.write("panel") { putBundle("undo", pendingUndo) }
        host.interactions.removePrefix("panel.control.")
        rebuildContentIfOpen()
        updateActivePanelResetState()
    }

    val onReset = if (reset == null) null else ::performResetOrUndo
    bubbleHandle = host.floatingFocusBubble(title, subtitle, onReset, ::closePanel) {
        host.beginPanelControls()
        content()
    }
    val bubble = bubbleHandle.view

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
    activeBubble = bubble
    activePanelResetStateUpdater = reset?.let { resetAction ->
        {
            val undoAvailable = pendingUndo != null
            bubbleHandle.updateResetAction(
                undoAvailable || !resetAction.isAtDefault(),
                undoAvailable
            )
        }
    }
    updateActivePanelResetState()
    bubble.findInteractionScroll()?.let { host.bindInteractionScroll(it, "panel.scroll") }

    bubble.setOnClickListener { /* absorb clicks inside the restored panel, too */ }
    bubble.isClickable = true
    if (restoringPanel) {
        overlay.alpha = 1f
        bubble.alpha = 1f
        return ::rebuildContentIfOpen
    }

    bubble.setOnClickListener { /* keep clicks inside the bubble */ }
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
        // Motion Prompt #049 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#049
        // The scrim settles before the source-linked panel finishes.
        overlay.animate().alpha(1f)
            .setDuration(FloatingPageTokens.PANEL_OVERLAY_FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(FloatingPageTokens.PANEL_OPEN_MS)
            .setInterpolator(OvershootInterpolator(FloatingPageTokens.PANEL_OPEN_OVERSHOOT_TENSION))
            .start()
    }
    return ::rebuildContentIfOpen
}

internal fun FloatingPageScope.installBackHandler() {
    host.floatingPanelBackHandler = {
        if (activeBubble != null || focusOverlay?.visibility == View.VISIBLE) {
            closePanel()
            true
        } else {
            false
        }
    }
}

private fun FloatingPageScope.closePanel() {
    host.interactions.panel = null
    val overlay = focusOverlay ?: return
    val bubble = activeBubble
    activeBubble = null
    overlay.setOnClickListener { /* absorb touches until the panel is removed */ }
    bubble?.animate()?.cancel()
    overlay.animate().cancel()

    fun finishClose() {
        if (bubble == null || overlay.getChildAt(0) === bubble) {
            overlay.visibility = View.GONE
            overlay.removeAllViews()
            clearContentFocus()
        }
    }

    if (bubble == null || !ValueAnimator.areAnimatorsEnabled() || !overlay.isAttachedToWindow) {
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
        .setInterpolator(AccelerateInterpolator())
        .withEndAction(::finishClose)
        .start()
    overlay.animate().alpha(0f)
        .setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
        .start()
}

private fun FloatingPageScope.clearContentFocus() {
    activePanelResetStateUpdater = null
    activeDisplayScopePanelRefresh = null
    selectedTileView?.let { tile ->
        tile.animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled() && tile.isAttachedToWindow) {
            tile.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(FloatingPageTokens.PANEL_CLOSE_MS).start()
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
