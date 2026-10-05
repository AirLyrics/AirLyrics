package com.andsi.airlyrics.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/** Keeps long-lived services on the same locales as AppCompat without changing shared resources. */
internal class LocalizedServiceContext private constructor(base: Context) : ContextWrapper(base) {
    private var cachedConfiguration: Configuration? = null
    private var cachedResources: Resources? = null

    @Synchronized
    override fun getResources(): Resources {
        val baseResources = baseContext.resources
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (appLocales.isEmpty) return baseResources

        val configuration = Configuration(baseResources.configuration)
        // Match AppCompat's ordering: app languages first, then system fallback languages.
        val tags = (appLocales.toLanguageTags() + "," + configuration.locales.toLanguageTags())
            .split(',').filter(String::isNotEmpty).distinct().joinToString(",")
        configuration.setLocales(LocaleList.forLanguageTags(tags))
        if (configuration != cachedConfiguration) {
            cachedResources = baseContext.createConfigurationContext(configuration).resources
            cachedConfiguration = configuration
        }
        return checkNotNull(cachedResources)
    }

    companion object {
        fun wrap(context: Context): Context =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                LocalizedServiceContext(context)
            } else {
                // Android 13+ applies LocaleManager settings to every component itself.
                context
            }
    }
}
