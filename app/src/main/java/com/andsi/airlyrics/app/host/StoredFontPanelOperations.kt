package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.pages.floating.FontPanelOperations

internal class StoredFontPanelOperations(private val host: MainUiHost) : FontPanelOperations {
    override val changes get() = FloatingLyricsFontStore.changes

    override fun fonts() = FloatingLyricsFontStore.fonts(host)
    override fun collections() = FloatingLyricsFontStore.collections(host)
    override fun selectedId() = host.floatingStyle().fontId
    override fun previewTypeface(id: String) =
        FloatingLyricsFontStore.resolvePreviewTypeface(host, id, host.floatingStyle().fontWeight)

    override fun select(id: String) = FloatingLyricsFontStore.selectFont(host, id)
    override fun remove(id: String) = FloatingLyricsFontStore.removeFont(host, id)
    override fun removeCollection(id: String) = FloatingLyricsFontStore.removeCollection(host, id)

    override suspend fun download(id: String, onProgress: (Int) -> Unit) =
        FloatingLyricsFontStore.downloadFont(host.applicationContext, id, onProgress)
}
