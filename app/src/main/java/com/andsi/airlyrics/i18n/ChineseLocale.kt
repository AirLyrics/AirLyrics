package com.andsi.airlyrics.i18n

import java.util.Locale

private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")

internal fun Locale.isChineseLanguage(): Boolean {
    return language.equals(Locale.CHINESE.language, ignoreCase = true)
}

internal fun Locale.usesTraditionalChinese(): Boolean {
    return isChineseLanguage() && (
        script.equals("Hant", ignoreCase = true) ||
            country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS
        )
}
