package com.andsi.airlyrics.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import com.andsi.airlyrics.media.model.CurrentMediaInfo

internal class MediaSessionObserver(
    context: Context,
    private val handler: Handler,
    private val listener: Listener
) {
    interface Listener {
        fun onCurrentMediaChanged(media: CurrentMediaInfo)
        fun onMediaSourceLost(packageName: String)
        fun onObservationError(message: String, error: Throwable)
    }

    private val appContext = context.applicationContext
    private val mediaSessionManager by lazy {
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private val notificationListenerComponent
        get() = ComponentName(appContext, MediaNotificationListenerService::class.java)

    private val observedControllers = linkedMapOf<MediaSession.Token, ObservedController>()
    private val publishedControllerTokens = mutableMapOf<String, MediaSession.Token>()
    private var activeSessionsListenerRegistered = false

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            refresh(controllers)
        }

    fun start() {
        if (!activeSessionsListenerRegistered) {
            runCatching {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    activeSessionsChangedListener,
                    notificationListenerComponent,
                    handler
                )
                activeSessionsListenerRegistered = true
            }.onFailure { e ->
                listener.onObservationError("Failed to listen active media sessions", e)
            }
        }

        refresh()
    }

    fun refresh(activeSessions: List<MediaController>? = null) {
        runCatching {
            val sessions = activeSessions
                ?: mediaSessionManager.getActiveSessions(notificationListenerComponent)
            updateObservedControllers(sessions)
        }.onFailure { e ->
            listener.onObservationError("Failed to refresh media sessions", e)
        }
    }

    fun stop() {
        if (activeSessionsListenerRegistered) {
            runCatching {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
            }.onFailure { e ->
                listener.onObservationError("Failed to remove active media sessions listener", e)
            }
            activeSessionsListenerRegistered = false
        }

        clearObservedControllers()
    }

    private fun updateObservedControllers(activeSessions: List<MediaController>) {
        val activeTokens = activeSessions.map { it.sessionToken }.toSet()

        observedControllers.keys
            .filter { it !in activeTokens }
            .toList()
            .forEach(::removeObservedController)

        activeSessions.forEach(::observeControllerIfNeeded)
        publishBestControllers()
    }

    private fun observeControllerIfNeeded(controller: MediaController) {
        val token = controller.sessionToken
        if (observedControllers.containsKey(token)) return

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                publishBestControllers()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                publishBestControllers()
            }

            override fun onSessionDestroyed() {
                removeObservedController(token)
                publishBestControllers()
            }
        }

        runCatching {
            controller.registerCallback(callback, handler)
            observedControllers[token] = ObservedController(controller, callback)
        }.onFailure { e ->
            listener.onObservationError("Failed to observe media controller", e)
        }
    }

    private fun removeObservedController(token: MediaSession.Token) {
        val observed = observedControllers.remove(token) ?: return
        runCatching {
            observed.controller.unregisterCallback(observed.callback)
        }.onFailure { e ->
            listener.onObservationError("Failed to stop observing media controller", e)
        }
    }

    private fun clearObservedControllers() {
        val lostPackages = publishedControllerTokens.keys.toList()
        observedControllers.keys.toList().forEach(::removeObservedController)
        publishedControllerTokens.clear()
        lostPackages.forEach(listener::onMediaSourceLost)
    }

    private fun publishBestControllers() {
        val bestControllers = CurrentMediaReader.selectedControllersByPackage(
            observedControllers.values.map { it.controller }
        )
        val nextControllerTokens = mutableMapOf<String, MediaSession.Token>()
        val mediaToPublish = mutableListOf<CurrentMediaInfo>()
        bestControllers.forEach { (packageName, controller) ->
            CurrentMediaReader.currentMediaFromController(controller)?.let { media ->
                nextControllerTokens[packageName] = controller.sessionToken
                mediaToPublish += media
            }
        }

        publishedControllerTokens.keys
            .filter { it !in nextControllerTokens }
            .toList()
            .forEach(listener::onMediaSourceLost)

        publishedControllerTokens.clear()
        publishedControllerTokens.putAll(nextControllerTokens)

        mediaToPublish.forEach(listener::onCurrentMediaChanged)
    }

    private data class ObservedController(
        val controller: MediaController,
        val callback: MediaController.Callback
    )
}
