package com.andsi.airlyrics.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.pages.media.createMediaPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityMediaPageTest {
    @Suppress("UsePropertyAccessSyntax")
    @Test
    fun neteasePageKeepsSongAndArtistWhenBluetoothLyricsChange() {
        withPlayer("com.netease.cloudmusic") { host, controller ->
            for (lyric in listOf("first lyric", "next lyric", "")) {
                shadowOf(controller).setMetadata(metadata(lyric, "Song - Artist", "Song", "Artist"))
                assertSongDisplayed(host, "Song", "Artist")
            }
        }
    }

    @Suppress("UsePropertyAccessSyntax")
    @Test
    fun neteasePageFallsBackToStandardPairWhenEitherDisplayFieldIsMissing() {
        withPlayer("com.netease.cloudmusic") { host, controller ->
            for ((title, artist) in listOf(null to "Display artist", "Display song" to null)) {
                shadowOf(controller).setMetadata(metadata("Standard song", "Standard artist", title, artist))
                assertSongDisplayed(host, "Standard song", "Standard artist")
            }
        }
    }

    @Suppress("UsePropertyAccessSyntax")
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
