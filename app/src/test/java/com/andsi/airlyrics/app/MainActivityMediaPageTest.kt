package com.andsi.airlyrics.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.mediaArtworkRefreshKey
import com.andsi.airlyrics.app.viewmodel.MainForegroundStateReader
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.pages.media.createMediaPage
import com.andsi.airlyrics.ui.pages.media.MediaRollingTextSlot
import com.andsi.airlyrics.ui.pages.media.MediaArtworkSwap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.math.roundToInt

// ShadowMediaController's metadata field is protected; use its public setter.
@Suppress("UsePropertyAccessSyntax")
@RunWith(RobolectricTestRunner::class)
class MainActivityMediaPageTest {
    @Test
    fun neteasePageKeepsSongAndArtistWhenBluetoothLyricsChange() {
        withPlayer("com.netease.cloudmusic") { host, controller ->
            for (lyric in listOf("first lyric", "next lyric", "")) {
                shadowOf(controller).setMetadata(metadata(lyric, "Song - Artist", "Song", "Artist"))
                assertSongDisplayed(host, "Song", "Artist")
            }
        }
    }

    @Test
    fun neteasePageFallsBackToStandardPairWhenEitherDisplayFieldIsMissing() {
        withPlayer("com.netease.cloudmusic") { host, controller ->
            for ((title, artist) in listOf(null to "Display artist", "Display song" to null)) {
                shadowOf(controller).setMetadata(metadata("Standard song", "Standard artist", title, artist))
                assertSongDisplayed(host, "Standard song", "Standard artist")
            }
        }
    }

    @Test
    fun otherPlayerPageUsesStandardMetadata() {
        withPlayer("other.player") { host, controller ->
            shadowOf(controller).setMetadata(metadata("Standard song", "Standard artist", "Display song", "Display artist"))
            assertSongDisplayed(host, "Standard song", "Standard artist")
        }
    }

    @Test
    fun selectedArtworkKeepsBitmapPriorityAndUriFallback() {
        withPlayer("other.player") { host, controller ->
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val uri = Uri.parse("content://artwork/current")
            shadowOf(controller).setMetadata(MediaMetadata.Builder()
                .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
                .putString(MediaMetadata.METADATA_KEY_ART_URI, uri.toString())
                .build())

            val bitmapState = host.mediaPageState()
            assertSame(bitmap, bitmapState.selectedArtworkBitmap)
            assertNull(bitmapState.selectedArtworkUri)
            val artworkView = descendants(createMediaPage(host, animateContent = false))
                .filterIsInstance<ImageView>()
                .single { it.scaleType == ImageView.ScaleType.CENTER_CROP }
            assertEquals(View.VISIBLE, artworkView.visibility)
            assertSame(bitmap, (artworkView.drawable as BitmapDrawable).bitmap)

            shadowOf(controller).setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_ART_URI, uri.toString())
                .build())
            val uriState = host.mediaPageState()
            assertNull(uriState.selectedArtworkBitmap)
            assertEquals(uri, uriState.selectedArtworkUri)
        }
    }

    @Test
    fun sameArtworkPixelsDoNotCrossfadeAfterRefresh() {
        withPlayer("other.player") { host, controller ->
            fun metadataWithArtwork(image: Bitmap) = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .putBitmap(MediaMetadata.METADATA_KEY_ART, image)
                .build()
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
            shadowOf(controller).setMetadata(metadataWithArtwork(bitmap))
            val page = createMediaPage(host, animateContent = false)
            host.contentContainer!!.addView(page)
            val artwork = descendants(page).filterIsInstance<MediaArtworkSwap>().single()
            val samePixels = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val foreground = MainForegroundStateReader(host)
            val beforeRefresh = foreground.read().media

            assertTrue(bitmap !== samePixels)
            assertEquals(bitmap.mediaArtworkRefreshKey(), samePixels.mediaArtworkRefreshKey())
            shadowOf(controller).setMetadata(metadataWithArtwork(samePixels))
            assertEquals(beforeRefresh, foreground.read().media)
            artwork.show(samePixels, null, "other.player\u0000Song\u0000Artist")
            assertEquals(1, artwork.childCount)

            val changedPixels = bitmap.copy(Bitmap.Config.ARGB_8888, true).apply {
                setPixel(0, 0, Color.CYAN)
            }
            shadowOf(controller).setMetadata(metadataWithArtwork(changedPixels))
            assertTrue(beforeRefresh != foreground.read().media)
            artwork.show(changedPixels, null, "other.player\u0000Song\u0000Artist")
            assertEquals(2, artwork.childCount)
        }
    }

    @Test
    fun playerCardRemainsClickableInsideEntranceHolder() {
        withPlayer("other.player") { host, controller ->
            shadowOf(controller).setMetadata(metadata("Song", "Artist", null, null))
            val page = createMediaPage(host, animateContent = false)
            val card = descendants(page).single {
                it.getTag(com.andsi.airlyrics.R.id.interaction_anchor) ==
                    "media.player.${controller.packageName}"
            }

            MediaSourceStore.saveSelectedPackage(host, null)
            assertTrue(card.performClick())
            assertEquals(controller.packageName, MediaSourceStore.getSelectedPackage(host))
        }
    }

    @Test
    fun contentRefreshKeepsMountedPlayerCardAndShowsLatestSong() {
        withPlayer("other.player") { host, controller ->
            shadowOf(controller).setMetadata(metadata("First song", "First artist", null, null))
            host.refreshMediaContent()
            val container = host.contentContainer!!
            val page = container.getChildAt(0)
            val card = descendants(page).single {
                it.getTag(com.andsi.airlyrics.R.id.interaction_anchor) ==
                    "media.player.${controller.packageName}"
            }
            val icon = descendants(card).filterIsInstance<ImageView>().single {
                it.layoutParams.width == host.dp(40)
            }
            measurePage(page)

            shadowOf(controller).setMetadata(metadata("Second song", "Second artist", null, null))
            host.refreshMediaContent()
            host.refreshMediaContent() // The same MediaSession data must not replace the view again.
            measurePage(page)
            val playerSong = descendants(card).filterIsInstance<MediaRollingTextSlot>().single()
            assertEquals("Player song roll was cancelled by layout", 0f, playerSong.textView.alpha)

            assertSame(page, container.getChildAt(0))
            assertSame(card, descendants(page).single {
                it.getTag(com.andsi.airlyrics.R.id.interaction_anchor) ==
                    "media.player.${controller.packageName}"
            })
            assertSame(icon, descendants(card).filterIsInstance<ImageView>().single {
                it.layoutParams.width == host.dp(40)
            })
            val texts = descendants(page).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("Latest song missing from page: $texts", "Second song" in texts)
            assertTrue("Latest player song missing from page: $texts", "Second song - Second artist" in texts)
            assertTrue(card.performClick())
        }
    }

    @Test
    fun rollingTitleKeepsOneViewAndSettlesAcrossLongShortAndRapidChanges() {
        withPlayer("other.player") { host, controller ->
            shadowOf(controller).setMetadata(metadata("Blue Zenith", "xi", null, null))
            host.refreshMediaContent()
            val page = host.contentContainer!!.getChildAt(0)
            measurePage(page)
            val slot = descendants(page).filterIsInstance<MediaRollingTextSlot>()
                .single { it.textView.text.toString() == "Blue Zenith" }
            val shortHeight = slot.measuredHeight
            val longTitle = "物凄い狂っとるフランちゃんが\n物凄いうたを歌う"

            shadowOf(controller).setMetadata(metadata(longTitle, "ななひら", null, null))
            host.refreshMediaContent()
            measurePage(page)
            assertEquals("Rolling text was cancelled by the next layout pass", 0f, slot.textView.alpha)
            assertSame(slot, descendants(page).filterIsInstance<MediaRollingTextSlot>()
                .single { it.textView.text.toString() == longTitle })
            assertEquals(1, slot.childCount)
            slot.finish()
            measurePage(page)
            assertTrue("Two-line title did not increase its natural height", slot.measuredHeight > shortHeight)

            shadowOf(controller).setMetadata(metadata("Go", "new artist", null, null))
            host.refreshMediaContent()
            shadowOf(controller).setMetadata(metadata("夜に駆ける 👩‍🎤", "artist", null, null))
            host.refreshMediaContent()
            shadowOf(controller).setMetadata(metadata("UP", "artist", null, null))
            host.refreshMediaContent()
            assertEquals("UP", slot.textView.text.toString())
            shadowOf(controller).setMetadata(metadata("Go", "artist", null, null))
            host.refreshMediaContent()
            host.refreshMediaContent()
            assertEquals("Go", slot.textView.text.toString())
            assertEquals(1, slot.childCount)
            slot.finish()
            measurePage(page)
            assertEquals(shortHeight, slot.measuredHeight)
        }
    }

    @Test
    fun artistAndPlaybackOnlyUpdatesLeaveTitleUntouched() {
        withPlayer("other.player") { host, controller ->
            shadowOf(controller).setMetadata(metadata("Same song", "First artist", null, null))
            host.refreshMediaContent()
            val page = host.contentContainer!!.getChildAt(0)
            measurePage(page)
            val title = descendants(page).filterIsInstance<MediaRollingTextSlot>()
                .single { it.textView.text.toString() == "Same song" }
            val playerSong = descendants(page).filterIsInstance<MediaRollingTextSlot>()
                .single { it.textView.text.toString() == "Same song - First artist" }

            shadowOf(controller).setMetadata(metadata("Same song", "Second artist", null, null))
            host.refreshMediaContent()
            assertEquals("Same song", title.textView.text.toString())
            assertEquals(1f, title.textView.alpha)
            assertEquals("Same song - Second artist", playerSong.textView.text.toString())

            shadowOf(controller).setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
            host.refreshMediaContent()
            assertEquals("Same song", title.textView.text.toString())
            assertEquals(1f, title.textView.alpha)
            assertEquals("Same song - Second artist", playerSong.textView.text.toString())
        }
    }

    private fun measurePage(page: View) {
        val density = page.resources.displayMetrics.density
        val width = (360 * density).roundToInt()
        val height = (720 * density).roundToInt()
        page.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        page.layout(0, 0, width, height)
    }

    private fun assertSongDisplayed(host: MainUiHost, title: String, artist: String) {
        val texts = descendants(createMediaPage(host, animateContent = false))
            .filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue("Song missing from page: $texts", title in texts)
        assertTrue("Artist missing from page: $texts", texts.any { it.startsWith("$artist · ") })
    }

    private fun withPlayer(packageName: String, block: (MainUiHost, MediaController) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val session = MediaSession(context, "media-page-test")
        val controller = MediaController(context, session.sessionToken)
        shadowOf(controller).setPackageName(packageName)
        shadowOf(manager).addController(controller)
        MediaSourceStore.saveSelectedPackage(context, packageName)
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().use { activity ->
                block(activity.get().graph.uiHost, controller)
            }
        } finally {
            shadowOf(manager).clearControllers()
            session.release()
        }
    }

    private fun metadata(title: String, artist: String, displayTitle: String?, displayArtist: String?) =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, displayTitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, displayArtist)
            .build()
}
