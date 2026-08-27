package com.andsi.airlyrics.app.host

import android.media.MediaMetadata
import android.media.session.MediaController
import android.view.View
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.label
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.mediaSourceCardImpl(controller: MediaController, selected: Boolean): View {
    val activity = this
    return card(activity) {
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            .orEmpty()
            .ifBlank { getString(R.string.ui_unknown_song) }
        val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: getString(R.string.ui_unknown_artist)
        val appName = getAppName(controller.packageName)
        val state = getPlaybackStateText(controller.playbackState?.state)

        addView(label(activity, getString(if (selected) R.string.ui_connected else R.string.ui_available), if (selected) colorAccentLight else colorTextMuted).apply {
            tag = "media_source_status:${controller.packageName}"
        })
        addView(bigText(activity, appName))
        addView(normalText(activity, "$title - $artist"))
        addView(smallHint(activity, state))
        enableSoftPressFeedback(AirUiTokens.Motion.FloatingCardPressScale)
        setOnClickListener {
            uiActions.selectMediaSource(controller.packageName, this)
        }
    }
}
