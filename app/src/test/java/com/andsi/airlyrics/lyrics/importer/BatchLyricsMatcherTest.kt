package com.andsi.airlyrics.lyrics.importer

import com.andsi.airlyrics.core.model.SongIdentity
import org.junit.Assert.*
import org.junit.Test

class BatchLyricsMatcherTest {
    private val first = target("one", "Song One", "Artist")
    private val second = target("two", "Song Two", "Artist")

    @Test fun metadataAndBothFilenameOrdersMatchOnlySelectedSongs() {
        val docs = listOf(BatchLyricsMatcher.Document("unrelated.lrc", "  SONG ONE ", "artist"),
            BatchLyricsMatcher.Document("Artist - Song Two.lrc"), BatchLyricsMatcher.Document("not selected.lrc"))
        val matches = BatchLyricsMatcher.match(docs, listOf(first, second))
        assertEquals(listOf("one", "two", null), matches.map { it.target?.key })
        assertEquals(BatchLyricsMatcher.Problem.NO_MATCH, matches.last().problem)
        assertEquals("two", BatchLyricsMatcher.match(listOf(BatchLyricsMatcher.Document("Song Two - Artist.ttml")), listOf(first, second)).single().target?.key)
    }

    @Test fun titleOnlyRequiresUniqueCandidateAndDoesNotMergeVersions() {
        val alternate = target("live", "Song One", "Artist", 240_000)
        val result = BatchLyricsMatcher.match(listOf(BatchLyricsMatcher.Document("Song One.lrc")), listOf(first, alternate))
        assertEquals(BatchLyricsMatcher.Problem.AMBIGUOUS, result.single().problem)
    }

    @Test fun conflictingArtistTagCannotBeOverriddenByFilename() {
        val result = BatchLyricsMatcher.match(listOf(BatchLyricsMatcher.Document("Artist - Song One.lrc", artist = "Other")), listOf(first, second))
        assertEquals(BatchLyricsMatcher.Problem.NO_MATCH, result.single().problem)
    }

    @Test fun multipleFilesForOneSongAreAllSkippedRegardlessOfOrder() {
        val docs = listOf(BatchLyricsMatcher.Document("Artist - Song One.lrc"), BatchLyricsMatcher.Document("Song One.ttml"))
        for (ordered in listOf(docs, docs.reversed())) {
            val matches = BatchLyricsMatcher.match(ordered, listOf(first, second))
            assertEquals(listOf(null, null), matches.map { it.target })
            assertEquals(List(2) { BatchLyricsMatcher.Problem.DUPLICATE }, matches.map { it.problem })
            val mixed = BatchLyricsMatcher.match(ordered + BatchLyricsMatcher.Document("Song Two.lrc"), listOf(first, second))
            assertEquals(listOf(null, null, second), mixed.map { it.target })
            assertEquals(listOf(BatchLyricsMatcher.Problem.DUPLICATE, BatchLyricsMatcher.Problem.DUPLICATE, null), mixed.map { it.problem })
        }
    }

    @Test fun oneSelectedSongAndOneFileAreAnExplicitMapping() {
        assertEquals(first, BatchLyricsMatcher.match(listOf(BatchLyricsMatcher.Document("anything.lrc")), listOf(first)).single().target)
    }

    @Test fun missingIdentityIsNeverAnImportTarget() {
        assertEquals(BatchLyricsMatcher.Problem.NO_MATCH,
            BatchLyricsMatcher.match(listOf(BatchLyricsMatcher.Document("anything.lrc")), listOf(target("legacy", "", ""))).single().problem)
    }

    @Test fun filenamesContainingHyphensKeepTheFullTitle() {
        val song = target("hyphen", "Song - Live", "A - B")
        assertEquals(song, BatchLyricsMatcher.match(listOf(BatchLyricsMatcher.Document("A - B - Song - Live.lrc")), listOf(song, first)).single().target)
    }

    @Test fun exportedIdentityMustBeSelectedAndNeverFallsBackToAnotherSong() {
        val doc = BatchLyricsMatcher.Document("Song Two.lrc", targetKey = first.key)
        assertEquals(first, BatchLyricsMatcher.match(listOf(doc), listOf(first, second)).single().target)
        assertEquals(BatchLyricsMatcher.Problem.NO_MATCH,
            BatchLyricsMatcher.match(listOf(doc), listOf(second)).single().problem)
    }

    private fun target(key: String, title: String, artist: String, duration: Long = 180_000) =
        BatchLyricsMatcher.Target(key, SongIdentity(title, artist, durationMs = duration))
}
