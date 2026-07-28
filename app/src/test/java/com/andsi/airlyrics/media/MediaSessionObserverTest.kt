package com.andsi.airlyrics.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaController
import org.robolectric.shadows.ShadowMediaSessionManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaSessionObserverTest {

    private lateinit var context: Context
    private lateinit var mainHandler: Handler
    private lateinit var receiver: BroadcastReceiver
    private val receivedIntents = mutableListOf<Intent>()
    private var observer: MediaSessionObserver? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mainHandler = Handler(Looper.getMainLooper())
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.let { receivedIntents += Intent(it) }
                }
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            CurrentMediaBroadcast.mediaStatusFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @After
    fun tearDown() {
        observer?.stop()
        mediaSessionManagerShadow().clearControllers()
        idleMainLooper()
        context.unregisterReceiver(receiver)
    }

    @Test
    fun mediaControllerFixture_deliversProductionCallbacksOnMainLooper() {
        val controller =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Initial"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val controllerShadow = Shadow.extract<ShadowMediaController>(controller)
        observer = createBroadcastingObserver()

        observer!!.refresh(listOf(controller))
        idleMainLooper()
        receivedIntents.clear()

        assertEquals(1, controllerShadow.callbacks.size)
        controllerShadow.executeOnMetadataChanged(metadata(title = "Updated"))
        controllerShadow.executeOnPlaybackStateChanged(
            playbackState(PlaybackState.STATE_PAUSED),
        )
        controllerShadow.executeOnSessionDestroyed()

        assertTrue(receivedIntents.isEmpty())
        idleMainLooper()

        val updates =
            receivedIntents
                .mapNotNull(CurrentMediaBroadcast::readMediaUpdate)
        assertTrue(updates.isNotEmpty())
        assertEquals("Updated", updates.last().title)
        assertFalse(updates.last().isPlaying)
        assertEquals(
            listOf(TEST_PACKAGE),
            receivedIntents
                .mapNotNull(CurrentMediaBroadcast::readMediaSourceLost),
        )
        assertTrue(controllerShadow.callbacks.isEmpty())
    }

    @Test
    fun metadataBecomesInvalid_afterPublishedMedia_emitsSourceLostOnce() {
        val controller =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Initially valid"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val controllerShadow = Shadow.extract<ShadowMediaController>(controller)
        observer = createBroadcastingObserver()
        observer!!.refresh(listOf(controller))
        idleMainLooper()
        assertEquals("Initially valid", mediaUpdates().single().title)
        receivedIntents.clear()

        controllerShadow.executeOnMetadataChanged(metadata(title = " "))
        idleMainLooper()

        assertEquals(listOf(TEST_PACKAGE), lostPackages())
        assertTrue(mediaUpdates().isEmpty())

        controllerShadow.executeOnMetadataChanged(metadata(title = ""))
        idleMainLooper()

        assertEquals(listOf(TEST_PACKAGE), lostPackages())
        assertTrue(mediaUpdates().isEmpty())
    }

    @Test
    fun pausedMedia_withValidMetadata_emitsUpdateNotSourceLost() {
        val controller =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Still valid"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val controllerShadow = Shadow.extract<ShadowMediaController>(controller)
        observer = createBroadcastingObserver()
        observer!!.refresh(listOf(controller))
        idleMainLooper()
        receivedIntents.clear()

        controllerShadow.executeOnPlaybackStateChanged(
            playbackState(PlaybackState.STATE_PAUSED),
        )
        idleMainLooper()

        val media = mediaUpdates().single()
        assertEquals("Still valid", media.title)
        assertFalse(media.isPlaying)
        assertTrue(lostPackages().isEmpty())
    }

    @Test
    fun observerStart_registersListenerAndPublishesInitialSession() {
        val initialController =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Initial active session"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val managerShadow = mediaSessionManagerShadow()
        managerShadow.addController(initialController)
        observer = createBroadcastingObserver()

        observer!!.start()
        idleMainLooper()

        assertEquals(
            listOf("Initial active session"),
            mediaUpdates().map(CurrentMediaInfo::title),
        )
        assertEquals(
            1,
            Shadow.extract<ShadowMediaController>(initialController).callbacks.size,
        )

        receivedIntents.clear()
        val subsequentlyActiveController =
            createController(
                packageName = SECOND_PACKAGE,
                metadata = metadata(title = "Listener delivered session"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        managerShadow.addController(subsequentlyActiveController)
        idleMainLooper()

        assertTrue(
            mediaUpdates().any {
                it.sourcePackage == SECOND_PACKAGE &&
                    it.title == "Listener delivered session"
            },
        )
    }

    @Test
    fun sessionReplacement_unregistersOldCallbackAndOnlyNewSessionPublishes() {
        val oldController =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Old session"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val newController =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "New session"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val oldShadow = Shadow.extract<ShadowMediaController>(oldController)
        val newShadow = Shadow.extract<ShadowMediaController>(newController)
        observer = createBroadcastingObserver()
        observer!!.refresh(listOf(oldController))
        idleMainLooper()
        receivedIntents.clear()

        oldShadow.executeOnMetadataChanged(metadata(title = "Late old event"))
        observer!!.refresh(listOf(newController))
        idleMainLooper()

        assertTrue(oldShadow.callbacks.isEmpty())
        assertEquals(1, newShadow.callbacks.size)
        assertTrue(mediaUpdates().isNotEmpty())
        assertTrue(mediaUpdates().all { it.title == "New session" })
        assertEquals("New session", mediaUpdates().last().title)
        assertTrue(lostPackages().isEmpty())
    }

    @Test
    fun sourceLost_publishesTerminalMediaState() {
        val controller =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Published session"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        observer = createBroadcastingObserver()
        observer!!.refresh(listOf(controller))
        idleMainLooper()
        receivedIntents.clear()

        observer!!.refresh(emptyList())
        idleMainLooper()

        assertEquals(listOf(TEST_PACKAGE), lostPackages())
        assertTrue(mediaUpdates().isEmpty())

        observer!!.refresh(emptyList())
        idleMainLooper()

        assertEquals(listOf(TEST_PACKAGE), lostPackages())
    }

    @Test
    fun observerStop_unregistersManagerAndControllerCallbacks() {
        val controller =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Published session"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val controllerShadow = Shadow.extract<ShadowMediaController>(controller)
        mediaSessionManagerShadow().addController(controller)
        observer = createBroadcastingObserver()
        observer!!.start()
        idleMainLooper()
        receivedIntents.clear()

        observer!!.stop()
        idleMainLooper()

        assertTrue(controllerShadow.callbacks.isEmpty())
        assertEquals(listOf(TEST_PACKAGE), lostPackages())
        assertTrue(mediaUpdates().isEmpty())

        observer!!.stop()
        idleMainLooper()

        assertEquals(listOf(TEST_PACKAGE), lostPackages())
    }

    @Test
    fun eventsAfterStop_doNotPublish() {
        val controller =
            createController(
                packageName = TEST_PACKAGE,
                metadata = metadata(title = "Published session"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            )
        val controllerShadow = Shadow.extract<ShadowMediaController>(controller)
        val managerShadow = mediaSessionManagerShadow()
        managerShadow.addController(controller)
        observer = createBroadcastingObserver()
        observer!!.start()
        idleMainLooper()
        val registeredCallback = controllerShadow.callbacks.single()
        receivedIntents.clear()

        observer!!.stop()
        idleMainLooper()
        assertEquals(listOf(TEST_PACKAGE), lostPackages())
        receivedIntents.clear()

        managerShadow.addController(
            createController(
                packageName = SECOND_PACKAGE,
                metadata = metadata(title = "After stop"),
                playbackState = playbackState(PlaybackState.STATE_PLAYING),
            ),
        )
        controllerShadow.executeOnMetadataChanged(metadata(title = "Framework late event"))
        registeredCallback.onMetadataChanged(metadata(title = "Adversarial late event"))
        idleMainLooper()

        assertTrue(controllerShadow.callbacks.isEmpty())
        assertTrue(receivedIntents.isEmpty())
    }

    private fun createBroadcastingObserver(): MediaSessionObserver =
        MediaSessionObserver(
            context = context,
            handler = mainHandler,
            listener =
                object : MediaSessionObserver.Listener {
                    override fun onCurrentMediaChanged(media: CurrentMediaInfo) {
                        context.sendBroadcast(
                            CurrentMediaBroadcast.mediaUpdateIntent(context, media),
                        )
                    }

                    override fun onMediaSourceLost(packageName: String) {
                        CurrentMediaBroadcast.mediaSourceLostIntent(context, packageName)
                            ?.let(context::sendBroadcast)
                    }

                    override fun onObservationError(message: String, error: Throwable) {
                        throw AssertionError(message, error)
                    }
                },
        )

    private fun createController(
        packageName: String,
        metadata: MediaMetadata,
        playbackState: PlaybackState,
    ): MediaController {
        @Suppress("UNCHECKED_CAST")
        val sessionControllerClass =
            Class.forName("android.media.session.ISessionController") as Class<Any>
        val sessionController = ReflectionHelpers.createDeepProxy(sessionControllerClass)
        val token =
            ReflectionHelpers.callConstructor(
                MediaSession.Token::class.java,
                ReflectionHelpers.ClassParameter.from(
                    Int::class.javaPrimitiveType!!,
                    nextTokenId.incrementAndGet(),
                ),
                ReflectionHelpers.ClassParameter.from(
                    sessionControllerClass,
                    sessionController,
                ),
            )
        val controller = MediaController(context, token)
        Shadow.extract<ShadowMediaController>(controller).apply {
            setPackageName(packageName)
            setMetadata(metadata)
            setPlaybackState(playbackState)
        }
        return controller
    }

    private fun metadata(title: String): MediaMetadata =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
            .build()

    private fun playbackState(state: Int): PlaybackState =
        PlaybackState.Builder()
            .setState(state, 12_345L, 1f)
            .build()

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun mediaSessionManagerShadow(): ShadowMediaSessionManager {
        val manager =
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return Shadow.extract(manager)
    }

    private fun mediaUpdates(): List<CurrentMediaInfo> =
        receivedIntents.mapNotNull(CurrentMediaBroadcast::readMediaUpdate)

    private fun lostPackages(): List<String> =
        receivedIntents.mapNotNull(CurrentMediaBroadcast::readMediaSourceLost)

    companion object {
        private const val TEST_PACKAGE = "com.example.fixture"
        private const val SECOND_PACKAGE = "com.example.fixture.second"
        private val nextTokenId = AtomicInteger(10_000)
    }
}
