package com.andsi.airlyrics.app.interaction

import android.content.Context
import com.andsi.airlyrics.app.host.toStorageItem
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.LyricsAccess
import com.andsi.airlyrics.lyrics.WordLyricsBulkText
import com.andsi.airlyrics.lyrics.WordLyricsDocument
import com.andsi.airlyrics.lyrics.storage.WordLyricsEditorStorage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.model.LocalLyricsUiChange
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.state.MainInteractionState
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class EditorNotice { READ_FAILED, SAVE_FAILED, DELETE_FAILED, VALID, INVALID }
internal data class EditorSession(
    val id: String,
    val item: LocalLyricsUiItem,
    val target: LyricsStorage.LocalLyricsEditTarget,
    val text: String,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val scrollY: Int = 0,
    val busy: Boolean = false,
    val notice: EditorNotice? = null,
    val invalidLines: List<Int> = emptyList(),
    val finished: LocalLyricsUiChange? = null,
    val access: LyricsAccess? = null,
    val untimedTranslation: String? = null,
    val bulkEditing: Boolean = false,
    val timelineScrollY: Int = 0,
) {
    val showsTextEditor: Boolean get() = item.isUntimed || bulkEditing
}

internal class LyricsEditorSession(
    context: Context,
    private val interactions: MainInteractionState,
    private val scope: CoroutineScope,
    private val drafts: LyricsDraftStore = LyricsDraftStore.forSession(context, interactions, "editor-drafts")
) {
    private val context = context.applicationContext
    private val current = MutableStateFlow<EditorSession?>(null)
    val state = current.asStateFlow()
    private var pendingWrite: Job? = null
    private var loading: Job? = null
    private var operation: Job? = null
    private var restored = false
    private var expiryJob: Job? = null
    private var bulkValidation: CachedBulkValidation? = null

    init { drafts.prune(interactions.read("editor")?.getString("id")) }

    fun restore() {
        if (restored) return
        restored = true
        val saved = interactions.read("editor") ?: return
        val item = saved.getBundle("item")?.toEditorItem() ?: return
        val target = LyricsStorage.LocalLyricsEditTarget.entries.find { it.name == saved.getString("target") } ?: return
        val id = saved.getString("id") ?: return
        loading = scope.launch {
            val draft = if (item.source == LyricsStorage.SOURCE_PROVIDER_CACHE) null else withContext(Dispatchers.IO) {
                runCatching { drafts.readDraft(id).get() }.getOrNull()
            }
            val text = draft?.text ?: withContext(Dispatchers.IO) { runCatching { readText(item, target) }.getOrNull() }
            val access = withContext(Dispatchers.IO) { LyricsStorage.restrictedLyricsAccess(context, item.indexKey) }
            val translation = withContext(Dispatchers.IO) { LyricsStorage.untimedTranslation(context, item.toStorageItem()) }
            if (text == null || (item.source == LyricsStorage.SOURCE_PROVIDER_CACHE && access == null)) {
                interactions.remove("editor")
                current.value = EditorSession(id, item, target, "", notice = EditorNotice.READ_FAILED)
            } else {
                current.value = EditorSession(
                    id, item, target, text,
                    selectionStart = saved.getInt("start"),
                    selectionEnd = saved.getInt("end"),
                    scrollY = saved.getInt("scroll"),
                    notice = if ((draft == null) && (item.source != LyricsStorage.SOURCE_PROVIDER_CACHE)) {
                        EditorNotice.READ_FAILED
                    } else null,
                    access = access,
                    untimedTranslation = translation,
                    bulkEditing = draft?.bulkEditing == true,
                    timelineScrollY = saved.getInt("timelineScroll", if (item.isUntimed) 0 else saved.getInt("scroll"))
                )
                watchExpiry()
            }
        }
    }

    fun open(item: LocalLyricsUiItem, target: LyricsStorage.LocalLyricsEditTarget) {
        if (current.value?.busy == true) return
        cancel()
        restored = true
        val id = UUID.randomUUID().toString()
        interactions.write("editor") {
            putString("id", id); putBundle("item", item.toEditorBundle()); putString("target", target.name)
        }
        loading = scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { readText(item, target) }.getOrNull() }
            val access = withContext(Dispatchers.IO) { LyricsStorage.restrictedLyricsAccess(context, item.indexKey) }
            val translation = withContext(Dispatchers.IO) { LyricsStorage.untimedTranslation(context, item.toStorageItem()) }
            if (text == null || (item.source == LyricsStorage.SOURCE_PROVIDER_CACHE && access == null)) {
                interactions.remove("editor")
                current.value = null
                return@launch
            }
            current.value = EditorSession(id, item, target, text, access = access, untimedTranslation = translation)
            watchExpiry()
            flush()
        }
    }

    private fun readText(item: LocalLyricsUiItem, target: LyricsStorage.LocalLyricsEditTarget): String? =
        if (target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD)
            WordLyricsEditorStorage.read(context, item.toStorageItem())
        else LyricsStorage.readLocalLyricsItemText(context, item.toStorageItem(), target)

    fun edit(text: String, start: Int, end: Int, scroll: Int) {
        val old = current.value ?: return
        if (old.busy || old.finished != null) return
        val next = if (old.showsTextEditor) {
            old.copy(
                text = text,
                selectionStart = start.coerceIn(0, text.length),
                selectionEnd = end.coerceIn(0, text.length),
                scrollY = scroll.coerceAtLeast(0),
            )
        } else {
            old.copy(text = text, timelineScrollY = scroll.coerceAtLeast(0))
        }
        if (next == old) return
        current.value = next
        saveMetadata(next)
        if (text != old.text) {
            pendingWrite?.cancel()
            pendingWrite = scope.launch { delay(300.milliseconds); flush() }
        }
    }

    fun switchMode(bulkEditing: Boolean, onInvalid: (List<Int>) -> Unit): EditorSession? {
        val session = current.value ?: return null
        if (session.item.isUntimed || session.busy || (session.finished != null) || (session.bulkEditing == bulkEditing)) {
            return null
        }
        val text = if (bulkEditing) {
            if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
                WordLyricsBulkText.format(WordLyricsDocument.parse(session.text))
            } else session.text
        } else {
            val (timelineText, _, invalidLines) = cachedBulkValidation(session)
            timelineText ?: run {
                onInvalid(invalidLines)
                return null
            }
        }
        val next = session.copy(text = text, bulkEditing = bulkEditing, notice = null, invalidLines = emptyList())
        current.value = next
        flush()
        return next
    }

    private data class BulkValidation(val timelineText: String?, val canSave: Boolean, val invalidLines: List<Int>)

    private data class CachedBulkValidation(val id: String, val text: String, val result: BulkValidation)

    private fun cachedBulkValidation(session: EditorSession): BulkValidation {
        bulkValidation?.let { (id, text, result) ->
            if ((id == session.id) && (text == session.text)) return result
        }
        return validateBulk(session).also { bulkValidation = CachedBulkValidation(session.id, session.text, it) }
    }

    private fun validateBulk(session: EditorSession): BulkValidation {
        if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
            val (document, invalidLines) = WordLyricsBulkText.parse(session.text)
            return BulkValidation(document?.serialize(), !document?.lines.isNullOrEmpty(), invalidLines)
        }
        val (canSave, invalidLines) = LrcParser.validateForStorage(session.text)
        return BulkValidation(session.text.takeIf { invalidLines.isEmpty() }, canSave, invalidLines)
    }

    private fun saveMetadata(session: EditorSession) {
        interactions.write("editor") {
            putString("id", session.id); putBundle("item", session.item.toEditorBundle())
            putString("target", session.target.name)
            putInt("start", session.selectionStart); putInt("end", session.selectionEnd); putInt("scroll", session.scrollY)
            putInt("timelineScroll", session.timelineScrollY)
        }
    }

    fun flush() {
        pendingWrite?.cancel()
        pendingWrite = null
        val session = current.value ?: return
        if ((session.finished != null) || (interactions.read("editor") == null)) return
        saveMetadata(session)
        if (session.item.source != LyricsStorage.SOURCE_PROVIDER_CACHE) {
            drafts.write(session.id, session.text, session.bulkEditing)
        }
    }

    fun cancel() {
        if (current.value?.busy == true) return
        loading?.cancel(); pendingWrite?.cancel(); expiryJob?.cancel()
        bulkValidation = null
        interactions.read("editor")?.getString("id")?.let(drafts::delete)
        interactions.remove("editor")
        if (interactions.read("confirmation")?.getString("operation") == "DELETE_EDITOR") interactions.remove("confirmation")
        current.value = null
    }

    private fun watchExpiry() {
        expiryJob?.cancel()
        val session = current.value ?: return
        val access = session.access ?: return
        expiryJob = scope.launch {
            delay((access.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0).milliseconds)
            if (current.value?.id == session.id) {
                operation?.cancel()
                current.value = current.value?.copy(busy = false)
                cancel()
                withContext(Dispatchers.IO) { LyricsStorage.pruneOnlineCache(context) }
            }
        }
    }

    fun consumeNotice() { current.value = current.value?.copy(notice = null, invalidLines = emptyList()) }
    fun acknowledgeCompletion() {
        expiryJob?.cancel()
        expiryJob = null
        bulkValidation = null
        current.value = null
    }

    fun check() = operate { session ->
        val result = if (session.bulkEditing) {
            val validation = validateBulk(session)
            LyricsStorage.LocalLyricsUpdateResult(validation.canSave, validation.invalidLines)
        } else if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
            val document = WordLyricsDocument.parse(session.text)
            LyricsStorage.LocalLyricsUpdateResult(document.lines.isNotEmpty() && document.invalidLines().isEmpty(), document.invalidLines())
        } else {
            val validation = if (session.item.isUntimed) LrcParser.StorageValidationResult(session.text.isNotBlank() || !session.untimedTranslation.isNullOrBlank())
                else LrcParser.validateForStorage(session.text)
            LyricsStorage.LocalLyricsUpdateResult(validation.isValid, validation.invalidLineNumbers)
        }
        session.copy(notice = if (result.saved) EditorNotice.VALID else EditorNotice.INVALID, invalidLines = result.invalidLineNumbers)
    }

    fun save(onBulkInvalid: ((List<Int>) -> Unit)? = null) {
        val snapshot = current.value ?: return
        if (snapshot.busy || (snapshot.finished != null) || (operation?.isActive == true)) return
        val validation = if (snapshot.bulkEditing) cachedBulkValidation(snapshot) else null
        if ((validation != null) && (!validation.canSave || (validation.timelineText == null))) {
            if (onBulkInvalid != null) onBulkInvalid(validation.invalidLines)
            else current.value = snapshot.copy(notice = EditorNotice.INVALID, invalidLines = validation.invalidLines)
            return
        }
        operate { session ->
            val text = validation?.timelineText ?: session.text
            val result = if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
                WordLyricsEditorStorage.save(context, session.item.toStorageItem(), text)
            } else LyricsStorage.updatePlainLyricsItemTextWithResult(context, session.item.toStorageItem(), text)
            if (result.saved) {
                BroadcastLyricsChangedPublisher(context).publish(SongIdentity(session.item.title, session.item.artist, session.item.album, session.item.durationMs))
                session.copy(finished = LocalLyricsUiChange.SAVED)
            } else session.copy(notice = if (result.invalidLineNumbers.isNotEmpty()) EditorNotice.INVALID else EditorNotice.SAVE_FAILED, invalidLines = result.invalidLineNumbers)
        }
    }

    fun delete() = operate(EditorNotice.DELETE_FAILED) { session ->
        when (val result = LyricsStorage.deleteLocalLyricsItem(context, session.item.toStorageItem())) {
            is LyricsStorage.DeleteLocalLyricsItemResult.Deleted -> {
                BroadcastLyricsChangedPublisher(context).publishDeleted(result.target)
                session.copy(finished = LocalLyricsUiChange.DELETED)
            }
            else -> session.copy(notice = EditorNotice.DELETE_FAILED)
        }
    }

    private fun operate(failure: EditorNotice = EditorNotice.SAVE_FAILED, block: (EditorSession) -> EditorSession) {
        val snapshot = current.value ?: return
        if (snapshot.busy || snapshot.finished != null || operation?.isActive == true) return
        if (snapshot.access?.isExpired() == true) { cancel(); return }
        flush()
        current.value = snapshot.copy(busy = true)
        operation = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { block(snapshot) }.getOrElse { snapshot.copy(notice = failure) }
            }.copy(busy = false)
            if (result.finished != null) {
                pendingWrite?.cancel()
                interactions.remove("editor")
                drafts.delete(result.id)
            }
            current.value = result
        }
    }
}
