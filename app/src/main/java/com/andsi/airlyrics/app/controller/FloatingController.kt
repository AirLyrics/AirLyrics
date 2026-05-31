package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.Intent
import android.provider.Settings
import android.view.animation.OvershootInterpolator
import com.andsi.airlyrics.ui.tokens.AirUiTokens
import android.widget.Toast
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.renderCurrentPage
import com.andsi.airlyrics.i18n.displayText
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

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.RELOAD_LYRICS
        }
        activity.startLyricsService(intent)
    }

    fun applyLyricsOffset(offsetMs: Long) {
        if (!activity.quickFloatingVisible) return

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.APPLY_LYRICS_OFFSET
            putExtra(BroadcastActions.EXTRA_LYRICS_OFFSET_MS, offsetMs)
        }
        activity.startLyricsService(intent)
    }

    fun reloadLyricsFromOnline() {
        if (!activity.quickFloatingVisible) {
            Toast.makeText(activity, activity.getString(R.string.ui_show_overlay_then_search_hint), Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.RELOAD_ONLINE_LYRICS
        }
        activity.startLyricsService(intent)
        Toast.makeText(activity, activity.getString(R.string.ui_searching_online_again), Toast.LENGTH_SHORT).show()
    }

    fun showLyrics(): Boolean {
        if (!Settings.canDrawOverlays(activity)) {
            if (!overlayPermissionHintShown) {
                Toast.makeText(activity, activity.getString(R.string.ui_enable_overlay_permission_first), Toast.LENGTH_LONG).show()
                overlayPermissionHintShown = true
            }
            activity.requestOverlayPermission()
            return false
        }

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.SHOW
        }
        activity.startLyricsService(intent)
        updateQuickFloatingVisible(true)
        updateTabs(activity)
        return true
    }

    fun hideLyrics(): Boolean {
        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.HIDE
        }
        activity.startLyricsService(intent)
        updateQuickFloatingVisible(false)
        updateTabs(activity)
        return true
    }


    fun restoreVisibleWindowIfNeeded() {
        if (!activity.quickFloatingVisible) return

        if (!Settings.canDrawOverlays(activity)) {
            updateQuickFloatingVisible(false)
            updateTabs(activity)
            return
        }

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.SHOW
        }
        activity.startLyricsService(intent)
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
        return QuickFloatingStore.isVisible(activity)
    }

    fun updateQuickFloatingVisible(visible: Boolean) {
        activity.quickFloatingVisible = visible
        QuickFloatingStore.setVisible(activity, visible)
    }

    fun toggleLock() {
        activity.locked = !FloatingLyricsStyleStore.isLocked(activity)
        FloatingLyricsStyleStore.setLocked(activity, activity.locked)
        if (!activity.quickFloatingVisible) return

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = if (activity.locked) BroadcastActions.LOCK else BroadcastActions.UNLOCK
        }
        activity.startLyricsService(intent)
    }

    fun toggleClickThrough() {
        activity.clickThrough = !FloatingLyricsStyleStore.isClickThrough(activity)
        FloatingLyricsStyleStore.setClickThrough(activity, activity.clickThrough)
        if (!activity.quickFloatingVisible) return

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = if (activity.clickThrough) {
                BroadcastActions.CLICK_THROUGH_ON
            } else {
                BroadcastActions.CLICK_THROUGH_OFF
            }
        }
        activity.startLyricsService(intent)
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

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.APPLY_STYLE
        }
        activity.startLyricsService(intent)
    }

    fun notifySourceChangedIfVisible(packageName: String?) {
        if (!activity.quickFloatingVisible) return

        val intent = Intent(activity, FloatingLyricsService::class.java).apply {
            action = BroadcastActions.SELECT_MEDIA_SOURCE
            putExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE, packageName)
        }
        activity.startLyricsService(intent)
    }
}
