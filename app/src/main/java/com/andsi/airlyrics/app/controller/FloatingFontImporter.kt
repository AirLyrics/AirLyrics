package com.andsi.airlyrics.app.controller

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore

internal sealed interface FloatingFontImportOutcome {
    data class Success(val displayName: String) : FloatingFontImportOutcome
    data object UnsupportedFormat : FloatingFontImportOutcome
    data object TooLarge : FloatingFontImportOutcome
    data object InvalidFont : FloatingFontImportOutcome
    data object ReadFailed : FloatingFontImportOutcome
}

/** Blocking custom-font import. The ViewModel owns dispatching and presentation outcomes. */
internal class FloatingFontImporter(context: Context) {
    private val appContext = context.applicationContext

    fun import(uri: Uri): FloatingFontImportOutcome {
        return when (val result = FloatingLyricsFontStore.importFont(appContext, uri)) {
            is FloatingLyricsFontStore.ImportResult.Success -> {
                FloatingLyricsStyleStore.setFontFamily(
                    appContext,
                    FloatingLyricsFontFamily.CUSTOM
                )
                FloatingFontImportOutcome.Success(result.displayName)
            }
            FloatingLyricsFontStore.ImportResult.UnsupportedFormat ->
                FloatingFontImportOutcome.UnsupportedFormat
            FloatingLyricsFontStore.ImportResult.TooLarge -> FloatingFontImportOutcome.TooLarge
            FloatingLyricsFontStore.ImportResult.InvalidFont ->
                FloatingFontImportOutcome.InvalidFont
            FloatingLyricsFontStore.ImportResult.ReadFailed -> FloatingFontImportOutcome.ReadFailed
        }
    }
}
