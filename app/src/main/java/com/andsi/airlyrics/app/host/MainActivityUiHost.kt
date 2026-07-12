package com.andsi.airlyrics.app.host

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
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.app.platform.PermissionHelper
import com.andsi.airlyrics.app.render.MainActivityViewRefs
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.i18n.localizedFloatingGravityTitle
import com.andsi.airlyrics.i18n.localizedFloatingPresetTitle
import com.andsi.airlyrics.i18n.localizedLocalLyricsMeta
import com.andsi.airlyrics.i18n.localizedLocalLyricsSource
import com.andsi.airlyrics.i18n.localizedLocalLyricsSubtitle
import com.andsi.airlyrics.i18n.localizedLocalLyricsType
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.displayText
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.CurrentLyricsUiState
import com.andsi.airlyrics.ui.model.CurrentMediaUiInfo
import com.andsi.airlyrics.ui.refs.FloatingPageRefs
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.LanguageOptionUiItem
import com.andsi.airlyrics.ui.model.LanguageSettingsUiState
import com.andsi.airlyrics.ui.model.LyricsDeleteMode
import com.andsi.airlyrics.ui.model.LyricsSettingsUiState
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.MediaPageState
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.ui.model.RecentLyricsUiState
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.design.tokens.AirUiTokens
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
    override var floatingPageRefs: FloatingPageRefs?
        get() = viewRefs.floatingPageRefs
        set(value) { viewRefs.floatingPageRefs = value }
    override val mediaRefreshHandler
        get() = graph.mediaRefreshHandler

    override fun rebuildCurrentPage(
        reason: PageRebuildReason,
        animateContent: Boolean,
        animateTabs: Boolean
    ) {
        graph.uiInvalidator.rebuildCurrentPage(reason, animateContent, animateTabs)
    }

    override fun rebuildMainView() {
        graph.uiInvalidator.recreateForThemeChange()
    }

    override fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun isDarkTheme(): Boolean = ThemeSettingsStore.isDark(this)

    override fun getActiveMediaControllers(): List<MediaController> = graph.mediaSourceController.getActiveControllers()
    override fun mediaPageState(): MediaPageState {
        val controllers = CurrentMediaReader.selectedControllersByPackage(getActiveMediaControllers())
            .values
            .toList()
        val selectedPackage = MediaSourceStore.getSelectedPackage(this)
        return MediaPageState(
            controllers = controllers,
            selectedPackage = selectedPackage,
            selectedController = CurrentMediaReader.bestController(controllers, selectedPackage)
        )
    }
    override fun getAppName(packageName: String): String = graph.mediaSourceController.getAppName(packageName)
    override fun getPlaybackStateText(state: Int?): String = graph.mediaSourceController.getPlaybackStateText(state)
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

    override fun floatingStyle(): FloatingLyricsStyle = FloatingLyricsStyleStore.getStyle(this)
    override fun floatingPresets() = FloatingLyricsStyleStore.presets
    override fun isFloatingPreviewExpanded(): Boolean = FloatingLyricsStyleStore.isPreviewExpanded(this)
    override fun setFloatingPreviewExpanded(expanded: Boolean) = FloatingLyricsStyleStore.setPreviewExpanded(this, expanded)
    override fun colorSummary(color: Int): String = FloatingLyricsStyleStore.colorSummary(color)
    override fun backgroundColorWithAlpha(style: FloatingLyricsStyle): Int = FloatingLyricsStyleStore.backgroundColorWithAlpha(style)

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
        val lockedText = if (uiState.locked) getString(R.string.ui_locked) else getString(R.string.ui_draggable)
        val clickThroughText = if (uiState.clickThrough) getString(R.string.ui_click_through) else getString(R.string.ui_clickable)
        return listOfNotNull(
            if (overlayPermissionGranted) null else getString(R.string.ui_overlay_permission_required),
            lockedText,
            clickThroughText
        ).joinToString(" · ")
    }

    override fun floatingLockButtonText(): String {
        return if (uiState.locked) getString(R.string.ui_drag_lock_on) else getString(R.string.ui_drag_lock_off)
    }

    override fun floatingClickThroughButtonText(): String {
        return if (uiState.clickThrough) getString(R.string.ui_click_through_on) else getString(R.string.ui_click_through_off)
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
    override fun applyFloatingBackgroundEnabled(enabled: Boolean) = graph.floatingController.applyBackgroundEnabled(enabled)
    override fun applyFloatingGravity(gravity: Int) = graph.floatingController.applyGravity(gravity)
    override fun applyFloatingShadowRadius(radius: Float) = graph.floatingController.applyShadowRadius(radius)
    override fun applyFloatingShadowColor(color: Int) = graph.floatingController.applyShadowColor(color)
    override fun applyFloatingMaxWidthPercent(percent: Int) = graph.floatingController.applyMaxWidthPercent(percent)
    override fun applyFloatingPaddingHorizontal(paddingDp: Int) = graph.floatingController.applyPaddingHorizontal(paddingDp)
    override fun applyFloatingPaddingVertical(paddingDp: Int) = graph.floatingController.applyPaddingVertical(paddingDp)
    override fun applyFloatingCornerRadius(radiusDp: Int) = graph.floatingController.applyCornerRadius(radiusDp)
    override fun applyFloatingKaraokeHighlightColor(color: Int) = graph.floatingController.applyKaraokeHighlightColor(color)
    override fun lyricsContentDisplayMode(): LyricsContentDisplayMode = LyricsSettingsStore.getContentDisplayMode(this)
    override fun lyricsLineDisplayMode(): LyricsLineDisplayMode = LyricsSettingsStore.getLineDisplayMode(this)
    override fun lyricsSwitchAnimationMode(): LyricsSwitchAnimationMode = LyricsSettingsStore.getSwitchAnimationMode(this)
    override fun karaokeLyricsEnabled(): Boolean = LyricsSettingsStore.isKaraokeLyricsEnabled(this)
    override fun setLyricsContentDisplayMode(mode: LyricsContentDisplayMode) = LyricsSettingsStore.setContentDisplayMode(this, mode)
    override fun setLyricsLineDisplayMode(mode: LyricsLineDisplayMode) = LyricsSettingsStore.setLineDisplayMode(this, mode)
    override fun setLyricsSwitchAnimationMode(mode: LyricsSwitchAnimationMode) = LyricsSettingsStore.setSwitchAnimationMode(this, mode)
    override fun setKaraokeLyricsEnabled(enabled: Boolean) = LyricsSettingsStore.setKaraokeLyricsEnabled(this, enabled)
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
        item: LocalLyricsUiItem,
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

    override fun currentLyricsState(): CurrentLyricsUiState {
        val media = graph.lyricsController.getCurrentMediaInfo()
        val offsetMs = media?.let { LyricsOffsetStore.getOffsetMs(this, it.toSongIdentity()) } ?: 0L
        val localInfo = media?.let {
            LyricsStorage.getLocalLyricsInfo(
                context = this,
                title = it.title,
                artist = it.artist,
                duration = it.durationMs
            )
        }
        val localWordByWord = media?.let {
            LyricsStorage.hasKaraokeLyrics(
                context = this,
                title = it.title,
                artist = it.artist,
                duration = it.durationMs
            )
        } == true
        return CurrentLyricsUiState(
            media = media?.toUiInfo(),
            localSourceText = localInfo?.let { localizedLocalLyricsSource(it) },
            plainLyricsTitle = localInfo?.friendlyTitle,
            plainLyricsDownloaded = localInfo?.source == LyricsStorage.SOURCE_DOWNLOADED,
            hasPlainLyrics = localInfo != null,
            canRemoveAllLyrics = localInfo != null && localInfo.source != LyricsStorage.SOURCE_KARAOKE_FALLBACK,
            localWordByWord = localWordByWord,
            karaokeEnabled = LyricsSettingsStore.isKaraokeLyricsEnabled(this),
            offsetMs = offsetMs
        )
    }

    override fun recentLyricsState(limit: Int): RecentLyricsUiState {
        val media = graph.lyricsController.getCurrentMediaInfo()?.takeUnless { it.isEmpty }
        return RecentLyricsUiState(
            currentItem = currentLocalLyricsItem(media),
            recentLyrics = LyricsStorage.listRecentLyrics(this, limit).map { toUiItem(it) },
            media = media?.toUiInfo()
        )
    }

    override fun lyricsSettingsState(): LyricsSettingsUiState {
        return LyricsSettingsUiState(
            selectedSource = LyricsSettingsStore.getLyricsSearchSource(this),
            sourceOptions = LyricsSearchSource.entries,
            autoSearchOnline = LyricsSettingsStore.isAutoSearchOnlineEnabled(this),
            autoSaveLocal = LyricsSettingsStore.isAutoSaveLocalEnabled(this),
            lyricsDirectoryPath = LyricsStorage.getLyricsDirRawPath(this)
        )
    }

    override fun languageSettingsState(): LanguageSettingsUiState {
        return LanguageSettingsUiState(
            displayName = LanguageSettingsStore.currentDisplayName(this),
            currentMode = LanguageSettingsStore.getMode(this),
            options = listOf(
                LanguageOptionUiItem(
                    mode = LanguageSettingsStore.MODE_SYSTEM,
                    title = getString(R.string.ui_follow_system),
                    subtitle = getString(R.string.ui_follow_system)
                ),
                LanguageOptionUiItem(
                    mode = LanguageSettingsStore.MODE_ZH_CN,
                    title = getString(R.string.ui_chinese_simplified),
                    subtitle = getString(R.string.ui_chinese_simplified)
                ),
                LanguageOptionUiItem(
                    mode = LanguageSettingsStore.MODE_EN,
                    title = getString(R.string.ui_english),
                    subtitle = getString(R.string.ui_english)
                )
            )
        )
    }

    override fun setLanguageMode(mode: String) {
        LanguageSettingsStore.setMode(this, mode)
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

private fun MainActivityUiHost.currentLocalLyricsItem(media: com.andsi.airlyrics.media.model.CurrentMediaInfo?): LocalLyricsUiItem? {
    media ?: return null
    val info = LyricsStorage.getLocalLyricsInfo(
        context = this,
        title = media.title,
        artist = media.artist,
        duration = media.durationMs
    ) ?: return null
    val hasWordByWord = LyricsStorage.hasKaraokeLyrics(
        context = this,
        title = media.title,
        artist = media.artist,
        duration = media.durationMs
    )
    return LyricsStorage.LocalLyricsItem(
        name = info.fileName,
        modifiedTimeMillis = info.updatedAt,
        sizeBytes = 0L,
        title = info.title,
        artist = info.artist,
        source = info.source,
        provider = info.provider,
        hasPlainLyrics = true,
        hasKaraokeLyrics = hasWordByWord
    ).let { toUiItem(it) }
}

private fun MainActivityUiHost.toUiItem(item: LyricsStorage.LocalLyricsItem): LocalLyricsUiItem {
    return LocalLyricsUiItem(
        name = item.name,
        modifiedTimeMillis = item.modifiedTimeMillis,
        sizeBytes = item.sizeBytes,
        title = item.title,
        artist = item.artist,
        source = item.source,
        provider = item.provider,
        hasPlainLyrics = item.hasPlainLyrics,
        hasKaraokeLyrics = item.hasKaraokeLyrics,
        displayTitle = item.displayTitle,
        subtitle = localizedLocalLyricsSubtitle(item),
        typeText = localizedLocalLyricsType(item),
        metaText = localizedLocalLyricsMeta(item)
    )
}

internal fun LocalLyricsUiItem.toStorageItem(): LyricsStorage.LocalLyricsItem {
    return LyricsStorage.LocalLyricsItem(
        name = name,
        modifiedTimeMillis = modifiedTimeMillis,
        sizeBytes = sizeBytes,
        title = title,
        artist = artist,
        source = source.ifBlank { LyricsStorage.SOURCE_LEGACY },
        provider = provider,
        hasPlainLyrics = hasPlainLyrics,
        hasKaraokeLyrics = hasKaraokeLyrics
    )
}

private fun com.andsi.airlyrics.media.model.CurrentMediaInfo.toUiInfo(): CurrentMediaUiInfo {
    return CurrentMediaUiInfo(
        displayText = displayText,
        isEmpty = isEmpty
    )
}

internal fun LyricsDeleteMode.toStorageDeleteMode(): LyricsStorage.DeleteMode {
    return when (this) {
        LyricsDeleteMode.PLAIN -> LyricsStorage.DeleteMode.PLAIN
        LyricsDeleteMode.KARAOKE -> LyricsStorage.DeleteMode.KARAOKE
        LyricsDeleteMode.ALL -> LyricsStorage.DeleteMode.ALL
    }
}
