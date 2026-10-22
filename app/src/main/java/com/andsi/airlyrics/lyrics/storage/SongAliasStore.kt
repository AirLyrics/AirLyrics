package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.util.AtomicFile
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.CachedMatchGuard
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-confirmed title/artist aliases, one mapping per played recording.
 *
 * A mapping is stored for the recording it was confirmed on: title, artist and duration are part
 * of the key, so a different edit (radio cut, live take) cannot silently reuse it. Mappings are
 * only ever created from a user choice or a trusted catalogue entry, never from a translation.
 */
internal object SongAliasStore {
    const val SOURCE_USER = "user"
    const val SOURCE_CATALOGUE = "catalogue"

    data class Alias(
        val originalTitle: String,
        val originalArtist: String,
        val originalArtistAliases: List<String>,
        val aliasTitle: String,
        val aliasArtist: String,
        val source: String,
        val confidence: Double,
        val confirmedAt: Long
    ) {
        /** Native engine entry: the alias artist is also acceptable as a spelling of the artist. */
        fun toJson(): JSONObject = JSONObject()
            .put("original_title", originalTitle)
            .put("original_artist", originalArtist)
            .put("original_artist_aliases", JSONArray(originalArtistAliases.distinct()))
            .put("alias_title", aliasTitle)
            .put("alias_artist", aliasArtist)
            .put("source", source)
            .put("confidence", confidence)
            .put("confirmed_at", confirmedAt)

        companion object {
            fun fromJson(doc: JSONObject): Alias? {
                val originalTitle = doc.optString("original_title")
                val originalArtist = doc.optString("original_artist")
                val aliasTitle = doc.optString("alias_title")
                if (originalTitle.isBlank() || aliasTitle.isBlank()) return null
                val spellings = mutableListOf<String>()
                val array = doc.optJSONArray("original_artist_aliases") ?: JSONArray()
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(spellings::add)
                }
                return Alias(
                    originalTitle = originalTitle,
                    originalArtist = originalArtist,
                    originalArtistAliases = spellings.ifEmpty { listOf(originalArtist) },
                    aliasTitle = aliasTitle,
                    aliasArtist = doc.optString("alias_artist"),
                    source = doc.optString("source", SOURCE_USER),
                    confidence = doc.optDouble("confidence", 1.0),
                    confirmedAt = doc.optLong("confirmed_at", 0L)
                )
            }
        }
    }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "song-aliases")

    private fun file(context: Context, song: SongIdentity) = AtomicFile(
        File(directory(context), song.storageKey() + ".json")
    )

    private fun readAlias(file: AtomicFile): Alias? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return runCatching {
            Alias.fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8)))
        }.getOrNull()
    }

    fun read(context: Context, song: SongIdentity): Alias? = LyricsStorage.withStorageLock {
        readAlias(file(context, song))
    }

    /** Saves (or replaces) the mapping for this recording and returns what was stored. */
    fun save(context: Context, song: SongIdentity, alias: Alias): Alias = LyricsStorage.withStorageLock {
        val target = file(context, song)
        val dir = directory(context)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create alias directory" }
        val stream = target.startWrite()
        try {
            stream.write(alias.toJson().toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
        alias
    }

    /** Removes the mapping; returns true when a mapping existed. */
    fun forget(context: Context, song: SongIdentity): Boolean = LyricsStorage.withStorageLock {
        val target = file(context, song)
        val existed = target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()
        target.delete()
        existed
    }

    fun all(context: Context): List<Alias> = LyricsStorage.withStorageLock {
        directory(context).listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapNotNull { stored -> readAlias(AtomicFile(stored)) }
            ?: emptyList()
    }

    /** Payload accepted by `SongAliasNative.registerJson`. */
    fun toNativeJson(aliases: List<Alias>): String {
        val array = JSONArray()
        aliases.forEach { alias -> array.put(alias.toJson()) }
        return array.toString()
    }

    /**
     * Persists the catalogue alias for an online match, so the next lookup for the same
     * recording is trusted instead of being re-verified (or wrongly invalidated).
     */
    fun rememberCatalogueAlias(
        context: Context,
        song: SongIdentity,
        matchedTitle: String,
        matchedArtist: String
    ) {
        if (matchedTitle.isBlank()) return
        if (CachedMatchGuard.isTrusted(song.title, matchedTitle, null)) return
        save(
            context,
            song,
            Alias(
                originalTitle = song.title,
                originalArtist = song.artist,
                originalArtistAliases = listOf(song.artist),
                aliasTitle = matchedTitle,
                aliasArtist = matchedArtist,
                source = SOURCE_CATALOGUE,
                confidence = 0.85,
                confirmedAt = System.currentTimeMillis()
            )
        )
    }
}
