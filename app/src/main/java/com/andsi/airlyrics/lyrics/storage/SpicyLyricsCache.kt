package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.util.AtomicFile
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.WordLyricsDocument
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.providers.SpicyLyricsParser
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Private, non-backed-up, bounded-lifetime documents; never use the user's lyrics directory. */
internal class SpicyLyricsCache(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val PREFIX = "spicy:"
        private val lock = Any()
        fun forContext(context: Context) = SpicyLyricsCache(File(context.noBackupFilesDir, "spicy-lyrics"))
        fun key(title: String, artist: String, duration: Long): String = MessageDigest.getInstance("SHA-256")
            .digest(org.json.JSONArray(listOf(title.trim(), artist.trim(), duration)).toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    private fun file(key: String): File {
        require(Regex("[0-9a-f]{64}").matches(key))
        return File(directory, "$key.json")
    }
    private fun document(key: String): JSONObject? {
        val f = file(key)
        return runCatching {
            val doc = JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8))
            val at = doc.getLong("fetchedAt")
            if (clock() < at || clock() - at >= SpicyLyricsParser.RETENTION_MS) {
                AtomicFile(f).delete(); LyricsStorage.markContentChanged(); null
            } else doc
        }.getOrElse {
            if (f.exists() || File(f.path + ".bak").exists() || File(f.path + ".new").exists()) { AtomicFile(f).delete(); LyricsStorage.markContentChanged() }
            null
        }
    }
    private fun result(key: String, doc: JSONObject): LyricsProviderResult? {
        val original = SpicyLyricsParser.parse(doc.getString("raw"), key, doc.getLong("fetchedAt")) ?: return null
        if (original.isUntimed) {
            val text = if (doc.has("textEdit")) doc.getString("textEdit") else original.untimedLyrics!!.text
            return original.copy(untimedLyrics = original.untimedLyrics!!.copy(text = text))
        }
        val edit = doc.optString("edit")
        if (edit.isBlank() && !doc.has("wordEdit")) return original
        return if (original.wordByWordLines.isNotEmpty()) {
            val words = doc.optString("wordEdit").takeIf { it.isNotEmpty() }
                ?.let(WordByWordLyricsJsonCodec::parseWordByWordLinesJson)
                ?: WordByWordLrcParser.parseImport(edit).wordByWordLines
            original.copy(plainLrc = WordByWordLrcParser.wordByWordLinesToPlainLrc(words.filterNot { it.isBackground }),
                translatedLrc = if (doc.has("translatedEdit")) doc.optString("translatedEdit").takeIf { it.isNotEmpty() } else original.translatedLrc,
                wordByWordLines = words)
        } else original.copy(plainLrc = edit, translatedLrc = null)
    }
    fun read(key: String): LyricsProviderResult? = synchronized(lock) {
        runCatching { document(key)?.let { result(key, it) } }.getOrNull()
    }
    fun readForTrack(key: String, spotifyId: String): LyricsProviderResult? = synchronized(lock) {
        runCatching {
            document(key)?.takeIf {
                JSONObject(it.getString("raw")).getJSONObject("response")
                    .getJSONObject("Body").getString("id") == spotifyId
            }?.let { result(key, it) }
        }.getOrNull()
    }
    fun readForSearch(key: String, searchKey: String): LyricsProviderResult? = synchronized(lock) {
        runCatching {
            document(key)?.takeIf { it.optString("searchKey", key) == searchKey }?.let { result(key, it) }
        }.getOrNull()
    }
    fun save(key: String, title: String, artist: String, album: String, duration: Long, raw: String, searchKey: String = key, fetchedAtMs: Long = clock()): LyricsProviderResult? = synchronized(lock) {
        val parsed = SpicyLyricsParser.parse(raw, key, fetchedAtMs)
            ?.takeIf { it.access?.isExpired(clock()) == false } ?: return null
        val doc = JSONObject().put("raw", raw).put("fetchedAt", fetchedAtMs).put("updatedAt", fetchedAtMs)
            .put("title", title).put("artist", artist).put("album", album).put("duration", duration).put("searchKey", searchKey)
        write(key, doc)
        parsed
    }
    internal fun snapshot(key: String): ByteArray? = synchronized(lock) {
        if (file(key).exists() || File(file(key).path + ".bak").exists()) AtomicFile(file(key)).readFully() else null
    }

    internal fun restore(key: String, bytes: ByteArray?) = synchronized(lock) {
        val atomic = AtomicFile(file(key))
        if (bytes == null) {
            atomic.delete()
            check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) { "Cache delete failed" }
        } else write(key, bytes)
    }

    private fun write(key: String, document: JSONObject) {
        write(key, document.toString().toByteArray(Charsets.UTF_8))
    }

    private fun write(key: String, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file(key))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        check(atomic.readFully().contentEquals(bytes)) { "Cache write failed" }
    }

    private fun writeEdit(key: String, document: JSONObject) {
        write(key, document)
        LyricsStorage.markContentChanged()
    }

    fun editPlain(key: String, text: String): LyricsStorage.LocalLyricsUpdateResult = synchronized(lock) {
        val doc = document(key) ?: return@synchronized LyricsStorage.LocalLyricsUpdateResult(saved = false)
        val payload = result(key, doc) ?: return@synchronized LyricsStorage.LocalLyricsUpdateResult(saved = false)
        val untimed = payload.untimedLyrics
        if (untimed != null) {
            if (text.isBlank() && untimed.translatedText.isNullOrBlank()) {
                return@synchronized LyricsStorage.LocalLyricsUpdateResult(saved = false)
            }
            if (text != untimed.text) {
                writeEdit(key, doc.put("textEdit", text).put("updatedAt", clock()))
            }
            return@synchronized LyricsStorage.LocalLyricsUpdateResult(saved = true)
        }
        if (payload.wordByWordLines.isNotEmpty()) return@synchronized LyricsStorage.LocalLyricsUpdateResult(saved = false)
        val validation = LrcParser.validateForStorage(text)
        if (validation.isValid && text != payload.plainLrc) {
            writeEdit(key, doc.put("edit", text).put("updatedAt", clock()))
        }
        LyricsStorage.LocalLyricsUpdateResult(saved = validation.isValid, invalidLineNumbers = validation.invalidLineNumbers)
    }

    fun editWords(key: String, edited: WordLyricsDocument): LyricsStorage.LocalLyricsUpdateResult = synchronized(lock) {
        val doc = document(key) ?: return@synchronized LyricsStorage.LocalLyricsUpdateResult(false)
        val original = result(key, doc) ?: return@synchronized LyricsStorage.LocalLyricsUpdateResult(false)
        if (original.wordByWordLines.isEmpty() || edited.lines.isEmpty() || edited.invalidLines().isNotEmpty())
            return@synchronized LyricsStorage.LocalLyricsUpdateResult(false, edited.invalidLines())
        if (original.wordByWordLines == edited.lines && original.translatedLrc == edited.translatedLrc)
            return@synchronized LyricsStorage.LocalLyricsUpdateResult(true)
        writeEdit(key, doc.put("wordEdit", edited.serialize()).put("edit", edited.originalLrc)
            .put("translatedEdit", edited.translatedLrc.orEmpty()).put("updatedAt", clock()))
        LyricsStorage.LocalLyricsUpdateResult(true)
    }

    fun delete(key: String): Boolean = synchronized(lock) {
        val existed = file(key).exists()
        AtomicFile(file(key)).delete()
        if (existed) LyricsStorage.markContentChanged()
        existed
    }
    fun item(key: String): LyricsStorage.LocalLyricsItem? = synchronized(lock) {
        runCatching {
            val doc = document(key) ?: return@synchronized null
            val payload = result(key, doc) ?: return@synchronized null
            LyricsStorage.LocalLyricsItem(PREFIX + key, doc.getLong("updatedAt"), file(key).length(), doc.getString("title"),
                doc.getString("artist"), doc.optString("album"), doc.getLong("duration"), PREFIX + key,
                LyricsStorage.SOURCE_PROVIDER_CACHE, "spicy", true, payload.wordByWordLines.isNotEmpty(), payload.isUntimed)
        }.getOrNull()
    }
    fun list(): List<LyricsStorage.LocalLyricsItem> = synchronized(lock) {
        directory.listFiles()?.map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            ?.filter { it.endsWith(".json") && Regex("[0-9a-f]{64}").matches(it.removeSuffix(".json")) }
            ?.distinct()?.mapNotNull { item(it.removeSuffix(".json")) }.orEmpty()
    }
    fun prune() { list() }
    fun clear(): Boolean = synchronized(lock) {
        val hadFiles = directory.listFiles()?.isNotEmpty() == true
        val success = !directory.exists() || directory.deleteRecursively()
        if (success && hadFiles) LyricsStorage.markContentChanged()
        success
    }
}
