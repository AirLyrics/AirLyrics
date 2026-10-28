package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SongAliasStoreTest : LyricsStorageTestBase() {
    private val song = SongIdentity(
        title = "Radio Love Song",
        artist = "Karen Mok",
        album = "Some Album",
        durationMs = 242_000L
    )

    private fun alias(title: String = "电台情歌", artist: String = "莫文蔚") = SongAliasStore.Alias(
        originalTitle = song.title,
        originalArtist = song.artist,
        originalArtistAliases = listOf("Karen Mok", "莫文蔚"),
        aliasTitle = title,
        aliasArtist = artist,
        source = SongAliasStore.SOURCE_USER,
        confidence = 0.95,
        confirmedAt = 1_700_000_000_000L
    )

    @Test
    fun confirmedMappingIsReusedForTheSameRecording() {
        assertNull(SongAliasStore.read(context, song))

        SongAliasStore.save(context, song, alias())

        val restored = SongAliasStore.read(context, song)
        assertEquals("电台情歌", restored?.aliasTitle)
        assertEquals("莫文蔚", restored?.aliasArtist)
        assertEquals(listOf("Karen Mok", "莫文蔚"), restored?.originalArtistAliases)
    }

    @Test
    fun aDifferentRecordingDoesNotReuseTheMapping() {
        SongAliasStore.save(context, song, alias())

        val live = song.copy(durationMs = 260_000L)
        assertNull(SongAliasStore.read(context, live))
    }

    @Test
    fun mappingCanBeOverwritten() {
        SongAliasStore.save(context, song, alias())
        SongAliasStore.save(context, song, alias(title = "电台情歌 (Live)", artist = "莫文蔚"))

        val restored = SongAliasStore.read(context, song)
        assertEquals("电台情歌 (Live)", restored?.aliasTitle)
        assertEquals(1, SongAliasStore.all(context).size)
    }

    @Test
    fun mappingCanBeCleared() {
        SongAliasStore.save(context, song, alias())

        assertTrue(SongAliasStore.forget(context, song))
        assertNull(SongAliasStore.read(context, song))
        assertFalse(SongAliasStore.forget(context, song))
    }

    @Test
    fun nativePayloadCarriesProvenanceAndSpellings() {
        val payload = SongAliasStore.toNativeJson(listOf(alias()))

        val entry = JSONArray(payload).getJSONObject(0)
        assertEquals("Radio Love Song", entry.getString("original_title"))
        assertEquals("电台情歌", entry.getString("alias_title"))
        assertEquals("user", entry.getString("source"))
        assertEquals(0.95, entry.getDouble("confidence"), 1e-9)
        assertEquals("莫文蔚", entry.getJSONArray("original_artist_aliases").getString(1))
    }

    @Test
    fun unparsableEntryIsIgnoredInsteadOfThrowing() {
        assertNull(SongAliasStore.Alias.fromJson(JSONObject().put("alias_title", "电台情歌")))
        assertNull(SongAliasStore.Alias.fromJson(JSONObject().put("original_title", "Radio Love Song")))
    }
}
