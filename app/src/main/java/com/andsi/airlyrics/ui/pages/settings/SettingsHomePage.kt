package com.andsi.airlyrics.ui.pages.settings

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.layout.ResponsiveColumns
import com.andsi.airlyrics.ui.layout.ResponsivePage
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceList
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.theme.colorAccentText

internal fun createSettingsHomePage(activity: MainUiHost, animateChildren: Boolean = true): View  = with(activity) createSettingsHomePage@ {
    // The accent picker owns its height animation. A second CHANGING
    // LayoutTransition here would animate the same layout work twice.
    val container = pageContainer(activity, animateChanges = false)
    val lyricsSettings = lyricsSettingsState()

    container.addView(settingsHomeHeader())
    val categories = ResponsiveColumns(activity)

    categories.addLeading(
        settingsCategoryCard(
            title = getString(R.string.ui_lyrics),
            subtitle = getString(R.string.ui_lyrics_settings_summary),
            status = "${localizedPlainLyricsSourceList(lyricsSettings.selectedPlainLyricsSources)} · ${getString(if (lyricsSettings.autoSaveLocal) R.string.ui_auto_save else R.string.ui_no_auto_save)}",
            iconRes = R.drawable.ic_air_music_note
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.LYRICS)
        }
    )

    categories.addView(
        settingsCategoryCard(
            title = getString(R.string.ui_system),
            subtitle = getString(R.string.ui_overlay_and_notification_permissions),
            status = permissionSummary(),
            iconRes = R.drawable.ic_air_shield
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.SYSTEM)
        }
    )

    categories.addLeading(aboutSettingsCard())
    container.addView(categories)
    return ResponsivePage(activity, container, "scroll.SETTINGS.HOME").apply {
        if (animateChildren) post { animateChildrenCascade(activity, container) }
    }
}

private fun MainUiHost.aboutSettingsCard(): View {
    val card = settingsCategoryCard(
        title = getString(R.string.ui_about),
        subtitle = getString(R.string.ui_version_project_link_and_changelog),
        status = "AirLyrics ${getAppVersionName()}",
        iconRes = R.drawable.ic_air_info
    ) { uiActions.openSettingsSubPage(SettingsSubPage.ABOUT) }
    val updates = appUpdates ?: return card
    val dot = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(dp(7), dp(7))
        setColor(colorAccentText)
    }
    val badge = InsetDrawable(dot, dp(13))
    return FrameLayout(this).apply {
        addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        foregroundGravity = Gravity.TOP or Gravity.END
        bindAppearance(this) { dot.setColor(colorAccentText) }
        bindAppUpdateState(this, updates.availableUpdate) { release ->
            foreground = release?.let { badge }
            ViewCompat.setStateDescription(card, release?.let { getString(R.string.ui_app_update_available_label) })
        }
    }
}
