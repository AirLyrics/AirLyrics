package com.andsi.airlyrics.app.controller

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.andsi.airlyrics.app.contracts.MainServiceStarter
import com.andsi.airlyrics.app.state.MainFloatingState
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.floating.FloatingServiceCommand
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal enum class FloatingVisibilityOutcome {
    SUCCESS,
    PERMISSION_REQUIRED,
    COMMAND_FAILED
}

/** Applies floating-window commands and persisted settings without owning screen presentation. */
internal class FloatingController(
    context: Context,
    private val state: MainFloatingState,
    private val serviceStarter: MainServiceStarter
) {
    private val appContext = context.applicationContext

    fun reloadLyrics() {
        if (state.quickFloatingDesiredVisible) {
            sendFloatingCommand(FloatingServiceCommand.ReloadLyrics)
        }
    }

    fun applyLyricsOffset(offsetMs: Long) {
        if (state.quickFloatingDesiredVisible) {
            sendFloatingCommand(FloatingServiceCommand.ApplyLyricsOffset(offsetMs))
        }
    }

    fun showLyrics(): FloatingVisibilityOutcome {
        setDesiredVisible(true)
        if (!updateOverlayPermissionGranted()) {
            return FloatingVisibilityOutcome.PERMISSION_REQUIRED
        }

        if (!sendFloatingCommand(FloatingServiceCommand.Show)) {
            return FloatingVisibilityOutcome.COMMAND_FAILED
        }
        return FloatingVisibilityOutcome.SUCCESS
    }

    fun hideLyrics(): FloatingVisibilityOutcome {
        setDesiredVisible(false)
        if (!sendFloatingCommand(FloatingServiceCommand.Hide)) {
            return FloatingVisibilityOutcome.COMMAND_FAILED
        }
        updateQuickFloatingActualVisible(false)
        return FloatingVisibilityOutcome.SUCCESS
    }

    fun restoreVisibleWindowIfNeeded() {
        if (!QuickFloatingStore.isDesiredVisible(appContext)) return
        if (!updateOverlayPermissionGranted()) return
        startFloatingService()
    }

    fun toggleLyrics(): FloatingVisibilityOutcome {
        if (state.quickFloatingDesiredVisible) {
            return hideLyrics()
        }
        if (!updateOverlayPermissionGranted()) {
            setDesiredVisible(true)
            return FloatingVisibilityOutcome.PERMISSION_REQUIRED
        }
        return showLyrics()
    }

    fun updateQuickFloatingActualVisible(visible: Boolean) {
        state.updateFloatingState(visible = visible)
    }

    private fun updateOverlayPermissionGranted(): Boolean {
        val granted = Settings.canDrawOverlays(appContext)
        state.updateFloatingState(overlayGranted = granted)
        return granted
    }

    private fun setDesiredVisible(visible: Boolean) {
        QuickFloatingStore.setDesiredVisible(appContext, visible)
        state.updateFloatingState(desiredVisible = visible)
    }

    fun notifyDisplayScopeChanged() {
        if (QuickFloatingStore.isDesiredVisible(appContext)) {
            sendFloatingCommand(FloatingServiceCommand.ApplyDisplayScope)
        }
    }

    fun toggleLock(): Boolean {
        val nextLocked = !state.locked
        if (!state.quickFloatingVisible) {
            updateLocalLocked(nextLocked, persist = true)
            return true
        }

        val command = if (nextLocked) {
            FloatingServiceCommand.Lock
        } else {
            FloatingServiceCommand.Unlock
        }
        if (!sendFloatingCommand(command)) return false
        updateLocalLocked(nextLocked, persist = false)
        return true
    }

    fun toggleClickThrough(): Boolean {
        val nextClickThrough = !state.clickThrough
        if (!state.quickFloatingVisible) {
            updateLocalClickThrough(nextClickThrough, persist = true)
            return true
        }

        val command = if (nextClickThrough) {
            FloatingServiceCommand.ClickThroughOn
        } else {
            FloatingServiceCommand.ClickThroughOff
        }
        if (!sendFloatingCommand(command)) return false
        updateLocalClickThrough(nextClickThrough, persist = false)
        return true
    }

    fun toggleAutoHideWhenPaused(): Boolean {
        val enabled = !FloatingLyricsStyleStore.isAutoHideWhenPaused(appContext)
        FloatingLyricsStyleStore.setAutoHideWhenPaused(appContext, enabled)
        if (QuickFloatingStore.isDesiredVisible(appContext)) {
            sendFloatingCommand(FloatingServiceCommand.ApplyAutoHideWhenPaused)
        }
        return enabled
    }

    private fun updateLocalLocked(locked: Boolean, persist: Boolean) {
        var clickThrough: Boolean? = null
        if (persist) {
            FloatingLyricsStyleStore.setLocked(appContext, locked)
            clickThrough = FloatingLyricsStyleStore.isClickThrough(appContext)
        } else if (FloatingLyricsStyleStore.isClickThroughFollowingLocked(appContext)) {
            clickThrough = locked
        }
        state.updateFloatingState(locked = locked, clickThrough = clickThrough)
    }

    private fun updateLocalClickThrough(clickThrough: Boolean, persist: Boolean) {
        state.updateFloatingState(clickThrough = clickThrough)
        if (persist) {
            FloatingLyricsStyleStore.setClickThrough(appContext, clickThrough)
        }
    }

    fun applyPreset(preset: String) {
        FloatingLyricsStyleStore.applyPreset(appContext, preset)
        notifyStyleChanged()
    }

    fun applyStyle(style: FloatingLyricsStyle) {
        FloatingLyricsStyleStore.setStyle(appContext, style)
        notifyStyleChanged()
    }

    fun applyTextSize(textSizeSp: Float) {
        FloatingLyricsStyleStore.setTextSize(appContext, textSizeSp)
        notifyStyleChanged()
    }

    fun applyTextColor(color: Int) {
        FloatingLyricsStyleStore.setTextColor(appContext, color)
        notifyStyleChanged()
    }

    fun applyTextAlpha(alpha: Int) {
        FloatingLyricsStyleStore.setTextAlpha(appContext, alpha)
        notifyStyleChanged()
    }

    fun applyBackgroundColor(color: Int) {
        FloatingLyricsStyleStore.setBackgroundColor(appContext, color)
        notifyStyleChanged()
    }

    fun applyBackgroundEnabled(enabled: Boolean) {
        FloatingLyricsStyleStore.setBackgroundEnabled(appContext, enabled)
        notifyStyleChanged()
    }

    fun applyBackgroundAlpha(alpha: Int) {
        FloatingLyricsStyleStore.setBackgroundAlpha(appContext, alpha)
        notifyStyleChanged()
    }

    fun applyGravity(gravity: Int) {
        FloatingLyricsStyleStore.setGravity(appContext, gravity)
        notifyStyleChanged()
    }

    fun applyShadowRadius(radius: Float) {
        FloatingLyricsStyleStore.setShadowRadius(appContext, radius)
        notifyStyleChanged()
    }

    fun applyShadowColor(color: Int) {
        FloatingLyricsStyleStore.setShadowColor(appContext, color)
        notifyStyleChanged()
    }

    fun applyMaxWidthPercent(percent: Int) {
        FloatingLyricsStyleStore.setMaxWidthPercent(appContext, percent)
        notifyStyleChanged()
    }

    fun applyPaddingHorizontal(paddingDp: Int) {
        FloatingLyricsStyleStore.setPaddingHorizontal(appContext, paddingDp)
        notifyStyleChanged()
    }

    fun applyPaddingVertical(paddingDp: Int) {
        FloatingLyricsStyleStore.setPaddingVertical(appContext, paddingDp)
        notifyStyleChanged()
    }

    fun applyCornerRadius(radiusDp: Int) {
        FloatingLyricsStyleStore.setCornerRadius(appContext, radiusDp)
        notifyStyleChanged()
    }

    fun applyWordByWordHighlightColor(color: Int) {
        FloatingLyricsStyleStore.setWordByWordHighlightColor(appContext, color)
        notifyStyleChanged()
    }

    fun notifyStyleChanged() {
        if (state.quickFloatingVisible) {
            sendFloatingCommand(FloatingServiceCommand.ApplyStyle)
        }
    }

    fun notifySourceChangedIfVisible(packageName: String?) {
        if (state.quickFloatingDesiredVisible) {
            sendFloatingCommand(FloatingServiceCommand.SelectMediaSource(packageName))
        }
    }

    private fun sendFloatingCommand(command: FloatingServiceCommand): Boolean {
        return startFloatingService(command.toIntent(appContext))
    }

    private fun startFloatingService(
        intent: Intent = FloatingServiceCommand.Restore.toIntent(appContext)
    ): Boolean {
        return serviceStarter.startLyricsServiceSafely(intent)
    }
}
