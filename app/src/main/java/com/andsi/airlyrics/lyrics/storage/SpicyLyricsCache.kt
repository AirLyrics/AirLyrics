package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.util.AtomicFile
import com.andsi.airlyrics.lyrics.LyricsProviderResult
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
        val edit = doc.optString("edit")
        if (edit.isBlank()) return original
        return if (original.wordByWordLines.isNotEmpty()) {
            val words = doc.optString("wordEdit").takeIf { it.isNotEmpty() }
                ?.let(WordByWordLyricsJsonCodec::parseWordByWordLinesJson)
                ?: WordByWordLrcParser.parseImport(edit).wordByWordLines
            original.copy(plainLrc = WordByWordLrcParser.wordByWordLinesToPlainLrc(words.filterNot { it.isBackground }), translatedLrc = null, wordByWordLines = words)
        } else original.copy(plainLrc = edit, translatedLrc = null)
    }
    fun read(key: String): LyricsProviderResult? = synchronized(lock) {
        runCatching { document(key)?.let { result(key, it) } }.getOrNull()
    }
    fun save(key: String, title: String, artist: String, album: String, duration: Long, raw: String): LyricsProviderResult? = synchronized(lock) {
        val at = clock()
        val parsed = SpicyLyricsParser.parse(raw, key, at) ?: return null
        val doc = JSONObject().put("raw", raw).put("fetchedAt", at).put("updatedAt", at)
            .put("title", title).put("artist", artist).put("album", album).put("duration", duration)
        write(key, doc)
        parsed
    }
    private fun write(key: String, document: JSONObject) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file(key))
        val stream = atomic.startWrite()
        try { stream.write(document.toString().toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        LyricsStorage.markContentChanged()
    }
    fun edit(key: String, text: String, word: Boolean): LyricsStorage.LocalLyricsUpdateResult = synchronized(lock) {
        val doc = document(key) ?: return@synchronized LyricsStorage.LocalLyricsUpdateResult(false)
        val payload = result(key, doc) ?: return@synchronized LyricsStorage.LocalLyricsUpdateResult(false)
        if (word != payload.wordByWordLines.isNotEmpty()) return@synchronized LyricsStorage.LocalLyricsUpdateResult(false)
        val validation = if (word) {
            val v = WordByWordLrcParser.validateForStorage(text)
            LyricsStorage.LocalLyricsUpdateResult(v.isValid, v.invalidLineNumbers)
        } else {
            val v = LrcParser.validateForStorage(text)
            LyricsStorage.LocalLyricsUpdateResult(v.isValid, v.invalidLineNumbers)
        }
        if (!validation.saved) return@synchronized validation
        val currentText = if (word) WordByWordLrcParser.wordByWordLinesToWordByWordLrc(payload.wordByWordLines) else payload.plainLrc
        if (text == currentText) return@synchronized validation
        if (word) {
            val baseline = WordByWordLrcParser.parse(currentText)
            val edited = WordByWordLrcParser.parse(text).mapIndexed { i, line ->
                val before = baseline.getOrNull(i)
                val precise = payload.wordByWordLines.getOrNull(i)
                if (before == null || precise == null || before.segments.size != line.segments.size) line
                else line.copy(
                    isBackground = precise.isBackground,
                    startMs = if (line.startMs == before.startMs) precise.startMs else line.startMs,
                    endMs = if (line.segments.map { it.startMs } == before.segments.map { it.startMs }) precise.endMs else line.endMs,
                    segments = line.segments.mapIndexed { j, segment ->
                        if (segment.startMs == before.segments[j].startMs) precise.segments[j].copy(text = segment.text) else segment
                    }
                )
            }
            doc.put("wordEdit", WordByWordLyricsJsonCodec.wordByWordLinesToJson(edited))
        }
        // fetchedAt is immutable, even for edits. The original response (including marks) survives intact.
        write(key, doc.put("edit", text).put("updatedAt", clock()))
        validation
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
                LyricsStorage.SOURCE_PROVIDER_CACHE, "spicy", true, payload.wordByWordLines.isNotEmpty())
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
