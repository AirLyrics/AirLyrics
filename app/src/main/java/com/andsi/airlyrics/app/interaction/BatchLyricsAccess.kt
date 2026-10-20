package com.andsi.airlyrics.app.interaction

import android.content.Context
import android.net.Uri
import android.content.Intent

internal interface BatchLyricsAccess {
    fun held(uri: Uri, flags: Int): Boolean
    fun take(uri: Uri, flags: Int): Boolean
    fun release(uri: Uri, flags: Int)
}

internal class AndroidBatchLyricsAccess(context: Context) : BatchLyricsAccess {
    private val resolver = context.applicationContext.contentResolver
    override fun held(uri: Uri, flags: Int) = resolver.persistedUriPermissions.any {
        it.uri == uri && (if (flags == Intent.FLAG_GRANT_READ_URI_PERMISSION) it.isReadPermission else it.isWritePermission)
    }
    override fun take(uri: Uri, flags: Int) = runCatching { resolver.takePersistableUriPermission(uri, flags) }.isSuccess
    override fun release(uri: Uri, flags: Int) { runCatching { resolver.releasePersistableUriPermission(uri, flags) } }
}
