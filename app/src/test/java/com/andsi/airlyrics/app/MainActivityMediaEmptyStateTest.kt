package com.andsi.airlyrics.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.media.MediaNotificationListenerService
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.layout.WindowLayoutSpec
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.RefreshState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN")
class MainActivityMediaEmptyStateTest {
    @Test
    fun permissionButtonOpensSettingsAndReturningRechecksAccess() {
        withEmptyMediaPage(accessGranted = false) { controller ->
            val activity = controller.get()
            val host = activity.graph.uiHost
            assertEmptyPage(host, accessGranted = false)

            assertTrue(anchor(page(host), "media.notification_access")!!.performClick())
            ShadowLooper.idleMainLooper()
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                shadowOf(activity).nextStartedActivity.action)

            controller.pause().stop()
            setNotificationAccess(activity, true)
            controller.start().resume()
            ShadowLooper.idleMainLooper()
            assertTrue(host.hasNotificationListenerAccess())
            assertEmptyPage(host, accessGranted = true)

            assertTrue(anchor(page(host), "media.refresh")!!.performClick())
            assertEquals(RefreshState.REFRESHING, host.mediaRefreshState)
            ShadowLooper.idleMainLooper(
                AirUiTokens.Layout.MediaRefreshingMs + AirUiTokens.Layout.MediaDoneRefreshMs,
                TimeUnit.MILLISECONDS
            )
            assertEmptyPage(host, accessGranted = true)

            controller.pause().stop()
            setNotificationAccess(activity, false)
            controller.start().resume()
            ShadowLooper.idleMainLooper()
            assertFalse(host.hasNotificationListenerAccess())
            assertEmptyPage(host, accessGranted = false)
        }
    }

    @Test
    fun detectedTrackRestoresPlayersAndPausedTrackKeepsItsPage() {
        withEmptyMediaPage(accessGranted = true) { activityController ->
            val activity = activityController.get()
            val host = activity.graph.uiHost
            val manager = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            assertEmptyPage(host, accessGranted = true)

            val session = MediaSession(activity, "empty-media-transition")
            try {
                val controller = MediaController(activity, session.sessionToken)
                shadowOf(controller).setPackageName("test.player")
                @Suppress("UsePropertyAccessSyntax")
                shadowOf(controller).setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                    .build())
                fun playback(state: Int) {
                    shadowOf(controller).setPlaybackState(PlaybackState.Builder()
                        .setState(state, 0L, 1f).build())
                    host.refreshMediaContent()
                }

                shadowOf(manager).addController(controller)
                playback(PlaybackState.STATE_PLAYING)
                val trackPage = page(host)
                val playerCardKey = "media.player.test.player"
                val playing = host.getPlaybackStateText(PlaybackState.STATE_PLAYING)
                assertTrue("Song" in cardTexts(trackPage))
                assertFalse(playing in cardTexts(trackPage))
                assertNotNull(anchor(trackPage, "media.players.heading"))
                assertTrue(playing in cardTexts(trackPage, playerCardKey))
                assertNotNull(anchor(trackPage, "media.refresh"))
                assertNull(anchor(trackPage, "media.notification_access"))

                playback(PlaybackState.STATE_PAUSED)
                ShadowLooper.idleMainLooper(AirUiTokens.Motion.MediaStatusSwapMs + 32L, TimeUnit.MILLISECONDS)
                val paused = host.getPlaybackStateText(PlaybackState.STATE_PAUSED)
                assertSame(trackPage, page(host))
                assertTrue("Song" in cardTexts(trackPage))
                assertFalse(paused in cardTexts(trackPage))
                assertTrue(paused in cardTexts(trackPage, playerCardKey))

                shadowOf(manager).clearControllers()
                host.refreshMediaContent()
                assertEmptyPage(host, accessGranted = true)
            } finally {
                session.release()
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun emptyTitleWrapsAtLargeFontSizesAndRefreshFollowsCardAcrossWidths() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            withEmptyMediaPage(accessGranted = true) { controller ->
                val host = controller.get().graph.uiHost
                val page = page(host)
                val card = anchor(page, "media.current")!!
                val title = descendants(card).filterIsInstance<TextView>()
                    .single { it.text.toString() == "未检测到音乐...现在还没有" }

                fun resize(widthDp: Int, fontScale: Float) {
                    host.windowLayout = WindowLayoutSpec(widthDp.toFloat(), 720f, fontScale)
                    page.measure(
                        View.MeasureSpec.makeMeasureSpec(host.dp(widthDp), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(host.dp(720), View.MeasureSpec.EXACTLY)
                    )
                    page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                    val refresh = anchor(page, "media.refresh")!!
                    assertEquals(anchor(page, "media.heading")!!.width, card.width)
                    assertEquals(minOf(host.dp(widthDp), host.dp(1160)) - host.dp(if (widthDp >= 900) 48 else 40), card.width)
                    val parent = card.parent as ViewGroup
                    assertSame(parent, refresh.parent)
                    assertEquals(parent.indexOfChild(card) + 1, parent.indexOfChild(refresh))
                    val cardParams = card.layoutParams as ViewGroup.MarginLayoutParams
                    val refreshParams = refresh.layoutParams as ViewGroup.MarginLayoutParams
                    assertEquals(cardParams.bottomMargin + refreshParams.topMargin,
                        refresh.top - card.bottom)
                    val layout = title.layout
                    assertEquals(title.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
                }

                resize(320, 2f)
                assertTrue(title.lineCount > 1)
                val narrowHeight = card.height
                resize(360, 2f)
                resize(1200, 1f)
                assertTrue("Empty card retained unused height", card.height < narrowHeight)
                assertEmptyPage(host, accessGranted = true)
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun withEmptyMediaPage(
        accessGranted: Boolean,
        block: (ActivityController<MainActivity>) -> Unit
    ) {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val previousAccess = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        shadowOf(manager).clearControllers()
        setNotificationAccess(context, accessGranted)
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().use(block)
        } finally {
            shadowOf(manager).clearControllers()
            Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", previousAccess)
        }
    }

    private fun setNotificationAccess(context: Context, granted: Boolean) {
        val listener = ComponentName(context, MediaNotificationListenerService::class.java)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            if (granted) listener.flattenToString() else "")
    }

    private fun page(host: MainUiHost): View = host.contentContainer!!.getChildAt(0)

    private fun anchor(root: View, key: String): View? =
        descendants(root).singleOrNull { it.getTag(R.id.interaction_anchor) == key }

    private fun cardTexts(page: View, cardKey: String = "media.current"): List<String> =
        descendants(requireNotNull(anchor(page, cardKey)) { "Missing card $cardKey" })
            .filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun assertEmptyPage(host: MainUiHost, accessGranted: Boolean) {
        val page = page(host)
        assertEquals(
            if (accessGranted) listOf("当前媒体", "未检测到音乐...现在还没有")
            else listOf("当前媒体", "未检测到播放器"),
            cardTexts(page)
        )
        assertNull(anchor(page, "media.players.heading"))
        assertEquals(accessGranted, anchor(page, "media.refresh") != null)
        assertEquals(!accessGranted, anchor(page, "media.notification_access") != null)
        val current = requireNotNull(anchor(page, "media.current"))
        val actionKey = if (accessGranted) "media.refresh" else "media.notification_access"
        val action = requireNotNull(anchor(page, actionKey))
        val parent = current.parent as ViewGroup
        assertSame(parent, action.parent)
        assertEquals(parent.indexOfChild(current) + 1, parent.indexOfChild(action))
        if (!accessGranted) {
            assertEquals("去开启通知访问权限", (action as TextView).text.toString())
        }
    }
}
