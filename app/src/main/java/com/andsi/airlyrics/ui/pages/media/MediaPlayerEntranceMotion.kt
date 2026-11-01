package com.andsi.airlyrics.ui.pages.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import com.andsi.airlyrics.design.tokens.AirUiTokens
import kotlin.math.cos
import kotlin.math.exp

/** Keeps a player's entrance at the same progress when media callbacks rebuild the page. */
internal class MediaPlayerEntranceMotion {
    private val settleEnd = 1.0 - exp(-5.5) * cos(6.0)

    private data class Entrance(
        var progress: Float = 0f,
        var view: View? = null,
        var animator: ValueAnimator? = null
    )

    private var displayedPackages: Set<String>? = null
    private val active = mutableMapOf<String, Entrance>()

    fun updatePackages(packages: Set<String>): Set<String> {
        val previous = displayedPackages.orEmpty()
        displayedPackages = packages.toSet()
        val appeared = packages - previous

        (active.keys - packages).forEach { packageName ->
            active.remove(packageName)?.let(::cancel)
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishActive()
            return appeared
        }
        appeared.forEach { packageName ->
            active[packageName] = Entrance()
        }
        return appeared
    }

    fun bind(packageName: String, view: View, risePx: Float) {
        val entrance = active[packageName] ?: return
        cancel(entrance)
        entrance.view = view
        applyProgress(view, entrance.progress, risePx)
        view.post {
            if (entrance.view !== view || !view.isAttachedToWindow) return@post
            if (!ValueAnimator.areAnimatorsEnabled()) {
                finish(packageName, entrance)
                return@post
            }
            val remaining = 1f - entrance.progress
            val animator = ValueAnimator.ofFloat(entrance.progress, 1f).apply {
                duration = (AirUiTokens.Motion.MediaPlayerEnterMs * remaining).toLong().coerceAtLeast(1L)
                interpolator = LinearInterpolator()
                addUpdateListener { animation ->
                    if (entrance.view === view) {
                        entrance.progress = animation.animatedValue as Float
                        applyProgress(view, entrance.progress, risePx)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (entrance.animator === animation) {
                            entrance.animator = null
                            finish(packageName, entrance)
                        }
                    }
                })
            }
            entrance.animator = animator
            animator.start()
        }
    }

    fun finishActive() {
        active.toMap().forEach { (packageName, entrance) -> finish(packageName, entrance) }
    }

    private fun finish(packageName: String, entrance: Entrance) {
        cancel(entrance)
        entrance.view?.let { view ->
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationY = 0f
        }
        entrance.view = null
        if (active[packageName] === entrance) active.remove(packageName)
    }

    private fun cancel(entrance: Entrance) {
        val animator = entrance.animator
        entrance.animator = null
        animator?.cancel()
    }

    private fun applyProgress(view: View, progress: Float, risePx: Float) {
        // A short damped settle gives about 0.5% scale overshoot and 1 dp of lift.
        val spring = ((1.0 - exp(-5.5 * progress) * cos(6.0 * progress)) / settleEnd).toFloat()
        val scale = AirUiTokens.Motion.MediaPlayerEnterScale +
            (1f - AirUiTokens.Motion.MediaPlayerEnterScale) * spring
        val fade = (progress / 0.65f).coerceIn(0f, 1f)
        view.alpha = 1f - (1f - fade) * (1f - fade) * (1f - fade)
        view.scaleX = scale
        view.scaleY = scale
        view.translationY = risePx * (1f - spring)
    }
}
