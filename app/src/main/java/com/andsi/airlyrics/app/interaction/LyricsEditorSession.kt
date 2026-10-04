package com.andsi.airlyrics.app.interaction

import android.content.Context
import com.andsi.airlyrics.app.host.toStorageItem
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.lyrics.parser.LrcParser
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
    val finished: LocalLyricsUiChange? = null
)

/** Retains only application context and data; UI subscribes anew after recreation. */
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

    init { drafts.prune(interactions.read("editor")?.getString("id")) }

    fun restore() {
        if (restored) return
        restored = true
        val saved = interactions.read("editor") ?: return
        val item = saved.getBundle("item")?.toEditorItem() ?: return
        val target = LyricsStorage.LocalLyricsEditTarget.entries.find { it.name == saved.getString("target") } ?: return
        val id = saved.getString("id") ?: return
        loading = scope.launch {
            val draft = withContext(Dispatchers.IO) { runCatching { drafts.read(id).get() }.getOrNull() }
            val text = draft ?: withContext(Dispatchers.IO) { runCatching { LyricsStorage.readLocalLyricsItemText(context, item.toStorageItem(), target) }.getOrNull() }
            if (text == null) {
                interactions.remove("editor")
                current.value = EditorSession(id, item, target, "", notice = EditorNotice.READ_FAILED)
            } else {
                current.value = EditorSession(id, item, target, text,
                    saved.getInt("start").coerceIn(0, text.length), saved.getInt("end").coerceIn(0, text.length),
                    saved.getInt("scroll"), notice = if (draft == null) EditorNotice.READ_FAILED else null)
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
            val text = withContext(Dispatchers.IO) { runCatching { LyricsStorage.readLocalLyricsItemText(context, item.toStorageItem(), target) }.getOrNull() }
            if (text == null) interactions.remove("editor")
            current.value = EditorSession(id, item, target, text.orEmpty(), notice = if (text == null) EditorNotice.READ_FAILED else null)
            if (text != null) flush()
        }
    }

    fun edit(text: String, start: Int, end: Int, scroll: Int) {
        val old = current.value ?: return
        if (old.busy || old.finished != null) return
        val next = old.copy(text = text, selectionStart = start.coerceIn(0, text.length),
            selectionEnd = end.coerceIn(0, text.length), scrollY = scroll.coerceAtLeast(0))
        if (next == old) return
        current.value = next
        saveMetadata(next)
        if (text != old.text) {
            pendingWrite?.cancel()
            pendingWrite = scope.launch { delay(300.milliseconds); flush() }
        }
    }

    private fun saveMetadata(session: EditorSession) {
        interactions.write("editor") {
            putString("id", session.id); putBundle("item", session.item.toEditorBundle())
            putString("target", session.target.name)
            putInt("start", session.selectionStart); putInt("end", session.selectionEnd); putInt("scroll", session.scrollY)
        }
    }

    fun flush() {
        pendingWrite?.cancel(); pendingWrite = null
        current.value?.takeIf { it.finished == null && interactions.read("editor") != null }?.let { saveMetadata(it); drafts.write(it.id, it.text) }
    }

    fun cancel() {
        if (current.value?.busy == true) return
        loading?.cancel(); pendingWrite?.cancel()
        interactions.read("editor")?.getString("id")?.let(drafts::delete)
        interactions.remove("editor")
        if (interactions.read("confirmation")?.getString("operation") == "DELETE_EDITOR") interactions.remove("confirmation")
        current.value = null
    }

    fun consumeNotice() { current.value = current.value?.copy(notice = null, invalidLines = emptyList()) }
    fun acknowledgeCompletion() { current.value = null }

    fun check() = operate { session ->
        val result = if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
            LyricsStorage.validateWordByWordLyricsItemText(session.text)
        } else {
            val validation = LrcParser.validateForStorage(session.text)
            LyricsStorage.LocalLyricsUpdateResult(validation.isValid, validation.invalidLineNumbers)
        }
        session.copy(notice = if (result.saved) EditorNotice.VALID else EditorNotice.INVALID, invalidLines = result.invalidLineNumbers)
    }

    fun save() = operate { session ->
        val result = if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
            LyricsStorage.updateWordByWordLyricsItemTextWithResult(context, session.item.toStorageItem(), session.text)
        } else LyricsStorage.updatePlainLyricsItemTextWithResult(context, session.item.toStorageItem(), session.text)
        if (result.saved) {
            BroadcastLyricsChangedPublisher(context).publish(SongIdentity(session.item.title, session.item.artist, session.item.album, session.item.durationMs))
            session.copy(finished = LocalLyricsUiChange.SAVED)
        } else session.copy(notice = if (result.invalidLineNumbers.isNotEmpty()) EditorNotice.INVALID else EditorNotice.SAVE_FAILED, invalidLines = result.invalidLineNumbers)
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
