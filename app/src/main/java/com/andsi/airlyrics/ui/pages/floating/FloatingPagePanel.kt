package com.andsi.airlyrics.ui.pages.floating

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout

internal data class FloatingPanelReset(
    val isAtDefault: () -> Boolean,
    val reset: () -> (() -> Unit)
)

internal fun FloatingPageScope.openPanel(
    anchor: View,
    title: String,
    subtitle: String,
    reset: FloatingPanelReset? = null,
    content: LinearLayout.() -> Unit
) {
    val overlay = focusOverlay ?: return
    selectedTileView = anchor

    anchor.animate()
        .scaleX(FloatingPageTokens.PANEL_SELECTED_SCALE)
        .scaleY(FloatingPageTokens.PANEL_SELECTED_SCALE)
        .alpha(FloatingPageTokens.PANEL_SELECTED_ALPHA)
        .setDuration(FloatingPageTokens.FAST_ANIMATION_MS)
        .setInterpolator(DecelerateInterpolator())
        .start()

    lateinit var bubbleHandle: com.andsi.airlyrics.ui.model.FloatingFocusBubbleHandle
    var pendingUndo: (() -> Unit)? = null

    fun rebuildContentIfOpen() {
        if (activeBubble === bubbleHandle.view) {
            bubbleHandle.rebuildContent()
        }
    }

    fun performResetOrUndo() {
        val resetAction = reset ?: return
        pendingUndo?.let { undo ->
            pendingUndo = null
            undo()
            rebuildContentIfOpen()
            updateActivePanelResetState()
            return
        }
        if (resetAction.isAtDefault()) return

        pendingUndo = resetAction.reset()
        rebuildContentIfOpen()
        updateActivePanelResetState()
    }

    val onReset = if (reset == null) null else ::performResetOrUndo
    bubbleHandle = host.floatingFocusBubble(title, subtitle, onReset, ::closePanel) {
        content()
    }
    val bubble = bubbleHandle.view

    overlay.removeAllViews()
    overlay.visibility = View.VISIBLE
    overlay.alpha = 0f
    overlay.setOnClickListener { closePanel() }
    overlay.addView(bubble)
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

    bubble.setOnClickListener { /* keep clicks inside the bubble */ }
    bubble.isClickable = true
    bubble.alpha = 0f
    bubble.scaleX = FloatingPageTokens.PANEL_OPEN_START_SCALE
    bubble.scaleY = FloatingPageTokens.PANEL_OPEN_START_SCALE

    overlay.post {
        val overlayCenter = IntArray(2)
        val anchorCenter = IntArray(2)
        overlay.getLocationOnScreen(overlayCenter)
        anchor.getLocationOnScreen(anchorCenter)
        val startX = anchorCenter[0] + anchor.width / 2f - overlayCenter[0] - overlay.width / 2f
        val startY = anchorCenter[1] + anchor.height / 2f - overlayCenter[1] - overlay.height / 2f

        bubble.translationX = startX
        bubble.translationY = startY
        overlay.animate().alpha(1f).setDuration(FloatingPageTokens.PANEL_OVERLAY_FADE_MS).start()
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
    val overlay = focusOverlay ?: return
    val bubble = activeBubble
    bubble?.animate()
        ?.alpha(0f)
        ?.scaleX(FloatingPageTokens.PANEL_CLOSE_SCALE)
        ?.scaleY(FloatingPageTokens.PANEL_CLOSE_SCALE)
        ?.translationY(host.dp(FloatingPageTokens.PANEL_CLOSE_TRANSLATION_Y_DP).toFloat())
        ?.setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
        ?.withEndAction {
            overlay.visibility = View.GONE
            overlay.removeAllViews()
            activeBubble = null
            clearContentFocus()
        }
        ?.start()
        ?: run {
            overlay.visibility = View.GONE
            overlay.removeAllViews()
            activeBubble = null
            clearContentFocus()
        }
}

private fun FloatingPageScope.clearContentFocus() {
    activePanelResetStateUpdater = null
    selectedTileView?.animate()
        ?.scaleX(1f)
        ?.scaleY(1f)
        ?.alpha(1f)
        ?.setDuration(FloatingPageTokens.PANEL_CLOSE_MS)
        ?.start()
    selectedTileView = null
}
