package com.andsi.airlyrics.app.controller

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore

internal sealed interface FloatingFontImportOutcome {
    data class Success(val displayName: String, val fontId: String = "custom") : FloatingFontImportOutcome
    data object UnsupportedFormat : FloatingFontImportOutcome
    data object TooLarge : FloatingFontImportOutcome
    data object TooManyFaces : FloatingFontImportOutcome
    data object InvalidFont : FloatingFontImportOutcome
    data object ReadFailed : FloatingFontImportOutcome
}

internal fun interface FloatingFontImportOperation {
    fun import(uri: Uri): FloatingFontImportOutcome
}

/** Blocking custom-font import. The ViewModel owns dispatching and presentation outcomes. */
internal class FloatingFontImporter(context: Context) : FloatingFontImportOperation {
    private val appContext = context.applicationContext

    override fun import(uri: Uri): FloatingFontImportOutcome {
        return when (val result = FloatingLyricsFontStore.importFont(appContext, uri)) {
            is FloatingLyricsFontStore.ImportResult.Success ->
                FloatingFontImportOutcome.Success(result.displayName, result.id)
            FloatingLyricsFontStore.ImportResult.UnsupportedFormat ->
                FloatingFontImportOutcome.UnsupportedFormat
            FloatingLyricsFontStore.ImportResult.TooLarge -> FloatingFontImportOutcome.TooLarge
            FloatingLyricsFontStore.ImportResult.TooManyFaces -> FloatingFontImportOutcome.TooManyFaces
            FloatingLyricsFontStore.ImportResult.InvalidFont ->
                FloatingFontImportOutcome.InvalidFont
            FloatingLyricsFontStore.ImportResult.ReadFailed -> FloatingFontImportOutcome.ReadFailed
        }
    }
}
