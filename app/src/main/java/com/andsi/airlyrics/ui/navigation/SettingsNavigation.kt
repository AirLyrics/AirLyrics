package com.andsi.airlyrics.ui.navigation

internal enum class SettingsNavigationDirection { PUSH, POP }

/** A user-initiated move between adjacent levels of the settings hierarchy. */
internal data class SettingsNavigation(
    val from: SettingsSubPage,
    val to: SettingsSubPage,
    val direction: SettingsNavigationDirection,
    val depth: Int
) {
    companion object {
        fun between(from: SettingsSubPage, to: SettingsSubPage): SettingsNavigation? = when {
            to.parentPage() == from -> SettingsNavigation(from, to, SettingsNavigationDirection.PUSH, to.depth())
            from.parentPage() == to -> SettingsNavigation(from, to, SettingsNavigationDirection.POP, from.depth())
            else -> null
        }
    }
}

private fun SettingsSubPage.depth(): Int = when (this) {
    SettingsSubPage.HOME -> 0
    SettingsSubPage.SYSTEM, SettingsSubPage.LYRICS, SettingsSubPage.ABOUT -> 1
    SettingsSubPage.SAVED_LYRICS, SettingsSubPage.APPLE_ACCOUNT -> 2
}
