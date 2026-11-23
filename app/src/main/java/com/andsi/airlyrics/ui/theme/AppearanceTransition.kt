package com.andsi.airlyrics.ui.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import java.util.WeakHashMap

internal data class AppearanceFrame(
    val palette: AirLyricsPalette,
    val accentText: Int,
    val darkFraction: Float,
    val solidButtonFraction: Float,
    private val accentSelection: Map<ThemeAccent, Float>,
) {
    fun accentSelectionFraction(accent: ThemeAccent): Float = accentSelection[accent] ?: 0f

    fun interpolate(to: AppearanceFrame, fraction: Float): AppearanceFrame {
        val t = fraction.coerceIn(0f, 1f)
        return AppearanceFrame(
            palette.interpolate(to.palette, t),
            ColorUtils.blendARGB(accentText, to.accentText, t),
            darkFraction + (to.darkFraction - darkFraction) * t,
            solidButtonFraction + (to.solidButtonFraction - solidButtonFraction) * t,
            (accentSelection.keys + to.accentSelection.keys).associateWith { accent ->
                val from = accentSelectionFraction(accent)
                from + (to.accentSelectionFraction(accent) - from) * t
            },
        )
    }

    companion object {
        fun resolve(host: MainUiHost): AppearanceFrame {
            val dark = host.isDarkTheme()
            val accent = host.themeAccent()
            val palette = AirLyricsTheme.palette(dark, accent)
            return AppearanceFrame(
                palette,
                if (dark) palette.accent else ColorUtils.blendARGB(palette.accent, palette.textStrong, 0.6f),
                if (dark) 1f else 0f,
                if (host.buttonStyle() == ButtonStyle.SOLID) 1f else 0f,
                mapOf(accent to 1f),
            )
        }
    }
}

/**
 * Recolors mounted Views in place. Each View owns its callback and this registry holds only
 * weak keys, so closures may safely capture their header. No layout or tree rebuild per frame.
 */
internal class AppearanceTransition(private val host: MainUiHost) {
    var frame = AppearanceFrame.resolve(host)
        private set
    private var destination = frame
    private var animator: ValueAnimator? = null
    val isAnimating: Boolean get() = animator != null
    private val bindings = WeakHashMap<View, Unit>()

    fun <V : View> bind(view: V, update: (V) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val callback: (View) -> Unit = { update(it as V) }
        view.setTag(R.id.appearance_color_binding, callback)
        bindings[view] = Unit
        update(view)
    }

    fun transition(
        animate: Boolean,
        durationMs: Long = AirUiTokens.Motion.ThemeColorChangeMs,
    ) {
        val target = AppearanceFrame.resolve(host)
        if (target == destination && animator != null) {
            refresh()
            return
        }
        cancel()
        destination = target
        val from = frame
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || from == target) {
            frame = target
            refresh()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener {
                frame = from.interpolate(target, it.animatedValue as Float)
                refresh()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    frame = target
                    refresh()
                }
            })
            start()
        }
    }

    fun refresh() {
        bindings.keys.forEach { view ->
            if (view.isAttachedToWindow) {
                @Suppress("UNCHECKED_CAST")
                val update = view.getTag(R.id.appearance_color_binding) as? (View) -> Unit
                update?.invoke(view)
            }
        }
    }

    fun finish() {
        cancel()
        frame = destination
        refresh()
    }

    fun dispose() {
        cancel()
        bindings.clear()
    }

    private fun cancel() {
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }
}

internal fun <V : View> MainUiHost.bindAppearance(view: V, update: (V) -> Unit) =
    appearance.bind(view, update)
