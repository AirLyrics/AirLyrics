package com.andsi.airlyrics.ui.components

import android.animation.Animator
import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionValues
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.doOnPreDraw
import java.util.WeakHashMap
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal const val FLOATING_PANEL_MOTION_ROOT = "floating.panel"
internal val floatingPanelEaseOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)

internal fun View.floatingPanelMotionRoot(): ViewGroup? =
    generateSequence(this) { it.parent as? View }
        .firstOrNull { it.getTag(R.id.interaction_anchor) == FLOATING_PANEL_MOTION_ROOT } as? ViewGroup

internal fun View.canAnimateFloatingControl(): Boolean =
    isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled() &&
        (isInTouchMode || !hasFocus())

internal fun View.beginFloatingPanelChange() {
    val root = floatingPanelMotionRoot() ?: return
    root.beginPanelTransition(TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(ChangeBounds())
        addTransition(FloatingPanelFade())
    })
}

/** Replacing a form must not lift old controls or their hidden pages into a Fade overlay. */
internal fun View.beginFloatingPanelContentReplacement() {
    val root = floatingPanelMotionRoot() ?: return
    endFloatingPanelChanges()
    val bounds = ChangeBounds()
    generateSequence(this) { if (it === root) null else it.parent as? View }
        .forEach { bounds.addTarget(it) }
    root.beginPanelTransition(bounds)
}

private val pendingPanelTransitions = WeakHashMap<ViewGroup, Unit>()

private fun ViewGroup.beginPanelTransition(transition: Transition) {
    val scene = parent as? ViewGroup ?: this
    if (!ValueAnimator.areAnimatorsEnabled()) {
        TransitionManager.endTransitions(scene)
        return
    }
    if (!isAttachedToWindow || !isLaidOut || (!isInTouchMode && findFocus() != null)) return
    // endTransitions() cancels the pending request but leaves its pre-draw listener in place.
    // Starting another request in the same frame lets that old listener consume the new one
    // and restart its canceled Fade. Keep this guard until the old listener has been drained.
    if (pendingPanelTransitions.containsKey(scene)) return
    pendingPanelTransitions[scene] = Unit
    transition.duration = AirUiTokens.Motion.LayoutChangeMs
    transition.interpolator = floatingPanelEaseOut
    TransitionManager.beginDelayedTransition(scene, transition)
    val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            pendingPanelTransitions.remove(scene)
            scene.removeOnAttachStateChangeListener(this)
        }
    }
    scene.addOnAttachStateChangeListener(detach)
    // Register after the framework listener, so it cannot steal a subsequent request.
    scene.doOnPreDraw {
        pendingPanelTransitions.remove(scene)
        scene.removeOnAttachStateChangeListener(detach)
    }
}

internal fun View.endFloatingPanelChanges() {
    val root = floatingPanelMotionRoot() ?: return
    TransitionManager.endTransitions(root.parent as? ViewGroup ?: root)
}

/**
 * Framework Fade captures a child's own visibility, not its ancestors' visibility.
 * Filtering before createAnimator also prevents Visibility from copying hidden children
 * into an overlay, where the original parent no longer hides or clips them.
 */
internal class FloatingPanelFade : Fade() {
    override fun captureStartValues(transitionValues: TransitionValues) {
        super.captureStartValues(transitionValues)
        transitionValues.values[SHOWN] = transitionValues.view.isShown
    }

    override fun captureEndValues(transitionValues: TransitionValues) {
        super.captureEndValues(transitionValues)
        transitionValues.values[SHOWN] = transitionValues.view.isShown
    }

    override fun createAnimator(
        sceneRoot: ViewGroup,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator? {
        if (startValues?.values?.get(SHOWN) != true && endValues?.values?.get(SHOWN) != true) return null
        return super.createAnimator(sceneRoot, startValues, endValues)
    }

    private companion object {
        const val SHOWN = "airlyrics:panel:shown"
    }
}
