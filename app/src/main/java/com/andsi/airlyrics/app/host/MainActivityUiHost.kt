package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.app.platform.PermissionHelper
import com.andsi.airlyrics.app.render.MainActivityViewRefs
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaController
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.andsi.airlyrics.R
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.i18n.localizedFloatingGravityTitle
import com.andsi.airlyrics.i18n.localizedFloatingPresetTitle
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.ui.tokens.AirUiTokens
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

/** Bridges handwritten main UI helpers to the UI-facing host boundary. */
internal class MainActivityUiHost(
    private val graph: MainGraph
) : MainUiHost(graph.activity, graph.state) {
    private val viewRefs: MainActivityViewRefs
        get() = graph.viewRefs

    override val actions: MainUiActions
        get() = graph.uiActions

    override val tabViews: MutableMap<Page, TextView>
        get() = viewRefs.tabViews
    override var tabRow: LinearLayout?
        get() = viewRefs.tabRow
        set(value) { viewRefs.tabRow = value }
    override var tabHighlight: WaterTabHighlightView?
        get() = viewRefs.tabHighlight
        set(value) { viewRefs.tabHighlight = value }
    override var floatingPanelBackHandler: (() -> Boolean)?
        get() = viewRefs.floatingPanelBackHandler
        set(value) { viewRefs.floatingPanelBackHandler = value }
    override var contentContainer: FrameLayout?
        get() = viewRefs.contentContainer
        set(value) { viewRefs.contentContainer = value }
    override val mediaRefreshHandler
        get() = graph.mediaRefreshHandler

    override fun refreshCurrentPage(animateContent: Boolean, animateTabs: Boolean) {
        graph.uiInvalidator.refreshCurrentPage(animateContent, animateTabs)
    }

    override fun rebuildMainView() {
        graph.uiInvalidator.recreateForThemeChange()
    }

    override fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun isDarkTheme(): Boolean = ThemeSettingsStore.isDark(this)

    override fun getActiveMediaControllers(): List<MediaController> = graph.mediaSourceController.getActiveControllers()
    override fun getAppName(packageName: String): String = graph.mediaSourceController.getAppName(packageName)
    override fun getPlaybackStateText(state: Int?): String = graph.mediaSourceController.getPlaybackStateText(state)
    override fun getCurrentMediaInfo(): CurrentMediaInfo? = graph.lyricsController.getCurrentMediaInfo()
    override fun runOnAppIo(block: () -> Unit) = graph.runOnAppIo(block)
    override fun runOnMainThread(block: () -> Unit) = graph.runOnMainThread(block)

    override fun refreshMediaButton(): View = refreshMediaButtonImpl()
    override fun mediaSourceCard(controller: MediaController, selected: Boolean): View = mediaSourceCardImpl(controller, selected)

    override fun optionGrid(items: List<OptionItem>): LinearLayout = optionGridImpl(items)
    override fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout = liveOptionGridImpl(items)
    override fun optionButton(item: OptionItem): TextView = optionButtonImpl(item)
    override fun applyOptionButtonState(button: TextView, title: String, selected: Boolean) = applyOptionButtonStateImpl(button, title, selected)
    override fun sliderRow(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ): LinearLayout = sliderRowImpl(title, value, min, max, suffix, onChanged)
    override fun colorControl(title: String, color: Int, onChanged: (Int) -> Unit): LinearLayout = colorControlImpl(title, color, onChanged)
    override fun colorPreviewBackground(color: Int): GradientDrawable = colorPreviewBackgroundImpl(color)
    override fun isDarkColor(color: Int): Boolean = isDarkColorImpl(color)
    override fun withAlpha(color: Int, alpha: Int): Int = withAlphaImpl(color, alpha)
    override fun settingGrid(vararg items: FloatingSettingTile): LinearLayout = settingGridImpl(*items)
    override fun floatingTile(item: FloatingSettingTile): LinearLayout = floatingTileImpl(item)
    override fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): LinearLayout = floatingFocusBubbleImpl(title, subtitle, onClose, content)
    override fun showFloatingSettingPanel(
        title: String,
        subtitle: String,
        content: LinearLayout.() -> Unit
    ) = showFloatingSettingPanelImpl(title, subtitle, content)

    override fun floatingPreviewSummary(style: FloatingLyricsStyle): String {
        return getString(
            R.string.floating_preview_summary,
            localizedFloatingPresetTitle(style.presetName),
            style.textSizeSp.toInt(),
            localizedFloatingGravityTitle(style.gravity),
            style.maxWidthPercent
        )
    }

    override fun floatingDisplaySummary(): String {
        val lockedText = if (FloatingLyricsStyleStore.isLocked(this)) getString(R.string.ui_locked) else getString(R.string.ui_draggable)
        val clickThroughText = if (FloatingLyricsStyleStore.isClickThrough(this)) getString(R.string.ui_click_through) else getString(R.string.ui_clickable)
        return listOfNotNull(
            if (overlayPermissionGranted) null else getString(R.string.ui_overlay_permission_required),
            lockedText,
            clickThroughText
        ).joinToString(" · ")
    }

    override fun floatingLockButtonText(): String {
        return if (FloatingLyricsStyleStore.isLocked(this)) getString(R.string.ui_drag_lock_on) else getString(R.string.ui_drag_lock_off)
    }

    override fun floatingClickThroughButtonText(): String {
        return if (FloatingLyricsStyleStore.isClickThrough(this)) getString(R.string.ui_click_through_on) else getString(R.string.ui_click_through_off)
    }

    override fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView {
        return TextView(this).apply {
            this.text = text
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Sm))
            layoutParams = params
            applyFloatingPreviewStyle(style)
        }
    }

    override fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle) {
        textSize = AirUiTokens.TextSize.Title
        typeface = Typeface.DEFAULT_BOLD
        gravity = style.gravity
        setTextColor(style.textColor)
        setShadowLayer(style.shadowRadius, 0f, 0f, style.shadowColor)
        setPadding(
            dp(style.paddingHorizontalDp.coerceAtMost(24)),
            dp(style.paddingVerticalDp.coerceAtMost(12)),
            dp(style.paddingHorizontalDp.coerceAtMost(24)),
            dp(style.paddingVerticalDp.coerceAtMost(12))
        )
        background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp.coerceAtMost(24)).toFloat()
                setColor(withAlpha(style.backgroundColor, style.backgroundAlpha))
            }
        } else {
            null
        }
    }

    override fun applyFloatingPreset(preset: String) = graph.floatingController.applyPreset(preset)
    override fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean) = graph.floatingController.applyTextSize(textSizeSp, refreshPage)
    override fun applyFloatingTextColor(color: Int, refreshPage: Boolean) = graph.floatingController.applyTextColor(color, refreshPage)
    override fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean) = graph.floatingController.applyBackgroundColor(color, refreshPage)
    override fun applyFloatingGravity(gravity: Int) = graph.floatingController.applyGravity(gravity)
    override fun notifyFloatingStyleChanged() = graph.floatingController.notifyStyleChanged()

    override fun settingsHomeHeader(): View = settingsHomeHeaderImpl()
    override fun settingsBackHeader(title: String, subtitle: String): View = settingsBackHeaderImpl(title, subtitle)
    override fun themeToggleButton(): TextView = themeToggleButtonImpl()
    override fun settingsCategoryCard(
        title: String,
        subtitle: String,
        status: String,
        accent: Int,
        iconRes: Int,
        onClick: () -> Unit
    ): View = settingsCategoryCardImpl(title, subtitle, status, accent, iconRes, onClick)
    override fun localLyricsRow(
        item: LyricsStorage.LocalLyricsItem,
        onLyricsSaved: (() -> Unit)?,
        badgeText: CharSequence?
    ): View = localLyricsRowImpl(item, onLyricsSaved, badgeText)
    override fun changelogItem(title: String, body: String): View = changelogItemImpl(title, body)
    override fun permissionSummary(): String = permissionSummaryImpl()
    override fun getAppVersionName(): String = getAppVersionNameImpl()
    override fun openUrl(url: String) = openUrlImpl(url)
    override fun refreshAfterLanguageChanged() = refreshAfterLanguageChangedImpl()

    override fun hasNotificationPermission(): Boolean = PermissionHelper.hasPostNotificationsPermission(this)
    override fun hasNotificationListenerAccess(): Boolean = PermissionHelper.hasNotificationListenerAccess(this)

    override fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode) {
        graph.lyricsController.deleteLyricsForCurrentMedia(media, mode)
    }

    fun toggleThemeMode() {
        val nextDark = !isDarkTheme()
        ThemeSettingsStore.setDark(this, nextDark)
        applySystemBarsTheme()
        val oldContainer = contentContainer
        oldContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(AirUiTokens.Layout.FastFadeMs)
            ?.withEndAction {
                rebuildMainView()
                contentContainer?.alpha = 0f
                contentContainer?.animate()
                    ?.alpha(1f)
                    ?.setDuration(AirUiTokens.Layout.RestoreFadeMs)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
            }
            ?.start()
            ?: run {
                rebuildMainView()
            }
    }

    fun applySystemBarsTheme() {
        val lightTheme = !isDarkTheme()
        activity.enableEdgeToEdge(
            statusBarStyle = if (lightTheme) {
                SystemBarStyle.light(Color.BLACK, Color.BLACK)
            } else {
                SystemBarStyle.dark(Color.BLACK)
            },
            navigationBarStyle = if (lightTheme) {
                SystemBarStyle.light(colorSurface, colorSurface)
            } else {
                SystemBarStyle.dark(colorSurface)
            }
        )
    }
}
