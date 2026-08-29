package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.app.state.MainFloatingState
import com.andsi.airlyrics.R

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.andsi.airlyrics.app.contracts.FloatingNavFeedback
import com.andsi.airlyrics.app.contracts.MainServiceStarter
import com.andsi.airlyrics.app.contracts.OverlayPermissionRequester
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.floating.FloatingServiceCommand
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.feedback.AirFeedback
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.ui.refresh.PageRebuildReason

internal class FloatingController(
    private val context: Context,
    private val state: MainFloatingState,
    private val invalidator: UiInvalidator,
    private val serviceStarter: MainServiceStarter,
    private val overlayPermissionRequester: OverlayPermissionRequester,
    private val navFeedback: FloatingNavFeedback,
    private val feedback: AirFeedback,
    private val crossWindowFeedback: AirFeedback
) {
    private var overlayPermissionHintShown = false

    fun reloadLyrics() {
        if (!state.quickFloatingVisible) return
        sendFloatingCommand(FloatingServiceCommand.ReloadLyrics)
    }

    fun applyLyricsOffset(offsetMs: Long) {
        if (!state.quickFloatingVisible) return

        sendFloatingCommand(FloatingServiceCommand.ApplyLyricsOffset(offsetMs))
    }

    fun showLyrics(): Boolean {
        setDesiredVisible(true)
        if (!updateOverlayPermissionGranted()) {
            if (!overlayPermissionHintShown) {
                crossWindowFeedback.showError(R.string.ui_enable_overlay_permission_first)
                overlayPermissionHintShown = true
            }
            invalidator.rebuildCurrentPage(PageRebuildReason.PERMISSION_CHANGED)
            overlayPermissionRequester.requestOverlayPermission()
            return false
        }

        val sent = sendFloatingCommand(FloatingServiceCommand.Show)
        if (sent) {
            updateQuickFloatingActualVisible(true)
            invalidator.refreshFloatingChrome()
        } else {
            invalidator.rebuildCurrentPage(PageRebuildReason.FLOATING_STRUCTURE_CHANGED)
        }
        return sent
    }

    fun hideLyrics(): Boolean {
        setDesiredVisible(false)
        val sent = sendFloatingCommand(FloatingServiceCommand.Hide)
        if (sent) {
            updateQuickFloatingActualVisible(false)
            invalidator.refreshFloatingChrome()
        } else {
            feedback.showError(R.string.ui_overlay_update_failed)
            invalidator.rebuildCurrentPage(PageRebuildReason.FLOATING_STRUCTURE_CHANGED)
        }
        return sent
    }

    fun restoreVisibleWindowIfNeeded() {
        if (!QuickFloatingStore.isDesiredVisible(context)) return
        if (!updateOverlayPermissionGranted()) return
        startFloatingService()
    }

    fun toggleFromNav() {
        navFeedback.playFloatingNavToggleFeedback()

        if (!updateOverlayPermissionGranted()) {
            showLyrics()
            return
        }

        if (state.quickFloatingVisible) {
            hideLyrics()
        } else {
            showLyrics()
        }
    }

    fun updateQuickFloatingActualVisible(visible: Boolean) {
        state.quickFloatingVisible = visible
    }

    private fun updateOverlayPermissionGranted(): Boolean {
        val granted = Settings.canDrawOverlays(context)
        state.overlayPermissionGranted = granted
        return granted
    }

    private fun setDesiredVisible(visible: Boolean) {
        QuickFloatingStore.setDesiredVisible(context, visible)
    }

    fun toggleLock() {
        val nextLocked = !state.locked

        if (!state.quickFloatingVisible) {
            updateLocalLocked(nextLocked, persist = true)
            invalidator.refreshFloatingControls()
            return
        }

        val command = if (nextLocked) {
            FloatingServiceCommand.Lock
        } else {
            FloatingServiceCommand.Unlock
        }
        if (sendFloatingCommand(command)) {
            updateLocalLocked(nextLocked, persist = false)
            invalidator.refreshFloatingControls()
        } else {
            feedback.showError(R.string.ui_overlay_update_failed)
        }
    }

    fun toggleClickThrough() {
        val nextClickThrough = !state.clickThrough

        if (!state.quickFloatingVisible) {
            updateLocalClickThrough(nextClickThrough, persist = true)
            invalidator.refreshFloatingControls()
            return
        }

        val sent = sendFloatingCommand(
            if (nextClickThrough) {
                FloatingServiceCommand.ClickThroughOn
            } else {
                FloatingServiceCommand.ClickThroughOff
            }
        )
        if (sent) {
            updateLocalClickThrough(nextClickThrough, persist = false)
            invalidator.refreshFloatingControls()
        } else {
            feedback.showError(R.string.ui_overlay_update_failed)
        }
    }

    fun toggleAutoHideWhenPaused(): Boolean {
        val enabled = !FloatingLyricsStyleStore.isAutoHideWhenPaused(context)
        FloatingLyricsStyleStore.setAutoHideWhenPaused(context, enabled)

        if (QuickFloatingStore.isDesiredVisible(context)) {
            sendFloatingCommand(FloatingServiceCommand.ApplyAutoHideWhenPaused)
        }

        invalidator.refreshFloatingControls()
        return enabled
    }

    private fun updateLocalLocked(locked: Boolean, persist: Boolean) {
        state.locked = locked
        if (persist) {
            FloatingLyricsStyleStore.setLocked(context, locked)
            state.clickThrough = FloatingLyricsStyleStore.isClickThrough(context)
        } else if (FloatingLyricsStyleStore.isClickThroughFollowingLocked(context)) {
            state.clickThrough = locked
        }
    }

    private fun updateLocalClickThrough(clickThrough: Boolean, persist: Boolean) {
        state.clickThrough = clickThrough
        if (persist) {
            FloatingLyricsStyleStore.setClickThrough(context, clickThrough)
        }
    }

    fun applyPreset(preset: String) {
        FloatingLyricsStyleStore.applyPreset(context, preset)
        notifyStyleChanged()
    }

    fun applyStyle(style: FloatingLyricsStyle) {
        FloatingLyricsStyleStore.setStyle(context, style)
        notifyStyleChanged()
    }

    fun applyTextSize(textSizeSp: Float, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextSize(context, textSizeSp)
        notifyStyleChanged()
        if (refreshPage) invalidator.rebuildCurrentPage(PageRebuildReason.FLOATING_STRUCTURE_CHANGED)
    }

    fun applyTextColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextColor(context, color)
        notifyStyleChanged()
        if (refreshPage) invalidator.rebuildCurrentPage(PageRebuildReason.FLOATING_STRUCTURE_CHANGED)
    }

    fun applyTextAlpha(alpha: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextAlpha(context, alpha)
        notifyStyleChanged()
        if (refreshPage) invalidator.rebuildCurrentPage(PageRebuildReason.FLOATING_STRUCTURE_CHANGED)
    }

    fun applyBackgroundColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setBackgroundColor(context, color)
        notifyStyleChanged()
        if (refreshPage) invalidator.rebuildCurrentPage(PageRebuildReason.FLOATING_STRUCTURE_CHANGED)
    }

    fun applyBackgroundEnabled(enabled: Boolean) {
        FloatingLyricsStyleStore.setBackgroundEnabled(context, enabled)
        notifyStyleChanged()
    }

    fun applyBackgroundAlpha(alpha: Int) {
        FloatingLyricsStyleStore.setBackgroundAlpha(context, alpha)
        notifyStyleChanged()
    }

    fun applyGravity(gravity: Int) {
        FloatingLyricsStyleStore.setGravity(context, gravity)
        notifyStyleChanged()
    }

    fun applyShadowRadius(radius: Float) {
        FloatingLyricsStyleStore.setShadowRadius(context, radius)
        notifyStyleChanged()
    }

    fun applyShadowColor(color: Int) {
        FloatingLyricsStyleStore.setShadowColor(context, color)
        notifyStyleChanged()
    }

    fun applyMaxWidthPercent(percent: Int) {
        FloatingLyricsStyleStore.setMaxWidthPercent(context, percent)
        notifyStyleChanged()
    }

    fun applyPaddingHorizontal(paddingDp: Int) {
        FloatingLyricsStyleStore.setPaddingHorizontal(context, paddingDp)
        notifyStyleChanged()
    }

    fun applyPaddingVertical(paddingDp: Int) {
        FloatingLyricsStyleStore.setPaddingVertical(context, paddingDp)
        notifyStyleChanged()
    }

    fun applyCornerRadius(radiusDp: Int) {
        FloatingLyricsStyleStore.setCornerRadius(context, radiusDp)
        notifyStyleChanged()
    }

    fun applyWordByWordHighlightColor(color: Int) {
        FloatingLyricsStyleStore.setWordByWordHighlightColor(context, color)
        notifyStyleChanged()
    }

    fun notifyStyleChanged() {
        if (!state.quickFloatingVisible) return
        sendFloatingCommand(FloatingServiceCommand.ApplyStyle)
    }

    fun notifySourceChangedIfVisible(packageName: String?) {
        if (!state.quickFloatingVisible) return

        sendFloatingCommand(FloatingServiceCommand.SelectMediaSource(packageName))
    }

    private fun sendFloatingCommand(command: FloatingServiceCommand): Boolean {
        return startFloatingService(command.toIntent(context))
    }

    private fun startFloatingService(
        intent: Intent = FloatingServiceCommand.Restore.toIntent(context)
    ): Boolean {
        return serviceStarter.startLyricsServiceSafely(intent)
    }
}
