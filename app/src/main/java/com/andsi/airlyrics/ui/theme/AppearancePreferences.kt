package com.andsi.airlyrics.ui.theme

import android.content.Context
import android.content.res.Configuration
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.ThemeAccent

internal data class AppearancePreferences(
    val isDark: Boolean,
    val accent: ThemeAccent,
    val buttonStyle: ButtonStyle
)

/** The application supplies preferences for standalone views and their themed contexts. */
internal interface AppearancePreferencesProvider {
    fun appearancePreferences(context: Context): AppearancePreferences
}

internal fun Context.appearancePreferences(): AppearancePreferences {
    (applicationContext as? AppearancePreferencesProvider)?.let { return it.appearancePreferences(this) }
    val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return AppearancePreferences(
        isDark = nightMode == Configuration.UI_MODE_NIGHT_YES,
        accent = ThemeAccent.DEFAULT,
        buttonStyle = ButtonStyle.DEFAULT
    )
}
