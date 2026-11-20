package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.AppUpdateDownloadState
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.text.NumberFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

private const val DOWNLOAD_TRANSITION_MS = 260L
private const val UPDATE_COLLAPSE_END = 0.62f
private const val UPDATE_FADE_START = 0.37f
private const val PROGRESS_REVEAL_START = 0.52f

@SuppressLint("ViewConstructor")
internal class AboutVersionDownloadFooter(
    private val host: MainUiHost,
    private val update: AboutVersionUpdate,
    private val isClosing: () -> Boolean,
) : FrameLayout(host) {
    private var showingDownload = false
    private var transitionFraction = 0f
    private var transition: ValueAnimator? = null
    private var downloadObserver: Job? = null

    private val updateButton = Button(host).apply {
        isAllCaps = false
        textSize = AirUiTokens.TextSize.Button
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        host.styleAirTonalButton(this, AirTonalButtonTone.PRIMARY)
        setPaddingRelative(host.dp(AirUiTokens.Space.ButtonH), host.dp(AirUiTokens.Space.Xl),
            host.dp(AirUiTokens.Space.ButtonH), host.dp(AirUiTokens.Space.Xl))
        setOnClickListener {
            if (isEnabled && !showingDownload && !isClosing()) update.onUpdate()
        }
    }
    private val progressAction = Button(host).apply {
        setText(R.string.ui_cancel)
        isAllCaps = false
        textSize = AirUiTokens.TextSize.Caption
        setTextColor(host.colorAccentText)
        minWidth = host.dp(AirUiTokens.Layout.IconTouchSize)
        minimumWidth = minWidth
        stateListAnimator = null
        elevation = 0f
        translationZ = 0f
        outlineProvider = null
        backgroundTintList = null
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccentText, 40)),
            null,
            GradientDrawable().apply {
                cornerRadius = host.dp(AirUiTokens.Radius.Pill).toFloat()
                setColor(Color.WHITE)
            }
        )
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPaddingRelative(host.dp(AirUiTokens.Space.Xs), 0, 0, 0)
        setOnClickListener {
            if (isClosing()) return@setOnClickListener
            when (val state = update.download.value) {
                is AppUpdateDownloadState.Progress -> if (state.percent < 100) update.onCancel?.invoke()
                AppUpdateDownloadState.Ready, AppUpdateDownloadState.PermissionRequired, AppUpdateDownloadState.InstallFailed ->
                    update.onInstall?.invoke()
                else -> Unit
            }
        }
    }
    private val percentFormat = NumberFormat.getPercentInstance(resources.configuration.locales[0])
    private val percent = TextView(host).apply {
        textSize = AirUiTokens.TextSize.BodySmall
        setTextColor(host.colorTextStrong)
        minimumWidth = ceil(paint.measureText(percentFormat.format(1.0))).toInt()
        gravity = Gravity.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val bar = UpdateDownloadProgressView(host)
    private val progressRow = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        isBaselineAligned = false
        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(progressAction, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                progressAction.minHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
                progressAction.minimumHeight = progressAction.minHeight
            })
            addView(percent, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        addView(bar, LinearLayout.LayoutParams(0, host.dp(60), 1f))
        addView(actions, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = host.dp(AirUiTokens.Space.Xl)
            bottomMargin = host.dp(AirUiTokens.Space.Xxl)
        })
    }

    init {
        addView(updateButton, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        addView(progressRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        render(update.download.value, animate = false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        downloadObserver = host.activity.lifecycleScope.launch {
            update.download.collect { if (!isClosing()) render(it) }
        }
    }

    override fun onDetachedFromWindow() {
        downloadObserver?.cancel()
        downloadObserver = null
        transition?.cancel()
        applyTransition(if (showingDownload) 1f else 0f)
        super.onDetachedFromWindow()
    }

    private fun render(state: AppUpdateDownloadState, animate: Boolean = true) {
        val value = when (state) {
            is AppUpdateDownloadState.Progress -> state.percent.coerceIn(0, 100)
            AppUpdateDownloadState.Idle, AppUpdateDownloadState.Failed, AppUpdateDownloadState.Incompatible -> null
            else -> 100
        }
        val label = when (state) {
            is AppUpdateDownloadState.Progress ->
                R.string.ui_cancel.takeIf { state.percent < 100 && update.onCancel != null }
            AppUpdateDownloadState.Ready -> R.string.ui_app_update_ready_install
            AppUpdateDownloadState.Installing -> R.string.ui_app_update_installing
            AppUpdateDownloadState.PermissionRequired -> R.string.ui_app_update_allow_install
            AppUpdateDownloadState.InstallFailed -> R.string.ui_app_update_install_retry
            AppUpdateDownloadState.Installed -> R.string.ui_app_update_installed
            else -> null
        }
        progressAction.visibility = if (label == null) INVISIBLE else VISIBLE
        if (label != null) progressAction.setText(label)
        val actionable = when (state) {
            is AppUpdateDownloadState.Progress -> state.percent < 100 && update.onCancel != null
            AppUpdateDownloadState.Ready, AppUpdateDownloadState.PermissionRequired, AppUpdateDownloadState.InstallFailed ->
                update.onInstall != null
            else -> false
        }
        progressAction.isClickable = actionable
        progressAction.isFocusable = actionable
        if (value == null) {
            updateButton.isEnabled = state != AppUpdateDownloadState.Incompatible
            updateButton.setText(when (state) {
                AppUpdateDownloadState.Failed -> R.string.ui_app_update_retry_download
                AppUpdateDownloadState.Incompatible -> R.string.ui_app_update_incompatible
                else -> R.string.ui_app_update_install
            })
        } else {
            if (!showingDownload) bar.showProgress(0, animate = false)
            percent.text = percentFormat.format(value / 100.0)
            bar.showProgress(value, animate)
        }
        showDownload(value != null, animate)
    }

    private fun showDownload(show: Boolean, animate: Boolean) {
        if (animate && showingDownload == show) return
        showingDownload = show
        updateButton.isClickable = !show
        updateButton.importantForAccessibility = if (show) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        transition?.cancel()
        val target = if (show) 1f else 0f
        if (!animate || !isLaidOut || !ValueAnimator.areAnimatorsEnabled() || transitionFraction == target) {
            applyTransition(target)
            return
        }

        val start = transitionFraction
        val animator = ValueAnimator.ofFloat(start, target).apply {
            duration = (DOWNLOAD_TRANSITION_MS * abs(target - start)).roundToLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                when {
                    isClosing() -> it.cancel()
                    !ValueAnimator.areAnimatorsEnabled() -> {
                        applyTransition(target)
                        it.cancel()
                    }
                    else -> applyTransition(it.animatedValue as Float)
                }
            }
            doOnEnd { transition = null }
        }
        transition = animator
        animator.start()
    }

    private fun applyTransition(fraction: Float) {
        transitionFraction = fraction
        val collapse = aboutVersionMotionEase.getInterpolation((fraction / UPDATE_COLLAPSE_END).coerceIn(0f, 1f))
        val fade = ((fraction - UPDATE_FADE_START) / (UPDATE_COLLAPSE_END - UPDATE_FADE_START)).coerceIn(0f, 1f)
        val reveal = aboutVersionMotionEase.getInterpolation(
            ((fraction - PROGRESS_REVEAL_START) / (1f - PROGRESS_REVEAL_START)).coerceIn(0f, 1f)
        )

        // Collapse the action into the same horizontal line that the progress track occupies.
        val trackY = progressRow.top + bar.top + bar.lineCenterY
        updateButton.pivotY = (trackY - updateButton.top).coerceIn(0f, updateButton.height.toFloat())
        updateButton.scaleY = 1f - 0.94f * collapse
        updateButton.alpha = 1f - fade
        updateButton.visibility = if (fade < 1f) VISIBLE else INVISIBLE

        progressRow.alpha = reveal
        progressRow.translationY = host.dp(AirUiTokens.Space.Sm) * (1f - reveal)
        progressRow.visibility = if (reveal > 0f) VISIBLE else INVISIBLE
    }
}
