package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.refresh.PageRebuildReason

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
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.refreshMediaButtonImpl(): View {
    val activity = this
    lateinit var row: LinearLayout
    lateinit var labelView: TextView
    var progressView: ProgressBar? = null

    fun applyButtonState(animateDone: Boolean = false) {
        val buttonText = when (mediaRefreshState) {
            RefreshState.IDLE -> getString(R.string.ui_refresh_media_status)
            RefreshState.REFRESHING -> getString(R.string.ui_refreshing)
            RefreshState.DONE -> getString(R.string.ui_refreshed)
        }
        val buttonColor = when (mediaRefreshState) {
            RefreshState.IDLE -> colorAccent
            RefreshState.REFRESHING -> colorSurfaceLight
            RefreshState.DONE -> colorAccentSoft
        }

        row.isEnabled = mediaRefreshState != RefreshState.REFRESHING
        row.background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(buttonColor)
        }
        labelView.text = buttonText
        labelView.setTextColor(if (mediaRefreshState == RefreshState.REFRESHING) colorText else Color.WHITE)

        if (mediaRefreshState == RefreshState.REFRESHING && progressView == null) {
            progressView = ProgressBar(activity).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.StatusIconSize), dp(AirUiTokens.Layout.StatusIconSize)).apply {
                    setMargins(0, 0, dp(AirUiTokens.Space.Xxl), 0)
                }
            }
            row.addView(progressView, 0)
        } else if (mediaRefreshState != RefreshState.REFRESHING && progressView != null) {
            row.removeView(progressView)
            progressView = null
        }

        if (animateDone && mediaRefreshState == RefreshState.DONE) {
            row.rotation = AirUiTokens.Layout.MediaDoneRotation
            row.animate()
                .rotation(0f)
                                .scaleX(AirUiTokens.Layout.MediaDoneScale)
                                .scaleY(AirUiTokens.Layout.MediaDoneScale)
                .setDuration(AirUiTokens.Motion.PressUpMs)
                .setInterpolator(OvershootInterpolator(AirUiTokens.Layout.MediaDoneOvershoot))
                .withEndAction {
                    row.animate()
                                        .scaleX(AirUiTokens.Motion.RestScale)
                                        .scaleY(AirUiTokens.Motion.RestScale)
                        .setDuration(AirUiTokens.Layout.MediaDoneSettleMs)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }
    }

    row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(AirUiTokens.Space.Xxl), 0, 0)
        layoutParams = params
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
    }

    labelView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
    }
    row.addView(labelView)

    applyButtonState()
    row.setOnClickListener {
        if (mediaRefreshState == RefreshState.REFRESHING) return@setOnClickListener
        playTinyPulse(row)
        startMediaRefreshFeedbackImpl { applyButtonState(animateDone = true) }
        applyButtonState()
    }
    return row
}

internal fun MainUiHost.startMediaRefreshFeedbackImpl(onStateChanged: () -> Unit) {
    mediaRefreshHandler.removeCallbacksAndMessages(null)
    mediaRefreshState = RefreshState.REFRESHING
    onStateChanged()

    mediaRefreshHandler.postDelayed({
        mediaRefreshState = RefreshState.DONE
        onStateChanged()
        mediaRefreshHandler.postDelayed({
            if (currentPage == Page.MEDIA) {
                rebuildCurrentPage(
                    reason = PageRebuildReason.MEDIA_CONTENT_CHANGED,
                    animateContent = false,
                    animateTabs = false
                )
            }
        }, AirUiTokens.Layout.MediaDoneRefreshMs)
    }, AirUiTokens.Layout.MediaRefreshingMs)
}

internal fun MainUiHost.updateMediaSourceSelectionVisualsImpl(selectedPackage: String) {
    val root = contentContainer ?: return
    fun visit(view: View) {
        if (view is TextView) {
            val tagText = view.tag as? String
            if (tagText?.startsWith("media_source_status:") == true) {
                val packageName = tagText.removePrefix("media_source_status:")
                val selected = packageName == selectedPackage
                view.text = if (selected) getString(R.string.ui_connected) else getString(R.string.ui_available)
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
