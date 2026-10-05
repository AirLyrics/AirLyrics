package com.andsi.airlyrics.app.interaction

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsDraftStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun longDraftRoundTripsAndDeleteWinsOverQueuedWrites() {
        val store = LyricsDraftStore(folder.root)
        val text = "[00:01.00]一行歌词\n".repeat(100000)
        store.write("draft", text)
        assertEquals(text, store.read("draft").get())
        store.write("draft", "changed")
        store.delete("draft").get()
        assertNull(store.read("draft").get())
    }

    @Test fun readerContentPreservesQueuedWriteDeleteOrdering() = runBlocking {
        val content = DraftReaderContent(LyricsDraftStore(folder.root))
        content.save("reader", "first")
        content.save("reader", "latest")
        assertEquals("latest", content.load("reader"))
        content.save("reader", "queued")
        content.discard("reader")
        assertNull(content.load("reader"))
    }

    @Test fun corruptDraftFallsBackAndPruningKeepsOnlyActiveDraft() {
        val store = LyricsDraftStore(folder.root)
        store.write("active", "keep").get()
        store.write("orphan", "discard").get()
        store.prune("active").get()
        assertEquals("keep", store.read("active").get())
        assertNull(store.read("orphan").get())
        File(folder.root, "active.json").writeText("broken json")
        assertNull(store.read("active").get())
    }
}
