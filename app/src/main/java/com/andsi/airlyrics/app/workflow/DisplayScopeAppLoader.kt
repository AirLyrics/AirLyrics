package com.andsi.airlyrics.app.workflow

import android.content.Context
import android.content.Intent

@Suppress("DEPRECATION")
internal fun loadDisplayScopeChoices(context: Context): List<DisplayScopeAppChoice> {
    val packageManager = context.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    )
    return intents
        .asSequence()
        .flatMap { intent -> packageManager.queryIntentActivities(intent, 0).asSequence() }
        .mapNotNull { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            packageName to resolveInfo
        }
        .distinctBy { (packageName) -> packageName }
        .map { (packageName, resolveInfo) ->
            val label = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf(String::isNotBlank)
                ?: packageName
            DisplayScopeAppChoice(
                packageName = packageName,
                label = label,
                icon = runCatching { resolveInfo.loadIcon(packageManager) }.getOrNull()
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DisplayScopeAppChoice::label))
        .toList()
}

