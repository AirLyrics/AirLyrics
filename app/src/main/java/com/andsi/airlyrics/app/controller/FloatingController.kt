package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.Intent
import android.provider.Settings
import android.view.animation.OvershootInterpolator
import com.andsi.airlyrics.ui.tokens.AirUiTokens
import android.widget.Toast
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.renderCurrentPage
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.floating.FloatingLyricsService
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.updateTabs

internal class FloatingController(
    private val activity: MainActivity
) {
    private var overlayPermissionHintShown = false

    fun reloadLyrics() {
        if (!activity.quickFloatingVisible) return
        sendFloatingCommand(BroadcastActions.RELOAD_LYRICS)
    }

    fun applyLyricsOffset(offsetMs: Long) {
        if (!activity.quickFloatingVisible) return

        sendFloatingCommand(BroadcastActions.APPLY_LYRICS_OFFSET) {
            putExtra(BroadcastActions.EXTRA_LYRICS_OFFSET_MS, offsetMs)
        }
    }

    fun reloadLyricsFromOnline() {
        if (!activity.quickFloatingVisible) {
            Toast.makeText(activity, activity.getString(R.string.ui_show_overlay_then_search_hint), Toast.LENGTH_LONG).show()
            return
        }

        if (sendFloatingCommand(BroadcastActions.RELOAD_ONLINE_LYRICS)) {
            Toast.makeText(activity, activity.getString(R.string.ui_searching_online_again), Toast.LENGTH_SHORT).show()
        }
    }

    fun showLyrics(): Boolean {
        if (!Settings.canDrawOverlays(activity)) {
            if (!overlayPermissionHintShown) {
                Toast.makeText(activity, activity.getString(R.string.ui_enable_overlay_permission_first), Toast.LENGTH_LONG).show()
                overlayPermissionHintShown = true
            }
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            updateTabs(activity)
            activity.requestOverlayPermission()
            return false
        }

        setDesiredVisible(true)
        val sent = sendFloatingCommand(BroadcastActions.SHOW)
        if (!sent) {
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            updateTabs(activity)
        }
        return sent
    }

    fun hideLyrics(): Boolean {
        setDesiredVisible(false)
        val sent = sendFloatingCommand(BroadcastActions.HIDE)
        if (!sent) {
            Toast.makeText(activity, activity.getString(R.string.ui_overlay_update_failed), Toast.LENGTH_SHORT).show()
            updateTabs(activity)
        }
        return sent
    }

    fun restoreVisibleWindowIfNeeded() {
        if (!QuickFloatingStore.isDesiredVisible(activity)) return

        if (!Settings.canDrawOverlays(activity)) {
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            updateTabs(activity)
            return
        }

        val sent = sendFloatingCommand(BroadcastActions.SHOW)
        if (!sent) {
            setDesiredVisible(false)
            updateQuickFloatingActualVisible(false)
            updateTabs(activity)
        }
    }

    fun toggleFromNav() {
        val selectedTab = activity.tabViews[Page.FLOATING]
        selectedTab?.animate()
            ?.scaleX(AirUiTokens.Layout.TabTextSwapScale)
            ?.scaleY(AirUiTokens.Layout.TabTextSwapScale)
            ?.setDuration(AirUiTokens.Layout.NavTapDownMs)
            ?.withEndAction {
                selectedTab.animate()
                    .scaleX(AirUiTokens.Layout.TabQuickScale)
                    .scaleY(AirUiTokens.Layout.TabQuickScale)
                    .setDuration(AirUiTokens.Layout.NavTapUpMs)
                    .setInterpolator(OvershootInterpolator(AirUiTokens.Layout.NavTapOvershoot))
                    .start()
            }
            ?.start()

        if (activity.quickFloatingVisible) {
            hideLyrics()
        } else {
            showLyrics()
        }
    }

    fun isQuickFloatingVisible(): Boolean {
        return activity.quickFloatingVisible
    }

    /** Compatibility entry point: callers use this for UI/actual state. */
    fun updateQuickFloatingVisible(visible: Boolean) {
        updateQuickFloatingActualVisible(visible)
    }

    fun updateQuickFloatingActualVisible(visible: Boolean) {
        activity.quickFloatingVisible = visible
    }

    private fun setDesiredVisible(visible: Boolean) {
        QuickFloatingStore.setDesiredVisible(activity, visible)
    }

    fun toggleLock() {
        val nextLocked = !FloatingLyricsStyleStore.isLocked(activity)

        if (!activity.quickFloatingVisible) {
            activity.locked = nextLocked
            FloatingLyricsStyleStore.setLocked(activity, nextLocked)
            activity.renderCurrentPage()
            return
        }

        if (!sendFloatingCommand(if (nextLocked) BroadcastActions.LOCK else BroadcastActions.UNLOCK)) {
            Toast.makeText(activity, activity.getString(R.string.ui_overlay_update_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleClickThrough() {
        val nextClickThrough = !FloatingLyricsStyleStore.isClickThrough(activity)

        if (!activity.quickFloatingVisible) {
            activity.clickThrough = nextClickThrough
            FloatingLyricsStyleStore.setClickThrough(activity, nextClickThrough)
            activity.renderCurrentPage()
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
            Toast.makeText(activity, activity.getString(R.string.ui_overlay_update_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun applyPreset(preset: String) {
        FloatingLyricsStyleStore.applyPreset(activity, preset)
        notifyStyleChanged()
    }

    fun applyTextSize(textSizeSp: Float, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextSize(activity, textSizeSp)
        notifyStyleChanged()
        if (refreshPage) activity.renderCurrentPage()
    }

    fun applyTextColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setTextColor(activity, color)
        notifyStyleChanged()
        if (refreshPage) activity.renderCurrentPage()
    }

    fun applyBackgroundColor(color: Int, refreshPage: Boolean = true) {
        FloatingLyricsStyleStore.setBackgroundColor(activity, color)
        notifyStyleChanged()
        if (refreshPage) activity.renderCurrentPage()
    }

    fun applyGravity(gravity: Int) {
        FloatingLyricsStyleStore.setGravity(activity, gravity)
        notifyStyleChanged()
    }

    fun notifyStyleChanged() {
        if (!activity.quickFloatingVisible) return
        sendFloatingCommand(BroadcastActions.APPLY_STYLE)
    }

    fun notifySourceChangedIfVisible(packageName: String?) {
        if (!activity.quickFloatingVisible && !QuickFloatingStore.isDesiredVisible(activity)) return

        sendFloatingCommand(BroadcastActions.SELECT_MEDIA_SOURCE) {
            putExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE, packageName)
        }
    }

    private fun sendFloatingCommand(
        action: String,
        configure: Intent.() -> Unit = {}
    ): Boolean {
        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            this.action = action
            configure()
        }
        return activity.startLyricsServiceSafely(intent)
    }
}
