package com.andsi.airlyrics.ui.pages.media

import com.andsi.airlyrics.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.PlaybackState
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.MediaPageState
import com.andsi.airlyrics.ui.model.MediaSourceCardBinding
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal const val MEDIA_ARTWORK_SIZE_DP = 72

internal class MediaPageBinding(
    val view: View,
    private val packages: Set<String>,
    private val currentMedia: CurrentMediaBinding?,
    private val players: List<MediaSourceCardBinding>
) {
    fun update(state: MediaPageState): Boolean {
        if (state.controllers.mapTo(mutableSetOf()) { it.packageName } != packages ||
            (state.selectedController != null) != (currentMedia != null)) return false
        currentMedia?.update(state)
        val controllers = state.controllers.associateBy { it.packageName }
        players.forEach { card ->
            val controller = controllers.getValue(card.packageName)
            card.update(controller, controller.packageName == state.selectedPackage)
        }
        return true
    }

    fun finish() {
        currentMedia?.finish()
        players.forEach(MediaSourceCardBinding::finish)
    }
}

internal class CurrentMediaBinding(
    private val host: MainUiHost,
    private val title: MediaRollingTextSlot,
    private val artist: MediaRollingTextSlot,
    private val status: MediaStatusMotion,
    private val artwork: MediaArtworkSwap
) {
    fun update(state: MediaPageState) = with(host) {
        val controller = state.selectedController ?: return@with
        title.show(state.selectedTitle.orEmpty().ifBlank { getString(R.string.ui_unknown_song) })
        val artistText = state.selectedArtist ?: getString(R.string.ui_unknown_artist)
        val appName = getAppName(controller.packageName)
        artist.show("$artistText · $appName", delayMs = 45L, stableSuffix = " · $appName")
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        status.show(getPlaybackStateText(controller.playbackState?.state), playing)
        artwork.show(state.selectedArtworkBitmap, state.selectedArtworkUri,
            "${controller.packageName}\u0000${state.selectedTitle}\u0000${state.selectedArtist}")
    }

    fun finish() {
        title.finish()
        artist.finish()
        status.finish()
        artwork.finish()
    }
}

internal fun createMediaPage(
    activity: MainUiHost,
    animateContent: Boolean = true,
    pageState: MediaPageState = activity.mediaPageState(),
    playerEntrance: MediaPlayerEntranceMotion? = null,
    artworkMotion: MediaArtworkMotionState = MediaArtworkMotionState(),
    onBound: ((MediaPageBinding) -> Unit)? = null
): View = with(activity) createMediaPage@ {
    val container = pageContainer(activity, animateChanges = animateContent)
    val controllers = pageState.controllers
    val selectedPackage = pageState.selectedPackage
    val selectedController = pageState.selectedController
    val notificationAccessGranted = hasNotificationListenerAccess()
    var currentMedia: CurrentMediaBinding? = null
    val players = mutableListOf<MediaSourceCardBinding>()

    container.addView(
        sectionTitle(activity, getString(R.string.ui_media))
    )

    container.addView(
        card(activity) {
            addView(label(activity, getString(R.string.ui_current_media), colorTextMuted))
            if (selectedController != null) {
                val title = pageState.selectedTitle
                    .orEmpty()
                    .ifBlank { getString(R.string.ui_unknown_song) }
                val artist = pageState.selectedArtist
                    ?: getString(R.string.ui_unknown_artist)
                val appName = getAppName(selectedController.packageName)
                val state = getPlaybackStateText(selectedController.playbackState?.state)
                val artworkBitmap = pageState.selectedArtworkBitmap
                val artworkUri = pageState.selectedArtworkUri
                val titleView = MediaRollingTextSlot(activity, bigText(activity, title), MediaRollingRole.TITLE)
                val artistView = MediaRollingTextSlot(activity, normalText(activity, "$artist · $appName"))
                val statusView = statusPill(activity, state, selectedController.playbackState?.state == PlaybackState.STATE_PLAYING)
                val statusMotion = MediaStatusMotion(activity, statusView,
                    selectedController.playbackState?.state == PlaybackState.STATE_PLAYING)
                val artworkView = MediaArtworkSwap(activity, artworkMotion)
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        )
                        addView(titleView)
                        addView(artistView)
                        addView(statusView)
                    })
                    addView(artworkView)
                })
                artworkView.show(artworkBitmap, artworkUri,
                    "${selectedController.packageName}\u0000${pageState.selectedTitle}\u0000${pageState.selectedArtist}")
                currentMedia = CurrentMediaBinding(activity, titleView, artistView, statusMotion, artworkView)
            } else {
                addView(bigText(activity, getString(R.string.ui_no_media_detected_yet)))
                val hintText = getString(
                    if (notificationAccessGranted) {
                        R.string.ui_media_streams_empty_hint
                    } else {
                        R.string.ui_enable_notif_access_hint
                    }
                )
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
            val source = mediaSourceCard(controller, controller.packageName == selectedPackage)
            players += source
            val sourceCard = source.view.apply {
                setTag(R.id.interaction_anchor, "media.player.${controller.packageName}")
            }
            // Keep the press feedback on the card independent from its entrance transform.
            val cardHolder = FrameLayout(activity).apply {
                layoutParams = sourceCard.layoutParams
                clipChildren = false
                clipToPadding = false
                addView(sourceCard, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            playerEntrance?.bind(controller.packageName, cardHolder, dp(AirUiTokens.Motion.MediaPlayerEnterRiseDp).toFloat())
            container.addView(cardHolder)
        }
    }

    container.addView(spacer(activity, 18))
    container.addView(refreshMediaButton())

    container.getChildAt(0).setTag(R.id.interaction_anchor, "media.heading")
    container.getChildAt(1).setTag(R.id.interaction_anchor, "media.current")
    container.getChildAt(3).setTag(R.id.interaction_anchor, "media.players.heading")
    container.getChildAt(container.childCount - 1).setTag(R.id.interaction_anchor, "media.refresh")
    val page = com.andsi.airlyrics.ui.layout.ResponsivePage(activity, container, setOf(0, 1), "scroll.MEDIA.root")
    onBound?.invoke(MediaPageBinding(page, controllers.mapTo(mutableSetOf()) { it.packageName }, currentMedia, players))
    return page
}

internal fun decodeMediaArtwork(context: Context, uri: Uri, targetSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val resolver = context.contentResolver
        val boundsInput = resolver.openInputStream(uri) ?: return null
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetSize &&
            bounds.outHeight / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: Exception) {
        null
    }
}
