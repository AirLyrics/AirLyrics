package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.app.state.MainFloatingState
import com.andsi.airlyrics.R

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.floating.FloatingLyricsService
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal class FloatingController(
    private val context: Context,
    private val state: MainFloatingState,
    private val invalidator: UiInvalidator,
    private val serviceStarter: MainServiceStarter,
    private val overlayPermissionRequester: OverlayPermissionRequester,
    private val navFeedback: FloatingNavFeedback
) {
    private var overlayPermissionHintShown = false

    fun handleWindowStateBroadcast(intent: Intent) {
        val action = intent.action ?: return
        if (action != BroadcastActions.WINDOW_VISIBILITY_CHANGED &&
            action != BroadcastActions.QUICK_CONTROL_CHANGED
        ) return

        val previousVisible = state.quickFloatingVisible
        val previousLocked = state.locked
        val previousClickThrough = state.clickThrough
        val visible = intent.getBooleanExtra(
            BroadcastActions.EXTRA_WINDOW_VISIBLE,
            state.quickFloatingVisible
        )
        val locked = intent.getBooleanExtra(
            BroadcastActions.EXTRA_LOCKED,
            FloatingLyricsStyleStore.isLocked(context)
        )
        val clickThrough = intent.getBooleanExtra(
            BroadcastActions.EXTRA_CLICK_THROUGH,
            FloatingLyricsStyleStore.isClickThrough(context)
        )
        state.locked = locked
        state.clickThrough = clickThrough
        updateQuickFloatingActualVisible(visible)

        val floatingStateChanged = previousVisible != visible ||
            previousLocked != locked ||
            previousClickThrough != clickThrough
        if (action == BroadcastActions.WINDOW_VISIBILITY_CHANGED || floatingStateChanged) {
            invalidator.refreshFloatingState()
        }
    }

    fun reloadLyrics() {
        if (!state.quickFloatingVisible) return
        sendFloatingCommand(BroadcastActions.RELOAD_LYRICS)
    }

    fun applyLyricsOffset(offsetMs: Long) {
        if (!state.quickFloatingVisible) return

        sendFloatingCommand(BroadcastActions.APPLY_LYRICS_OFFSET) {
            putExtra(BroadcastActions.EXTRA_LYRICS_OFFSET_MS, offsetMs)
        }
    }

    fun reloadLyricsFromOnline() {
        if (!state.quickFloatingVisible) {
            Toast.makeText(context, context.getString(R.string.ui_show_overlay_then_search_hint), Toast.LENGTH_LONG).show()
            return
        }

        if (sendFloatingCommand(BroadcastActions.RELOAD_ONLINE_LYRICS)) {
            Toast.makeText(context, context.getString(R.string.ui_searching_online_again), Toast.LENGTH_SHORT).show()
        }
    }

    fun showLyrics(): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            if (!overlayPermissionHintShown) {
                Toast.makeText(context, context.getString(R.string.ui_enable_overlay_permission_first), Toast.LENGTH_LONG).show()
                overlayPermissionHintShown = true
            }
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            invalidator.refreshFloatingState()
            overlayPermissionRequester.requestOverlayPermission()
            return false
        }

        setDesiredVisible(true)
        val sent = sendFloatingCommand(BroadcastActions.SHOW)
        if (!sent) {
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            invalidator.refreshFloatingState()
        }
        return sent
    }

    fun hideLyrics(): Boolean {
        setDesiredVisible(false)
        val sent = sendFloatingCommand(BroadcastActions.HIDE)
        if (!sent) {
            Toast.makeText(context, context.getString(R.string.ui_overlay_update_failed), Toast.LENGTH_SHORT).show()
            invalidator.refreshFloatingState()
        }
        return sent
    }

    fun restoreVisibleWindowIfNeeded() {
        if (!QuickFloatingStore.isDesiredVisible(context)) return

        if (!Settings.canDrawOverlays(context)) {
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            invalidator.refreshFloatingState()
            return
        }

        val sent = sendFloatingCommand(BroadcastActions.SHOW)
        if (!sent) {
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            invalidator.refreshFloatingState()
        }
    }

    fun toggleFromNav() {
        navFeedback.playFloatingNavToggleFeedback()

        if (state.quickFloatingVisible) {
            hideLyrics()
        } else {
            showLyrics()
        }
    }

    fun updateQuickFloatingActualVisible(visible: Boolean) {
        state.quickFloatingVisible = visible
    }

    private fun setDesiredVisible(visible: Boolean) {
        QuickFloatingStore.setDesiredVisible(context, visible)
    }

    fun toggleLock() {
        val nextLocked = !FloatingLyricsStyleStore.isLocked(context)

        if (!state.quickFloatingVisible) {
            state.locked = nextLocked
            FloatingLyricsStyleStore.setLocked(context, nextLocked)
            invalidator.refresh()
            return
        }

        if (!sendFloatingCommand(if (nextLocked) BroadcastActions.LOCK else BroadcastActions.UNLOCK)) {
            Toast.makeText(context, context.getString(R.string.ui_overlay_update_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleClickThrough() {
        val nextClickThrough = !FloatingLyricsStyleStore.isClickThrough(context)

        if (!state.quickFloatingVisible) {
            state.clickThrough = nextClickThrough
            FloatingLyricsStyleStore.setClickThrough(context, nextClickThrough)
            invalidator.refresh()
            return
        }

        val sent = sendFloatingCommand(
            if (nextClickThrough) {
                BroadcastActions.CLICK_THROUGH_ON
            } else {
                BroadcastActions.CLICK_THROUGH_OFF
            }
        )
        if (!sent) {
            Toast.makeText(context, context.getString(R.string.ui_overlay_update_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun applyPreset(preset: String) {
        FloatingLyricsStyleStore.applyPreset(context, preset)
        notifyStyleChanged()
    }

    fun applyTextSize(textSizeSp: Float, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextSize(context, textSizeSp)
        notifyStyleChanged()
        if (refreshPage) invalidator.refresh()
    }

    fun applyTextColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextColor(context, color)
        notifyStyleChanged()
        if (refreshPage) invalidator.refresh()
    }

    fun applyBackgroundColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setBackgroundColor(context, color)
        notifyStyleChanged()
        if (refreshPage) invalidator.refresh()
    }

    fun applyGravity(gravity: Int) {
        FloatingLyricsStyleStore.setGravity(context, gravity)
        notifyStyleChanged()
    }

    fun notifyStyleChanged() {
        if (!state.quickFloatingVisible) return
        sendFloatingCommand(BroadcastActions.APPLY_STYLE)
    }

    fun notifySourceChangedIfVisible(packageName: String?) {
        if (!state.quickFloatingVisible) return

        sendFloatingCommand(BroadcastActions.SELECT_MEDIA_SOURCE) {
            putExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE, packageName)
        }
    }

    private fun sendFloatingCommand(
        action: String,
        configure: Intent.() -> Unit = {}
    ): Boolean {
        val intent = Intent(context, FloatingLyricsService::class.java).apply {
            this.action = action
            configure()
        }
        return serviceStarter.startLyricsServiceSafely(intent)
    }
}
