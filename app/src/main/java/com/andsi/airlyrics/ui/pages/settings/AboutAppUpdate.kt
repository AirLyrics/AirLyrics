package com.andsi.airlyrics.ui.pages.settings

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.airLyricsPalette
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

internal fun MainUiHost.aboutVersionEntry(): View {
    val version = getAppVersionName()
    val updates = appUpdates
    val pill = statusPill(this, version, playing = true).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    return object : FrameLayout(this) {
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }.apply {
        minimumWidth = dp(AirUiTokens.Layout.IconTouchSize)
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize + AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
        addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        isFocusable = true
        var stickerVisible: Boolean? = null
        fun renderUpdate(available: Boolean) {
            if (stickerVisible == available) return
            stickerVisible = available
            contentDescription = getString(
                if (available) R.string.ui_app_update_available else R.string.ui_app_version_changelog,
                version
            )
            foreground = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(colorTextStrong, 32)),
                if (available) UpdateStickerDrawable(this@aboutVersionEntry) else null,
                GradientDrawable().apply {
                    cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
                    setColor(Color.WHITE)
                }
            )
        }
        if (updates == null) renderUpdate(false)
        else bindAppUpdateState(this, updates.availableUpdate) { renderUpdate(it != null) }
        enableSoftPressFeedback()
        setOnClickListener {
            showAppUpdateDialog()
            updates?.checkNow()
        }
    }
}

internal fun <T> MainUiHost.bindAppUpdateState(view: View, state: StateFlow<T>, render: (T) -> Unit) {
    render(state.value)
    var observer: Job? = null
    fun start() {
        observer = activity.lifecycleScope.launch { state.collect { render(it) } }
    }
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = start()
        override fun onViewDetachedFromWindow(view: View) {
            observer?.cancel()
            observer = null
        }
    })
    if (view.isAttachedToWindow) start()
}

private class UpdateStickerDrawable(host: MainUiHost) : Drawable() {
    private val label = host.getString(R.string.ui_app_update_badge)
    private val frontColor = host.airLyricsPalette.accentPink
    private val backColor = host.airLyricsPalette.accentMint
    private val edgeColor = host.colorCard
    private val ink = ColorUtils.blendARGB(Color.BLACK, frontColor, 0.26f)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AirUiTokens.TextSize.Tiny, host.resources.displayMetrics)
    }
    private val labelBaseline = -(labelPaint.ascent() + labelPaint.descent()) / 2f
    private val halfLength = (labelPaint.measureText(label) / 2f) + host.dp(AirUiTokens.Space.Xl)
    private val halfLineHeight = (labelPaint.descent() - labelPaint.ascent()) / 2f
    private val halfThickness = halfLineHeight + host.dp(AirUiTokens.Space.Xxs)
    private val frontTape = RectF(-halfLength, -halfThickness, halfLength, halfThickness)
    private val backTape = RectF(frontTape).apply { inset(0f, host.dp(AirUiTokens.Space.Xxs).toFloat()) }
    private val rim = host.dp(AirUiTokens.Stroke.Selected).toFloat()
    private val corner = host.dp(AirUiTokens.Space.Xs).toFloat()
    private val seamInset = host.dp(AirUiTokens.Space.Sm).toFloat()
    private val seamHalfLength = host.dp(AirUiTokens.Space.Xxs).toFloat()
    private val stickerRadius = hypot(halfLength + rim, halfThickness + rim)
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = host.dp(AirUiTokens.Stroke.Hairline).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val radius = minOf(stickerRadius, bounds.width() / 2f, bounds.height() / 2f)
        val centerX = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.left + radius else bounds.right - radius
        val scale = radius / stickerRadius
        canvas.withTranslation(centerX, bounds.top + radius) {
            withScale(scale, scale) {
                withRotation(38f) { drawTape(this, backTape, backColor) }
                withRotation(-34f) {
                    drawTape(this, frontTape, frontColor)
                    drawText(label, 0f, labelBaseline, labelPaint)
                }
            }
        }
    }

    override fun getAlpha(): Int = labelPaint.alpha

    override fun setAlpha(alpha: Int) {
        labelPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        labelPaint.colorFilter = colorFilter
        tapePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun drawTape(canvas: Canvas, tape: RectF, color: Int) {
        tapePaint.color = ColorUtils.setAlphaComponent(edgeColor, labelPaint.alpha)
        canvas.drawRoundRect(
            tape.left - rim, tape.top - rim, tape.right + rim, tape.bottom + rim,
            corner + rim, corner + rim, tapePaint
        )
        tapePaint.color = ColorUtils.setAlphaComponent(color, labelPaint.alpha)
        canvas.drawRoundRect(tape, corner, corner, tapePaint)
        val seamColor = ColorUtils.blendARGB(ink, color, 0.52f)
        tapePaint.color = ColorUtils.setAlphaComponent(seamColor, labelPaint.alpha)
        val leftSeam = tape.left + seamInset
        val rightSeam = tape.right - seamInset
        canvas.drawLine(leftSeam, -seamHalfLength, leftSeam, seamHalfLength, tapePaint)
        canvas.drawLine(rightSeam, -seamHalfLength, rightSeam, seamHalfLength, tapePaint)
    }
}
