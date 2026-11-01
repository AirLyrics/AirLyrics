package com.andsi.airlyrics.app.host

import android.content.pm.PackageManager
import android.media.session.MediaController
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.andsi.airlyrics.ui.model.MediaSourceCardBinding
import com.andsi.airlyrics.ui.pages.media.MediaStatusMotion
import com.andsi.airlyrics.ui.pages.media.MediaRollingTextSlot
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.mediaSourceCardImpl(controller: MediaController, selected: Boolean): MediaSourceCardBinding {
    val activity = this
    fun songInfo(source: MediaController): Pair<String, String> {
        val song = resolveMediaSongMetadata(source.packageName, source.metadata)
        val title = song.title.orEmpty().ifBlank { getString(R.string.ui_unknown_song) }
        val artist = song.artist ?: getString(R.string.ui_unknown_artist)
        return title to artist
    }
    var shownSong = songInfo(controller)
    lateinit var selectionView: TextView
    lateinit var songView: MediaRollingTextSlot
    lateinit var stateView: TextView
    val root = card(activity) {
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
                selectionView = label(activity, getString(if (selected) R.string.ui_connected else R.string.ui_available), if (selected) colorAccentText else colorTextMuted).apply {
                    tag = "media_source_status:${controller.packageName}"
                }
                addView(selectionView)
                addView(expandableText(bigText(activity, appName)))
                songView = MediaRollingTextSlot(activity,
                    normalText(activity, "${shownSong.first} - ${shownSong.second}"))
                addView(songView)
                stateView = smallHint(activity, state)
                addView(stateView)
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
    val stateMotion = MediaStatusMotion(activity, stateView)
    var shownSelected = selected
    return object : MediaSourceCardBinding {
        override val view: View = root
        override val packageName: String = controller.packageName

        override fun update(controller: MediaController, selected: Boolean) {
            require(controller.packageName == packageName)
            val nextSong = songInfo(controller)
            songView.show("${nextSong.first} - ${nextSong.second}", delayMs = 30L,
                stablePrefix = if (shownSong.first == nextSong.first) "${nextSong.first} - " else null,
                stableSuffix = if (shownSong.second == nextSong.second) " - ${nextSong.second}" else null)
            shownSong = nextSong
            stateMotion.show(getPlaybackStateText(controller.playbackState?.state))
            if (shownSelected != selected) {
                shownSelected = selected
                selectionView.setText(if (selected) R.string.ui_connected else R.string.ui_available)
                selectionView.setTextColor(if (selected) colorAccentText else colorTextMuted)
            }
        }

        override fun finish() {
            songView.finish()
            stateMotion.finish()
        }
    }
}
