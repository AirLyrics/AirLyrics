package com.andsi.airlyrics.ui.components

import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal const val FLOATING_PANEL_MOTION_ROOT = "floating.panel"
internal val floatingPanelEaseOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)

/** Capture only the modal; background page geometry is never part of its transition. */
internal fun View.beginFloatingPanelChange() {
    val root = generateSequence(this) { it.parent as? View }
        .firstOrNull { it.getTag(R.id.interaction_anchor) == FLOATING_PANEL_MOTION_ROOT } as? ViewGroup ?: return
    if (!ValueAnimator.areAnimatorsEnabled()) {
        TransitionManager.endTransitions(root)
        return
    }
    if (!root.isAttachedToWindow || !root.isLaidOut ||
        (!root.isInTouchMode && root.findFocus() != null)) return
    // Native transitions capture the current values when another change interrupts them.
    TransitionManager.beginDelayedTransition(root, TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(ChangeBounds())
        addTransition(Fade())
        duration = AirUiTokens.Motion.LayoutChangeMs
        interpolator = floatingPanelEaseOut
    })
}
