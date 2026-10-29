package com.andsi.airlyrics.app.host

import android.content.pm.PackageManager
import android.media.session.MediaController
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.andsi.airlyrics.media.resolveMediaSongMetadata
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.expandableText
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.label
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.mediaSourceCardImpl(controller: MediaController, selected: Boolean): View {
    val activity = this
    return card(activity) {
        val song = resolveMediaSongMetadata(controller.packageName, controller.metadata)
        val title = song.title
            .orEmpty()
            .ifBlank { getString(R.string.ui_unknown_song) }
        val artist = song.artist
            ?: getString(R.string.ui_unknown_artist)
        val appName = getAppName(controller.packageName)
        val state = getPlaybackStateText(controller.playbackState?.state)
        val appIcon = try {
            packageManager.getApplicationIcon(controller.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            packageManager.defaultActivityIcon
        } catch (_: RuntimeException) {
            packageManager.defaultActivityIcon
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(label(activity, getString(if (selected) R.string.ui_connected else R.string.ui_available), if (selected) colorAccentText else colorTextMuted).apply {
                    tag = "media_source_status:${controller.packageName}"
                })
                addView(expandableText(bigText(activity, appName)))
                addView(expandableText(normalText(activity, "$title - $artist")))
                addView(smallHint(activity, state))
            })
            addView(ImageView(activity).apply {
                setImageDrawable(appIcon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
                }
            })
        })
        enableSoftPressFeedback(AirUiTokens.Motion.FloatingCardPressScale)
        setOnClickListener {
            uiActions.selectMediaSource(controller.packageName, this)
        }
    }
}
