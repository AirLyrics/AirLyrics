package com.andsi.airlyrics.lyrics.importer

import org.junit.Assert.*
import org.junit.Test

class BatchLyricsDocumentReaderTest {
    @Test fun ordinaryLrcPreservesMetadataForMatching() {
        val result = BatchLyricsDocumentReader.parse("file.lrc", "[ti:Song]\n[ar:Artist]\n[00:01.00]hello", LyricsDocumentFormat.LRC)
        assertNull(result.failure)
        assertEquals("Song", result.document?.title)
        assertEquals("Artist", result.document?.artist)
        assertTrue(result.document!!.words.isEmpty())
    }

    @Test fun enhancedLrcAutomaticallyProducesWordsAndPlainFallback() {
        val result = BatchLyricsDocumentReader.parse("file.lrc", "[00:01.00]<00:01.00>hello <00:02.00>world", LyricsDocumentFormat.LRC)
        assertNull(result.failure)
        assertEquals(2, result.document!!.words.single().segments.size)
        assertTrue(result.document.plainLrc.contains("hello world"))
        assertFalse(result.document.plainLrc.contains("<00:"))
    }

    @Test fun malformedWordTimingIsNotImportedAsOrdinaryText() {
        val result = BatchLyricsDocumentReader.parse("file.lrc", "[00:01.00]<00:bad>hello", LyricsDocumentFormat.LRC)
        assertEquals(BatchLyricsDocumentReader.Failure.INVALID, result.failure)
    }

    @Test fun emptyAndUntimedFilesAreRejected() {
        for (text in listOf("", "hello", "[ti:Song]")) {
            assertEquals(BatchLyricsDocumentReader.Failure.INVALID,
                BatchLyricsDocumentReader.parse("file.lrc", text, LyricsDocumentFormat.LRC).failure)
        }
    }

    @Test fun ttmlDetectsLineAndWordTiming() {
        val plain = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s">hello</p></div></body></tt>"""
        val words = plain.replace("hello", "<span begin=\"1s\" end=\"2s\">hello</span><span begin=\"2s\" end=\"3s\">world</span>")
        assertTrue(BatchLyricsDocumentReader.parse("file.ttml", plain, LyricsDocumentFormat.TTML).document!!.words.isEmpty())
        assertEquals(2, BatchLyricsDocumentReader.parse("file.ttml", words, LyricsDocumentFormat.TTML).document!!.words.single().segments.size)
    }
}
