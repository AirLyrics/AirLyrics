package com.andsi.airlyrics.lyrics.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal object LyricsImportValidator {
    private const val MAX_IMPORT_LYRICS_BYTES = 30L * 1024L * 1024L

    fun isLikelyLyricsDocument(context: Context, uri: Uri): Boolean {
        val fileName = getDocumentDisplayName(context, uri).lowercase()
        val path = uri.lastPathSegment.orEmpty().lowercase()
        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()

        return fileName.endsWith(".lrc") ||
            path.endsWith(".lrc") ||
            path.contains(".lrc") ||
            mimeType.isBlank() ||
            mimeType.startsWith("text/") ||
            mimeType == "application/x-lrc" ||
            mimeType == "application/lrc" ||
            mimeType == "application/octet-stream"
    }

    fun isLyricsDocumentTooLarge(context: Context, uri: Uri): Boolean {
        val projection = arrayOf(OpenableColumns.SIZE)
        val size = runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    null
                }
            }
        }.getOrNull() ?: return false

        return size > MAX_IMPORT_LYRICS_BYTES
    }

    private fun getDocumentDisplayName(context: Context, uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
    }
}
