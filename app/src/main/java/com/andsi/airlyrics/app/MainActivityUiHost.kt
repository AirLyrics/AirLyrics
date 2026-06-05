package com.andsi.airlyrics.app

import android.graphics.drawable.GradientDrawable
import android.media.session.MediaController
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

/** Bridges the old hand-written screen helpers to the UI-facing host boundary. */
internal class MainActivityUiHost(
    private val owner: MainActivity
) : MainUiHost(owner, owner.state) {
    override val actions: MainUiActions
        get() = owner.uiActions

    override val tabViews: MutableMap<Page, TextView>
        get() = owner.tabViews
    override var tabRow: LinearLayout?
        get() = owner.tabRow
        set(value) { owner.tabRow = value }
    override var tabHighlight: WaterTabHighlightView?
        get() = owner.tabHighlight
        set(value) { owner.tabHighlight = value }
    override var floatingPanelBackHandler: (() -> Boolean)?
        get() = owner.floatingPanelBackHandler
        set(value) { owner.floatingPanelBackHandler = value }

    override fun dp(value: Int): Int = owner.dp(value)
    override fun isDarkTheme(): Boolean = owner.isDarkTheme()

    override fun getActiveMediaControllers(): List<MediaController> = owner.getActiveMediaControllers()
    override fun getAppName(packageName: String): String = owner.getAppName(packageName)
    override fun getPlaybackStateText(state: Int?): String = owner.getPlaybackStateText(state)
    override fun getCurrentMediaSnapshot(): CurrentMediaInfo? = owner.getCurrentMediaSnapshot()
    override fun runOnAppIo(block: () -> Unit) = owner.runOnAppIo(block)
    override fun runOnMainThread(block: () -> Unit) = owner.runOnMainThread(block)

    override fun refreshMediaButton(): View = owner.refreshMediaButton()
    override fun mediaSourceCard(controller: MediaController, selected: Boolean): View = owner.mediaSourceCard(controller, selected)

    override fun optionGrid(items: List<OptionItem>): LinearLayout = owner.optionGrid(items)
    override fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout = owner.liveOptionGrid(items)
    override fun optionButton(item: OptionItem): TextView = owner.optionButton(item)
    override fun applyOptionButtonState(button: TextView, title: String, selected: Boolean) = owner.applyOptionButtonState(button, title, selected)
    override fun sliderRow(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ): LinearLayout = owner.sliderRow(title, value, min, max, suffix, onChanged)
    override fun colorControl(title: String, color: Int, onChanged: (Int) -> Unit): LinearLayout = owner.colorControl(title, color, onChanged)
    override fun colorPreviewBackground(color: Int): GradientDrawable = owner.colorPreviewBackground(color)
    override fun isDarkColor(color: Int): Boolean = owner.isDarkColor(color)
    override fun withAlpha(color: Int, alpha: Int): Int = owner.withAlpha(color, alpha)
    override fun settingGrid(vararg items: FloatingSettingTile): LinearLayout = owner.settingGrid(*items)
    override fun floatingTile(item: FloatingSettingTile): LinearLayout = owner.floatingTile(item)
    override fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): LinearLayout = owner.floatingFocusBubble(title, subtitle, onClose, content)
    override fun showFloatingSettingPanel(
        title: String,
        subtitle: String,
        content: LinearLayout.() -> Unit
    ) = owner.showFloatingSettingPanel(title, subtitle, content)

    override fun floatingPreviewSummary(style: FloatingLyricsStyle): String = owner.floatingPreviewSummary(style)
    override fun floatingDisplaySummary(): String = owner.floatingDisplaySummary()
    override fun floatingLockButtonText(): String = owner.floatingLockButtonText()
    override fun floatingClickThroughButtonText(): String = owner.floatingClickThroughButtonText()
    override fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView = owner.floatingPreviewText(text, style)
    override fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle) {
        with(owner) {
            this@applyFloatingPreviewStyle.applyFloatingPreviewStyle(style)
        }
    }
    override fun applyFloatingPreset(preset: String) = owner.applyFloatingPreset(preset)
    override fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean) = owner.applyFloatingTextSize(textSizeSp, refreshPage)
    override fun applyFloatingTextColor(color: Int, refreshPage: Boolean) = owner.applyFloatingTextColor(color, refreshPage)
    override fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean) = owner.applyFloatingBackgroundColor(color, refreshPage)
    override fun applyFloatingGravity(gravity: Int) = owner.applyFloatingGravity(gravity)
    override fun notifyFloatingStyleChanged() = owner.notifyFloatingStyleChanged()

    override fun settingsHomeHeader(): View = owner.settingsHomeHeader()
    override fun settingsBackHeader(title: String, subtitle: String): View = owner.settingsBackHeader(title, subtitle)
    override fun themeToggleButton(): TextView = owner.themeToggleButton()
    override fun settingsCategoryCard(
        title: String,
        subtitle: String,
        status: String,
        accent: Int,
        iconRes: Int,
        onClick: () -> Unit
    ): View = owner.settingsCategoryCard(title, subtitle, status, accent, iconRes, onClick)
    override fun localLyricsRow(
        item: LyricsStorage.LocalLyricsItem,
        onLyricsSaved: (() -> Unit)?,
        badgeText: CharSequence?
    ): View = owner.localLyricsRow(item, onLyricsSaved, badgeText)
    override fun changelogItem(title: String, body: String): View = owner.changelogItem(title, body)
    override fun permissionSummary(): String = owner.permissionSummary()
    override fun getAppVersionName(): String = owner.getAppVersionName()
    override fun openUrl(url: String) = owner.openUrl(url)
    override fun refreshAfterLanguageChanged() = owner.refreshAfterLanguageChanged()

    override fun hasNotificationPermission(): Boolean = owner.hasNotificationPermission()
    override fun hasNotificationListenerAccess(): Boolean = owner.hasNotificationListenerAccess()

    override fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode) = owner.deleteLyricsForCurrentMedia(media, mode)
}
