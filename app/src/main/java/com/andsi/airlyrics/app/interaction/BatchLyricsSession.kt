package com.andsi.airlyrics.app.interaction

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.andsi.airlyrics.app.host.toStorageItem
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.lyrics.exporter.BatchLyricsExporter
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocumentReader
import com.andsi.airlyrics.lyrics.importer.BatchLyricsMatcher
import com.andsi.airlyrics.lyrics.storage.LyricsReplacementOps
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.state.MainInteractionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class BatchLyricsStage { PICKING, EXPORT_PICKING, READING, PREVIEW, DELETE, RUNNING, EXPORTING, FINISHED }
internal enum class BatchLyricsProblem { READ, TOO_LARGE, INVALID, NO_MATCH, AMBIGUOUS, DUPLICATE, CHANGED, SAVE, DELETE, ROLLBACK, RESTRICTED }
internal data class BatchLyricsFailure(val name: String, val problem: BatchLyricsProblem)
internal data class BatchLyricsRow(
    val uri: Uri,
    val name: String,
    val info: BatchLyricsMatcher.Document,
    val fingerprint: String,
    val wordByWord: Boolean,
    val target: LocalLyricsUiItem? = null,
    val problem: BatchLyricsProblem? = null
)
internal data class BatchLyricsState(
    val stage: BatchLyricsStage,
    val items: List<LocalLyricsUiItem>,
    val directory: String,
    val rows: List<BatchLyricsRow> = emptyList(),
    val completed: Int = 0,
    val failures: List<BatchLyricsFailure> = emptyList(),
    val interrupted: Boolean = false,
    val exporting: Boolean = false,
    val exportFailed: Boolean = false
)

/** ViewModel-owned work; rotation never repeats an operation or retains an Activity. */
internal class BatchLyricsSession(
    context: Context,
    private val interactions: MainInteractionState,
    private val scope: CoroutineScope
) {
    private val context = context.applicationContext
    private val current = MutableStateFlow<BatchLyricsState?>(null)
    val state = current.asStateFlow()
    private var restored = false

    fun restore() {
        if (restored) return
        restored = true
        val saved = interactions.read("batchLyrics") ?: return
        val items = (0 until saved.getInt("count")).mapNotNull { saved.getBundle("item$it")?.toEditorItem() }
        val stage = BatchLyricsStage.entries.find { it.name == saved.getString("stage") } ?: return
        current.value = BatchLyricsState(stage, items, saved.getString("directory").orEmpty(), exporting = saved.getBoolean("exporting"))
        when (stage) {
            BatchLyricsStage.PREVIEW, BatchLyricsStage.READING -> filesSelected(saved.getStringArrayList("uris").orEmpty().map(Uri::parse))
            BatchLyricsStage.RUNNING, BatchLyricsStage.EXPORTING, BatchLyricsStage.FINISHED -> current.value = current.value?.copy(stage = BatchLyricsStage.FINISHED, interrupted = true)
            else -> Unit
        }
    }

    fun begin(items: List<LocalLyricsUiItem>, delete: Boolean): Boolean {
        if (current.value?.stage == BatchLyricsStage.PICKING) dismiss()
        if (current.value != null || items.isEmpty()) return false
        restored = true
        current.value = BatchLyricsState(if (delete) BatchLyricsStage.DELETE else BatchLyricsStage.PICKING,
            items.distinctBy { it.indexKey.ifBlank { it.name } }, LyricsStorage.getLyricsDirRawPath(context))
        persist()
        return true
    }

    fun beginExport(items: List<LocalLyricsUiItem>): Boolean {
        if (current.value != null || items.isEmpty()) return false
        restored = true
        current.value = BatchLyricsState(BatchLyricsStage.EXPORT_PICKING,
            items.distinctBy { it.indexKey.ifBlank { "root:${it.name}" } },
            LyricsStorage.getLyricsDirRawPath(context), exporting = true)
        persist()
        return true
    }

    fun exportDestinationSelected(uri: Uri?) {
        restore()
        val snapshot = current.value?.takeIf { it.stage == BatchLyricsStage.EXPORT_PICKING } ?: return
        if (uri == null) { dismiss(); return }
        current.value = snapshot.copy(stage = BatchLyricsStage.EXPORTING)
        persist()
        // Retain the destination grant while the ViewModel-owned writer survives rotation.
        var ownedGrant = false
        if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) {
            ownedGrant = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.isSuccess
        }
        if (ownedGrant) interactions.write("batchLyrics") { putString("exportGrant", uri.toString()) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BatchLyricsExporter.export(context, snapshot.items.map { it.toStorageItem() }, snapshot.directory, uri)
                }
                current.value = snapshot.copy(stage = BatchLyricsStage.FINISHED, completed = result.exported,
                    exportFailed = result.writeFailed, failures = result.failures.map {
                        BatchLyricsFailure(it.name, BatchLyricsProblem.valueOf(it.problem.name))
                    })
                persist()
            } finally {
                if (ownedGrant) runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
            }
        }
    }

    private fun persist(uris: List<Uri>? = null) {
        val value = current.value ?: return
        interactions.write("batchLyrics") {
            putString("stage", value.stage.name)
            putBoolean("exporting", value.exporting)
            putString("directory", value.directory)
            putInt("count", value.items.size)
            value.items.forEachIndexed { index, item -> putBundle("item$index", item.toEditorBundle()) }
            if (uris != null) putStringArrayList("uris", ArrayList(uris.map(Uri::toString)))
        }
    }

    fun filesSelected(uris: List<Uri>) {
        restore()
        val snapshot = current.value ?: return
        if (snapshot.stage !in setOf(BatchLyricsStage.PICKING, BatchLyricsStage.READING, BatchLyricsStage.PREVIEW)) return
        if (uris.isEmpty()) { dismiss(); return }
        val uniqueUris = uris.distinct()
        val ownedGrants = interactions.read("batchLyrics")?.getStringArrayList("ownedGrants").orEmpty().toMutableSet()
        uniqueUris.forEach { uri ->
            if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    .onSuccess { ownedGrants += uri.toString() }
            }
        }
        interactions.write("batchLyrics") { putStringArrayList("ownedGrants", ArrayList(ownedGrants)) }
        current.value = snapshot.copy(stage = BatchLyricsStage.READING)
        persist(uniqueUris)
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                val read = uniqueUris.map { uri ->
                    val result = BatchLyricsDocumentReader.read(context, uri)
                    BatchLyricsRow(uri, result.name, result.document?.matchInfo ?: BatchLyricsMatcher.Document(result.name),
                        result.fingerprint, result.document?.words?.isNotEmpty() == true,
                        problem = result.failure?.let { BatchLyricsProblem.valueOf(it.name) })
                }
                // The list may show both a local copy and an online cache for the same exact identity.
                val targets = snapshot.items.filter { it.indexKey.isNotBlank() && it.title.isNotBlank() }
                    .groupBy { SongIdentity(it.title, it.artist, it.album, it.durationMs).storageKey() }
                    .values.map { group -> group.firstOrNull { it.source != LyricsStorage.SOURCE_PROVIDER_CACHE } ?: group.first() }
                val matches = BatchLyricsMatcher.match(read.map { it.info }, targets.map {
                    BatchLyricsMatcher.Target(it.indexKey, SongIdentity(it.title, it.artist, it.album, it.durationMs))
                })
                read.mapIndexed { index, row ->
                    val match = matches[index]
                    row.copy(target = if (row.problem == null) targets.find { it.indexKey == match.target?.key } else null,
                        problem = row.problem ?: match.problem?.let { BatchLyricsProblem.valueOf(it.name) })
                }
            }
            current.value = snapshot.copy(stage = BatchLyricsStage.PREVIEW, rows = rows)
            persist()
        }
    }

    fun confirm() {
        val snapshot = current.value ?: return
        if (snapshot.stage !in setOf(BatchLyricsStage.PREVIEW, BatchLyricsStage.DELETE)) return
        current.value = snapshot.copy(stage = BatchLyricsStage.RUNNING)
        persist()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                var completed = 0
                val failures = mutableListOf<BatchLyricsFailure>()
                val publisher = BroadcastLyricsChangedPublisher(context)
                if (snapshot.stage == BatchLyricsStage.DELETE) {
                    snapshot.items.forEach { item ->
                        val deleted = runCatching {
                            LyricsStorage.withStorageLock {
                                check(snapshot.directory == LyricsStorage.getLyricsDirRawPath(context))
                                val currentItem = LyricsStorage.listAllLyrics(context).find { it.indexKey == item.indexKey && it.name == item.name }
                                check(currentItem == item.toStorageItem())
                                LyricsStorage.deleteLocalLyricsItem(context, currentItem)
                            }
                        }.getOrNull()
                        if (deleted is LyricsStorage.DeleteLocalLyricsItemResult.Deleted) {
                            completed++
                            publisher.publishDeleted(deleted.target)
                        } else failures += BatchLyricsFailure(item.displayTitle, BatchLyricsProblem.DELETE)
                    }
                } else {
                    snapshot.rows.filter { it.target != null && it.problem == null }.forEach { row ->
                        val item = checkNotNull(row.target)
                        var problem = BatchLyricsProblem.SAVE
                        val saved = runCatching {
                            val read = BatchLyricsDocumentReader.read(context, row.uri)
                            if (read.fingerprint != row.fingerprint || read.document == null) problem = BatchLyricsProblem.CHANGED
                            check(read.fingerprint == row.fingerprint && read.document != null)
                            LyricsStorage.withStorageLock {
                                check(snapshot.directory == LyricsStorage.getLyricsDirRawPath(context))
                                LyricsStorage.replaceSavedLyrics(context, item.toStorageItem(), read.document)
                            }
                        }.getOrNull()
                        if (saved == LyricsReplacementOps.Result.SAVED) {
                            completed++
                            publisher.publish(SongIdentity(item.title, item.artist, item.album, item.durationMs))
                        } else {
                            if (saved == LyricsReplacementOps.Result.STALE) problem = BatchLyricsProblem.CHANGED
                            if (saved == LyricsReplacementOps.Result.ROLLBACK_FAILED) problem = BatchLyricsProblem.ROLLBACK
                            failures += BatchLyricsFailure(row.name, problem)
                        }
                    }
                }
                snapshot.copy(stage = BatchLyricsStage.FINISHED, completed = completed, failures = failures)
            }
            current.value = result
            interactions.remove("savedLyricsSelection")
            persist()
        }
    }

    fun dismiss() {
        if (current.value?.stage in setOf(BatchLyricsStage.READING, BatchLyricsStage.RUNNING, BatchLyricsStage.EXPORTING)) return
        interactions.read("batchLyrics")?.getStringArrayList("ownedGrants")?.forEach { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        interactions.read("batchLyrics")?.getString("exportGrant")?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        current.value = null
        interactions.remove("batchLyrics")
    }
}
