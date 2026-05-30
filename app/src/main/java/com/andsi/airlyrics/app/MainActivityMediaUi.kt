package com.andsi.airlyrics.app

import android.view.View
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.i18n.tr

internal fun MainActivity.refreshMediaButton(): View {
    val activity = this
    lateinit var row: LinearLayout
    lateinit var labelView: TextView
    var progressView: ProgressBar? = null

    fun applyButtonState(animateDone: Boolean = false) {
        val buttonText = when (mediaRefreshState) {
            RefreshState.IDLE -> tr("刷新媒体状态", "Refresh media status")
            RefreshState.REFRESHING -> tr("刷新中", "Refreshing")
            RefreshState.DONE -> tr("已刷新", "Refreshed")
        }
        val buttonColor = when (mediaRefreshState) {
            RefreshState.IDLE -> colorAccent
            RefreshState.REFRESHING -> colorSurfaceLight
            RefreshState.DONE -> colorAccentSoft
        }

        row.isEnabled = mediaRefreshState != RefreshState.REFRESHING
        row.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(buttonColor)
        }
        labelView.text = buttonText
        labelView.setTextColor(if (mediaRefreshState == RefreshState.REFRESHING) colorText else Color.WHITE)

        if (mediaRefreshState == RefreshState.REFRESHING && progressView == null) {
            progressView = ProgressBar(activity).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    setMargins(0, 0, dp(10), 0)
                }
            }
            row.addView(progressView, 0)
        } else if (mediaRefreshState != RefreshState.REFRESHING && progressView != null) {
            row.removeView(progressView)
            progressView = null
        }

        if (animateDone && mediaRefreshState == RefreshState.DONE) {
            row.rotation = -2f
            row.animate()
                .rotation(0f)
                .scaleX(1.018f)
                .scaleY(1.018f)
                .setDuration(150L)
                .setInterpolator(OvershootInterpolator(0.65f))
                .withEndAction {
                    row.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(130L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }
    }

    row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(10), 0, 0)
        layoutParams = params
        enableSoftPressFeedback(0.97f)
    }

    labelView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }
    row.addView(labelView)

    applyButtonState()
    row.setOnClickListener {
        if (mediaRefreshState == RefreshState.REFRESHING) return@setOnClickListener
        playTinyPulse(row)
        startMediaRefreshFeedback { applyButtonState(animateDone = true) }
        applyButtonState()
    }
    return row
}

internal fun MainActivity.startMediaRefreshFeedback(onStateChanged: () -> Unit) {
    val activity = this
    mediaRefreshHandler.removeCallbacksAndMessages(null)
    mediaRefreshState = RefreshState.REFRESHING
    onStateChanged()

    mediaRefreshHandler.postDelayed({
        mediaRefreshState = RefreshState.DONE
        onStateChanged()
        mediaRefreshHandler.postDelayed({
            if (currentPage == Page.MEDIA) {
                renderCurrentPage(animateContent = false, animateTabs = false)
            }
        }, 260L)
    }, 650L)
}

internal fun MainActivity.updateMediaSourceSelectionVisuals(selectedPackage: String) {
    val activity = this
    val root = contentContainer ?: return
    fun visit(view: View) {
        if (view is TextView) {
            val tagText = view.tag as? String
            if (tagText?.startsWith("media_source_status:") == true) {
                val packageName = tagText.removePrefix("media_source_status:")
                val selected = packageName == selectedPackage
                view.text = if (selected) tr("已连接", "Connected") else tr("可选择", "Available")
                view.setTextColor(if (selected) colorAccentLight else colorTextMuted)
                if (selected) playTinyPulse(view)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
    }
    visit(root)
}

