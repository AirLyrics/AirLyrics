package com.andsi.airlyrics.ui.pages.media

import com.andsi.airlyrics.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.session.PlaybackState
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.media.MediaArtwork
import com.andsi.airlyrics.media.mediaArtwork
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

private const val MEDIA_ARTWORK_SIZE_DP = 72

internal fun createMediaPage(activity: MainUiHost, animateContent: Boolean = true): View  = with(activity) createMediaPage@ {
    val container = pageContainer(activity, animateChanges = animateContent)
    val pageState = mediaPageState()
    val controllers = pageState.controllers
    val selectedPackage = pageState.selectedPackage
    val selectedController = pageState.selectedController
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
                val title = pageState.selectedTitle
                    .orEmpty()
                    .ifBlank { getString(R.string.ui_unknown_song) }
                val artist = pageState.selectedArtist
                    ?: getString(R.string.ui_unknown_artist)
                val appName = getAppName(selectedController.packageName)
                val state = getPlaybackStateText(selectedController.playbackState?.state)
                val artwork = selectedController.metadata.mediaArtwork()
                val titleView = expandableText(bigText(activity, title))
                val artistView = expandableText(normalText(activity, "$artist · $appName"))
                val statusView = statusPill(activity, state, selectedController.playbackState?.state == PlaybackState.STATE_PLAYING)

                if (artwork == null) {
                    addView(titleView)
                    addView(artistView)
                    addView(statusView)
                } else {
                    val artworkView = ImageView(activity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        visibility = View.GONE
                        background = GradientDrawable().apply {
                            cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
                            setColor(colorCard)
                        }
                        clipToOutline = true
                        layoutParams = LinearLayout.LayoutParams(
                            dp(MEDIA_ARTWORK_SIZE_DP), dp(MEDIA_ARTWORK_SIZE_DP)
                        ).apply {
                            marginStart = dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
                        }
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
                            layoutParams = LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                            )
                            addView(titleView)
                            addView(artistView)
                            addView(statusView)
                        })
                        addView(artworkView)
                    })
                    when (artwork) {
                        is MediaArtwork.BitmapImage -> {
                            artworkView.setImageBitmap(artwork.bitmap)
                            artworkView.visibility = View.VISIBLE
                        }
                        is MediaArtwork.UriImage -> {
                            val generation = currentUiGeneration()
                            val artworkSize = dp(MEDIA_ARTWORK_SIZE_DP)
                            runOnAppIo {
                                val bitmap = decodeMediaArtwork(activity, artwork.uri, artworkSize)
                                if (bitmap != null) runOnStartedUi(generation) {
                                    if (artworkView.isAttachedToWindow) {
                                        artworkView.setImageBitmap(bitmap)
                                        artworkView.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    }
                }
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
            container.addView(mediaSourceCard(controller, controller.packageName == selectedPackage).apply {
                setTag(R.id.interaction_anchor, "media.player.${controller.packageName}")
            })
        }
    }

    container.addView(spacer(activity, 18))
    container.addView(refreshMediaButton())

    container.getChildAt(0).setTag(R.id.interaction_anchor, "media.heading")
    container.getChildAt(1).setTag(R.id.interaction_anchor, "media.current")
    container.getChildAt(3).setTag(R.id.interaction_anchor, "media.players.heading")
    container.getChildAt(container.childCount - 1).setTag(R.id.interaction_anchor, "media.refresh")
    return com.andsi.airlyrics.ui.layout.ResponsivePage(activity, container, setOf(0, 1), "scroll.MEDIA.root")
}

private fun decodeMediaArtwork(context: Context, uri: Uri, targetSize: Int): Bitmap? {
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
