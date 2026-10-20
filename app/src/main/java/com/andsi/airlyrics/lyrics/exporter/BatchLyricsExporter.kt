package com.andsi.airlyrics.lyrics.exporter

import android.content.Context
import android.net.Uri
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stages the archive locally before opening the destination; never modifies source lyrics. */
internal object BatchLyricsExporter {
    enum class Problem { READ, CHANGED, RESTRICTED }
    data class Failure(val name: String, val problem: Problem)
    data class Result(val exported: Int = 0, val failures: List<Failure> = emptyList(), val writeFailed: Boolean = false)

    fun export(context: Context, items: List<LyricsStorage.LocalLyricsItem>, directory: String, destination: Uri): Result {
        val failures = mutableListOf<Failure>()
        var archive: File? = null
        return try {
            val staged = File.createTempFile("lyrics-export-", ".zip", context.cacheDir)
            archive = staged
            var count = 0
            val names = mutableSetOf<String>()
            var revision = Long.MIN_VALUE
            var current = emptyMap<String, LyricsStorage.LocalLyricsItem>()
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                for (item in items.distinctBy { it.indexKey.ifBlank { "root:${it.name}" } }) {
                    var problem = Problem.READ
                    val text = runCatching {
                        LyricsStorage.withStorageLock {
                            if (revision != LyricsStorage.currentRevision()) {
                                current = LyricsStorage.listAllLyrics(context).associateBy { it.indexKey.ifBlank { "root:${it.name}" } }
                                revision = LyricsStorage.currentRevision()
                            }
                            if (directory != LyricsStorage.getLyricsDirRawPath(context) ||
                                current[item.indexKey.ifBlank { "root:${item.name}" }] != item) {
                                problem = Problem.CHANGED
                                error("Selection changed")
                            }
                            if (LyricsStorage.restrictedLyricsAccess(context, item.indexKey)?.allowExport == false) {
                                problem = Problem.RESTRICTED
                                error("Export restricted")
                            }
                            val target = if (item.hasWordByWordLyrics) LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
                                else LyricsStorage.LocalLyricsEditTarget.PLAIN
                            requireNotNull(LyricsStorage.readLocalLyricsItemText(context, item, target)?.takeIf { it.isNotBlank() })
                        }
                    }.getOrElse {
                        failures += Failure(item.displayTitle, problem)
                        continue
                    }
                    val stem = safeStem(item)
                    var name = "$stem.lrc"
                    var suffix = 2
                    while (!names.add(name.lowercase(Locale.ROOT))) name = "$stem (${suffix++}).lrc"
                    // Compression and destination I/O must not hold the playback storage lock.
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(withIdentity(text, item).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    count++
                }
            }
            if (count == 0) return Result(failures = failures)
            requireNotNull(context.contentResolver.openOutputStream(destination, "wt")).use { output ->
                staged.inputStream().use { it.copyTo(output) }
            }
            Result(count, failures)
        } catch (_: Exception) {
            Result(failures = failures, writeFailed = true)
        } finally {
            archive?.delete()
        }
    }

    private fun safeStem(item: LyricsStorage.LocalLyricsItem): String {
        val name = listOf(item.artist, item.title.ifBlank { item.name.substringBeforeLast('.') })
            .filter(String::isNotBlank).joinToString(" - ")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.').take(100).ifBlank { "lyrics" }
        return if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$").matches(name)) "_$name" else name
    }

    private fun withIdentity(text: String, item: LyricsStorage.LocalLyricsItem): String {
        val lines = text.lineSequence().filterNot {
            it.trimStart().startsWith("[airlyrics-key:", ignoreCase = true)
        }.joinToString("\n")
        return if (item.indexKey.isBlank()) lines
            else "[airlyrics-key:${Uri.encode(item.indexKey)}]\n$lines"
    }
}
