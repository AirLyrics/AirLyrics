package com.andsi.airlyrics.ui.pages.media

import com.andsi.airlyrics.R

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.view.View
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal fun createMediaPage(activity: MainUiHost, animateContent: Boolean = true): View  = with(activity) createMediaPage@ {
    val container = pageContainer(activity, animateChanges = animateContent)
    val controllers = CurrentMediaReader.selectedControllersByPackage(getActiveMediaControllers())
        .values
        .toList()
    val selectedPackage = MediaSourceStore.getSelectedPackage(this)
    val selectedController = CurrentMediaReader.bestController(controllers, selectedPackage)
    val notificationAccessGranted = hasNotificationListenerAccess()

    container.addView(
        sectionTitle(activity, 
            getString(R.string.ui_media),
            getString(R.string.ui_choose_the_player_airlyrics_follows)
        )
    )

    container.addView(
        card(activity) {
            addView(label(activity, getString(R.string.ui_current_media), colorTextMuted))
            if (selectedController != null) {
                val title = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    .orEmpty()
                    .ifBlank { getString(R.string.ui_unknown_song) }
                val artist = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: getString(R.string.ui_unknown_artist)
                val appName = getAppName(selectedController.packageName)
                val state = getPlaybackStateText(selectedController.playbackState?.state)

                addView(bigText(activity, title))
                addView(normalText(activity, "$artist · $appName"))
                addView(statusPill(activity, state, selectedController.playbackState?.state == PlaybackState.STATE_PLAYING))
            } else {
                addView(bigText(activity, getString(R.string.ui_no_media_detected_yet)))
                val hintText = if (notificationAccessGranted) {
                    getString(R.string.ui_media_streams_empty_hint)
                } else {
                    getString(R.string.ui_enable_notif_access_hint)
                }
                addView(normalText(activity, hintText))
            }
        }
    )

    container.addView(spacer(activity, 12))
    container.addView(label(activity, getString(R.string.ui_active_players), colorTextMuted))

    if (controllers.isEmpty()) {
        container.addView(
            card(activity) {
                addView(bigText(activity, getString(R.string.ui_waiting_for_music_signal)))
                addView(normalText(activity, getString(R.string.ui_media_streams_empty_hint)))
                if (!notificationAccessGranted) {
                    addView(smallHint(activity, getString(R.string.ui_media_empty_permission_hint)))
                }
            }
        )
    } else {
        controllers.forEach { controller ->
            container.addView(mediaSourceCard(controller, controller.packageName == selectedPackage))
        }
    }

    container.addView(spacer(activity, 18))
    container.addView(refreshMediaButton())

    return scroll(activity, container, animateChildren = animateContent)
}
