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
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.state.forgetAuxiliaryDialog
import com.andsi.airlyrics.ui.state.isAuxiliaryDialogShowing
import com.andsi.airlyrics.ui.state.rememberAuxiliaryDialog
import com.andsi.airlyrics.ui.theme.airLyricsPalette
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlin.math.hypot

internal const val APP_UPDATE_DIALOG = "appUpdate"

// Replace this with release availability when the updater is connected.
private val hasAppUpdate = BuildConfig.DEBUG

internal fun MainUiHost.aboutVersionEntry(): View {
    val version = getAppVersionName()
    val pill = statusPill(this, version, playing = true)
    return object : FrameLayout(this) {
        override fun getAccessibilityClassName(): CharSequence =
            if (isClickable) Button::class.java.name else super.getAccessibilityClassName()
    }.apply {
        // Both states reserve the same space. The sticker is drawn over the pill.
        minimumWidth = dp(AirUiTokens.Layout.IconTouchSize)
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize + AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
        addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        if (!hasAppUpdate) return@apply

        contentDescription = getString(R.string.ui_app_update_available, version)
        pill.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = true
        foreground = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(colorTextStrong, 32)),
            UpdateStickerDrawable(this@aboutVersionEntry),
            GradientDrawable().apply {
                cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
                setColor(Color.WHITE)
            },
        )
        enableSoftPressFeedback()
        setOnClickListener { showAppUpdateDialog() }
    }
}

internal fun MainUiHost.showAppUpdateDialog() {
    if (!hasAppUpdate || isAuxiliaryDialogShowing(APP_UPDATE_DIALOG)) return
    rememberAuxiliaryDialog(APP_UPDATE_DIALOG)
    auxiliaryDialogs[APP_UPDATE_DIALOG] = showAirDialog(
        title = getString(R.string.ui_app_update_title),
        message = getString(R.string.ui_app_update_preview),
        positiveText = null,
        coordinatedDim = true,
        popIn = true,
        showCloseButton = true,
        onUserDismiss = { forgetAuxiliaryDialog(APP_UPDATE_DIALOG) },
    )
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
