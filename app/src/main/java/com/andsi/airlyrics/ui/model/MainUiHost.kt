package com.andsi.airlyrics.ui.model

import android.content.ContextWrapper
import android.media.session.MediaController
import android.os.Handler
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

/**
 * UI-facing host for the handwritten main screen.
 *
 * Pages receive this small boundary instead of the concrete activity, keeping
 * layout code stable while the app layer continues to own wiring and services.
 */
internal abstract class MainUiHost(
    val activity: AppCompatActivity,
    val uiState: MainUiState
) : ContextWrapper(activity) {
    abstract val actions: MainUiActions
    val uiActions: MainUiActions
        get() = actions

    var currentPage: Page
        get() = uiState.currentPage
        set(value) { uiState.currentPage = value }
    var settingsSubPage: SettingsSubPage
        get() = uiState.settingsSubPage
        set(value) { uiState.settingsSubPage = value }
    var quickFloatingVisible: Boolean
        get() = uiState.quickFloatingVisible
        set(value) { uiState.quickFloatingVisible = value }
    var overlayPermissionGranted: Boolean
        get() = uiState.overlayPermissionGranted
        set(value) { uiState.overlayPermissionGranted = value }
    var currentLyricsLoadGeneration: Int
        get() = uiState.currentLyricsLoadGeneration
        set(value) { uiState.currentLyricsLoadGeneration = value }
    var recentLyricsLoadGeneration: Int
        get() = uiState.recentLyricsLoadGeneration
        set(value) { uiState.recentLyricsLoadGeneration = value }
    var mediaRefreshState: RefreshState
        get() = uiState.mediaRefreshState
        set(value) { uiState.mediaRefreshState = value }

    abstract val tabViews: MutableMap<Page, TextView>
    abstract var tabRow: LinearLayout?
    abstract var tabHighlight: WaterTabHighlightView?
    abstract var floatingPanelBackHandler: (() -> Boolean)?
    abstract var contentContainer: FrameLayout?
    abstract val mediaRefreshHandler: Handler

    abstract fun refreshCurrentPage(animateContent: Boolean = true, animateTabs: Boolean = true)
    abstract fun rebuildMainView()
    abstract fun dp(value: Int): Int
    abstract fun isDarkTheme(): Boolean

    abstract fun getActiveMediaControllers(): List<MediaController>
    abstract fun getAppName(packageName: String): String
    abstract fun getPlaybackStateText(state: Int?): String
    abstract fun getCurrentMediaSnapshot(): CurrentMediaInfo?
    abstract fun runOnAppIo(block: () -> Unit)
    abstract fun runOnMainThread(block: () -> Unit)

    abstract fun refreshMediaButton(): View
    abstract fun mediaSourceCard(controller: MediaController, selected: Boolean): View

    abstract fun optionGrid(items: List<OptionItem>): LinearLayout
    abstract fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout
    abstract fun optionButton(item: OptionItem): TextView
    abstract fun applyOptionButtonState(button: TextView, title: String, selected: Boolean)
    abstract fun sliderRow(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ): LinearLayout
    abstract fun colorControl(title: String, color: Int, onChanged: (Int) -> Unit): LinearLayout
    abstract fun colorPreviewBackground(color: Int): android.graphics.drawable.GradientDrawable
    abstract fun isDarkColor(color: Int): Boolean
    abstract fun withAlpha(color: Int, alpha: Int): Int
    abstract fun settingGrid(vararg items: FloatingSettingTile): LinearLayout
    abstract fun floatingTile(item: FloatingSettingTile): LinearLayout
    abstract fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): LinearLayout
    abstract fun showFloatingSettingPanel(
        title: String,
        subtitle: String,
        content: LinearLayout.() -> Unit
    )

    abstract fun floatingPreviewSummary(style: FloatingLyricsStyle): String
    abstract fun floatingDisplaySummary(): String
    abstract fun floatingLockButtonText(): String
    abstract fun floatingClickThroughButtonText(): String
    abstract fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView
    abstract fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle)
    abstract fun applyFloatingPreset(preset: String)
    abstract fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true)
    abstract fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true)
    abstract fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true)
    abstract fun applyFloatingGravity(gravity: Int)
    abstract fun notifyFloatingStyleChanged()

    abstract fun settingsHomeHeader(): View
    abstract fun settingsBackHeader(title: String, subtitle: String = ""): View
    abstract fun themeToggleButton(): TextView
    abstract fun settingsCategoryCard(
        title: String,
        subtitle: String,
        status: String,
        accent: Int,
        iconRes: Int,
        onClick: () -> Unit
    ): View
    abstract fun localLyricsRow(
        item: LyricsStorage.LocalLyricsItem,
        onLyricsSaved: (() -> Unit)? = null,
        badgeText: CharSequence? = null
    ): View
    abstract fun changelogItem(title: String, body: String): View
    abstract fun permissionSummary(): String
    abstract fun getAppVersionName(): String
    abstract fun openUrl(url: String)
    abstract fun refreshAfterLanguageChanged()

    abstract fun hasNotificationPermission(): Boolean
    abstract fun hasNotificationListenerAccess(): Boolean

    abstract fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode)
}
