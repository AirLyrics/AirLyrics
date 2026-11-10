package com.andsi.airlyrics.app.search

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.theme.AirLyricsPalette

/** A fixed-height minimum target with a right-to-left selected wash and independent check spring. */
@SuppressLint("ViewConstructor")
internal class LyricsSourceSelectionRow(
    context: Context,
    private val palette: AirLyricsPalette,
    title: String,
    checked: Boolean,
    private val onToggle: () -> Unit
) : LinearLayout(context) {
    private var checked = checked
    private val wash = SelectionWash()
    private var fill = if (checked) 1f else 0f
    private var animator: ValueAnimator? = null
    private val markShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val mark = ImageView(context).apply {
        setImageResource(R.drawable.ic_air_check)
        imageTintList = ColorStateList.valueOf(palette.onAccent)
        setPadding(dp(5), dp(5), dp(5), dp(5))
        background = markShape
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun checkSpring(property: DynamicAnimation.ViewProperty) = SpringAnimation(mark, property).apply {
        spring = SpringForce(1f).apply {
            stiffness = 500f
            dampingRatio = 0.72f
        }
    }
    private val scaleXSpring = checkSpring(DynamicAnimation.SCALE_X)
    private val scaleYSpring = checkSpring(DynamicAnimation.SCALE_Y)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        isFocusable = true
        background = wash
        foreground = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.accent, 35)),
            null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() })
        contentDescription = title
        addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(palette.textStrong)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(0, -2, 1f))
        addView(mark, LayoutParams(dp(26), dp(26)).apply { marginStart = dp(12) })
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.CheckBox"
                info.isCheckable = true
                info.checked = if (this@LyricsSourceSelectionRow.checked)
                    AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE else AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
            }
        })
        setOnClickListener {
            setChecked(!this.checked, true)
            onToggle()
        }
        setChecked(checked, false)
    }

    fun setChecked(next: Boolean, animate: Boolean) {
        checked = next
        isSelected = next
        animator?.cancel()
        val target = if (next) 1f else 0f
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(fill, target).apply {
                duration = 200
                interpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)
                addUpdateListener { fill = it.animatedValue as Float; renderSelection() }
                start()
            }
            if (next) {
                scaleXSpring.cancel()
                scaleYSpring.cancel()
                scaleXSpring.setStartVelocity(-2.5f).animateToFinalPosition(1f)
                scaleYSpring.setStartVelocity(-2.5f).animateToFinalPosition(1f)
            } else {
                scaleXSpring.animateToFinalPosition(1f)
                scaleYSpring.animateToFinalPosition(1f)
            }
        } else {
            fill = target
            scaleXSpring.cancel()
            scaleYSpring.cancel()
            mark.scaleX = 1f
            mark.scaleY = 1f
            renderSelection()
        }
    }

    private fun renderSelection() {
        wash.invalidateSelf()
        markShape.setColor(ColorUtils.blendARGB(palette.stroke, palette.accent, fill))
        mark.imageAlpha = (fill * 255).toInt()
    }

    fun dispose() {
        animator?.cancel()
        animator = null
        scaleXSpring.cancel()
        scaleYSpring.cancel()
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    private inner class SelectionWash : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val clip = Path()
        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            val radius = dp(12).toFloat()
            paint.color = palette.surface
            canvas.drawRoundRect(rect, radius, radius, paint)
            clip.rewind()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            paint.color = ColorUtils.blendARGB(palette.surface, palette.accent, 0.14f)
            paint.alpha = (255 * fill).toInt()
            canvas.drawRect(rect.right - rect.width() * fill, rect.top, rect.right, rect.bottom, paint)
            canvas.restoreToCount(save)
            paint.alpha = 255
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
