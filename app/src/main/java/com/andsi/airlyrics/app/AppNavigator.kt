package com.andsi.airlyrics.app

import android.content.Intent
import android.net.Uri
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal object AppNavigator {
    fun handleBackNavigation(activity: MainActivity): Boolean {
        if (activity.currentPage == Page.FLOATING && activity.floatingPanelBackHandler?.invoke() == true) {
            return true
        }

        if (activity.currentPage == Page.SETTINGS && activity.settingsSubPage != SettingsSubPage.HOME) {
            activity.settingsSubPage = SettingsSubPage.HOME
            activity.renderCurrentPage()
            return true
        }

        return false
    }

    fun openUrl(activity: MainActivity, url: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
