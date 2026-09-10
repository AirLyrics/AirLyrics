package com.andsi.airlyrics.lyrics.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal object LyricsFormatDetector {
    fun detect(
        context: Context,
        uri: Uri,
        content: CharSequence
    ): LyricsDocumentFormat {
        val resolver = context.contentResolver
        val displayName = runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst() && !cursor.isNull(nameColumn)) {
                    cursor.getString(nameColumn)
                } else {
                    null
                }
            }
        }.getOrNull()

        LyricsFormatCatalog.formatFromFileName(displayName)?.let { return it }
        LyricsFormatCatalog.formatFromFileName(uri.lastPathSegment)?.let { return it }

        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        LyricsFormatCatalog.formatFromMimeType(mimeType)?.let { return it }

        return LyricsFormatCatalog.formatFromContent(content)
    }
}
