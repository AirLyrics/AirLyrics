package com.andsi.airlyrics.app.interaction

import com.andsi.airlyrics.ui.model.ReaderContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal class DraftReaderContent(private val drafts: LyricsDraftStore) : ReaderContent {
    override fun save(id: String, text: String) { drafts.write(id, text) }
    override suspend fun load(id: String): String? = runInterruptible(Dispatchers.IO) { drafts.read(id).get() }
    override fun discard(id: String) { drafts.delete(id) }
}
