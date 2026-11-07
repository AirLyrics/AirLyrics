package com.andsi.airlyrics.app

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.WordLyricsDocument
import com.andsi.airlyrics.lyrics.storage.LyricsStorageTestBase
import com.andsi.airlyrics.lyrics.storage.WordByWordLyricsStorageOps
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.awaitUiCondition as await
import com.andsi.airlyrics.testutil.descendants
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalWordLyricsEditorTest : LyricsStorageTestBase() {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val line = WordByWordLine(1001, 3789, "Hello world", listOf(
        WordByWordSegment("Hello ", 1001, 2111), WordByWordSegment("world", 2444, 3789)
    ), translatedText = "你好")
    private val originalRaw = "<00:01.001>Hello <00:02.111><00:02.444>world<00:03.789>"

    @Before fun open() {
        assertTrue(WordByWordLyricsStorageOps.saveWordByWordLyrics(context, "Song", "Artist", 10000, listOf(line)))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val host = activity.graph.uiHost
        host.localLyricsRow(host.savedLyricsState().lyrics.single()).performClick()
        await { ShadowDialog.getLatestDialog() is BottomSheetDialog && row() != null }
        settle()
    }

    @After fun close() { controller.close(); ShadowDialog.reset() }

    @Test fun wordFormValidatesTimesAndCancelLeavesSavedLyricsUntouched() {
        row()!!.performClick()
        settle()
        button(R.string.ui_edit_line).performClick()
        await { inputs().size == 7 }
        assertEquals(originalRaw, inputs().first().text.toString())
        val word = inputs().single { it.text.toString() == "world" }
        word.setText("there")
        val end = inputs().single { it.text.toString() == "00:02.111" }
        end.setText("00:00.500")
        assertFalse(button(R.string.ui_confirm_line).isEnabled)
        end.setText("00:02.111")
        assertTrue(button(R.string.ui_confirm_line).isEnabled)
        button(R.string.ui_confirm_line).performClick()
        await { draft().lines.single().text == "Hello there" }
        assertEquals(line, WordByWordLyricsStorageOps.readWordByWordLyrics(context, "Song", "Artist", 10000).single())
        settle()
        button(R.string.ui_cancel).performClick()
        settle()
        assertNull(activity.graph.uiHost.editorSession.state.value)
        assertEquals(line, WordByWordLyricsStorageOps.readWordByWordLyrics(context, "Song", "Artist", 10000).single())
    }

    @Test fun insertedWordAndConfirmedDraftSurviveRecreationAndSave() {
        row()!!.performClick()
        settle()
        button(R.string.ui_edit_line).performClick()
        await { inputs().size == 7 }
        button(R.string.ui_add_word).performClick()
        await { inputs().size == 10 }
        val added = inputs().single { it.hint.toString() == activity.getString(R.string.ui_lyrics_word_text, 3) }
        added.setText("!")
        assertTrue(button(R.string.ui_confirm_line).isEnabled)
        button(R.string.ui_confirm_line).performClick()
        await { draft().lines.single().text == "Hello world!" }
        val expected = draft()
        settle()
        controller.recreate()
        await { activity.graph.uiHost.editorSession.state.value != null && row() != null }
        assertEquals(expected, draft())
        button(R.string.ui_save_changes).performClick()
        await { activity.graph.uiHost.editorSession.state.value == null }
        val saved = WordByWordLyricsStorageOps.readWordByWordLyrics(context, "Song", "Artist", 10000).single()
        assertEquals(expected.lines.single(), saved)
        assertEquals("你好", saved.translatedText)
    }

    @Test fun wholeLineEditingRebuildsWordsAndSavesTheJoinedText() {
        val headings = descendants(dialog().window!!.decorView).filterIsInstance<TextView>()
        assertTrue(headings.any { it.text.toString() == "Song" })
        assertFalse(headings.any { it.text.toString().startsWith("Word-by-word lyrics") })
        row()!!.performClick()
        settle()
        button(R.string.ui_edit_line).performClick()
        await { inputs().size == 7 }
        val whole = inputs().single { it.contentDescription == activity.getString(R.string.ui_lyrics_line_text) }
        whole.setText("<00:01.001>Hello <00:02.111><00:02.444>新<00:02.900>世<00:03.300>界<00:03.789>")
        assertEquals(listOf("Hello ", "world"), wordInputs().map { it.text.toString() })
        assertTrue(wordInputs().all { !it.isEnabled })
        assertFalse(button(R.string.ui_add_word).isEnabled)
        assertFalse(button(R.string.ui_confirm_line).isEnabled)
        whole.setText("")
        assertFalse(button(R.string.ui_lyrics_update_words).isEnabled)
        whole.setText("<00:01.001>Hello <00:02.111><00:02.444>新<00:02.900>世<00:03.300>界<00:03.789>")
        assertTrue(button(R.string.ui_lyrics_update_words).isEnabled)
        button(R.string.ui_lyrics_update_words).performClick()
        assertEquals(listOf("Hello ", "新", "世", "界"), wordInputs().map { it.text.toString() })
        assertTrue(wordInputs().all { it.isEnabled })
        assertTrue(button(R.string.ui_add_word).isEnabled)
        assertFalse(button(R.string.ui_lyrics_update_words).isEnabled)
        assertEquals(line, draft().lines.single())
        assertTrue(button(R.string.ui_confirm_line).isEnabled)
        button(R.string.ui_confirm_line).performClick()
        await { draft().lines.single().text == "Hello 新世界" }
        val updated = draft().lines.single()
        assertEquals(line.segments.first(), updated.segments.first())
        assertEquals("你好", updated.translatedText)
        settle()
        button(R.string.ui_save_changes).performClick()
        await { activity.graph.uiHost.editorSession.state.value == null }
        assertEquals(updated, WordByWordLyricsStorageOps.readWordByWordLyrics(context, "Song", "Artist", 10000).single())
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wholeLineTimeChangesNeedConfirmationAndRefreshWordTimes() {
        val bottomSheet = dialog().findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        val originalHeight = bottomSheet.height
        row()!!.performClick()
        settle()
        button(R.string.ui_edit_line).performClick()
        await { inputs().size == 7 }
        assertFalse(inputs().any { it.contentDescription == activity.getString(R.string.ui_lyrics_line_start) ||
            it.contentDescription == activity.getString(R.string.ui_lyrics_line_end) })
        val whole = field(R.string.ui_lyrics_line_text)
        assertEquals(originalRaw, whole.text.toString())
        whole.setText("<00:05.001>Hello <00:06.111><00:06.444>world<00:07.789>")
        assertEquals("00:01.001", wordTime(1, R.string.ui_lyrics_word_start).text.toString())
        assertFalse(button(R.string.ui_confirm_line).isEnabled)
        capture("word-raw-line-pending-en")
        assertFullyVisible(button(R.string.ui_lyrics_update_words))
        button(R.string.ui_lyrics_update_words).performClick()
        capture("word-raw-line-updated-en")
        assertFullyVisible(wordInputs().first())
        assertEquals("00:05.001", wordTime(1, R.string.ui_lyrics_word_start).text.toString())
        assertEquals("00:06.111", wordTime(1, R.string.ui_lyrics_word_end).text.toString())
        assertEquals("00:06.444", wordTime(2, R.string.ui_lyrics_word_start).text.toString())
        assertTrue(wordInputs().all { it.isEnabled })
        button(R.string.ui_confirm_line).performClick()
        await { draft().lines.single().startMs == 5001L }
        val edited = draft().lines.single()
        assertEquals(line.copy(startMs = 5001, endMs = 7789,
            segments = line.segments.map { it.copy(startMs = it.startMs + 4000, endMs = it.endMs + 4000) }), edited)
        assertTrue(draft().invalidLines().isEmpty())
        settle()
        assertEquals(originalHeight, bottomSheet.height)
    }

    @Test
    @Config(qualifiers = "es-rES-w320dp-h800dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wholeLineActionsFitANarrowDarkEditor() {
        ThemeSettingsStore.setDark(context, true)
        try {
            controller.recreate()
            await { row() != null }
            row()!!.performClick()
            settle()
            button(R.string.ui_edit_line).performClick()
            await { inputs().size == 7 }
            field(R.string.ui_lyrics_line_text).setText("<00:01.001>Texto <00:02.444>nuevo<00:03.789>")
            capture("word-raw-line-es-dark")
            assertFullyVisible(button(R.string.ui_lyrics_update_words))
            assertFullyVisible(button(R.string.ui_lyrics_reset_line))
            button(R.string.ui_lyrics_update_words).performClick()
            assertTrue(wordInputs().all { it.isEnabled })
        } finally { ThemeSettingsStore.setDark(context, false) }
    }

    @Test fun resettingUnconfirmedWholeLineEditsPreservesIndividualWordChanges() {
        row()!!.performClick()
        settle()
        button(R.string.ui_edit_line).performClick()
        await { inputs().size == 7 }
        wordInputs().last().setText("there")
        wordTime(2, R.string.ui_lyrics_word_start).setText("00:02.500")
        val whole = field(R.string.ui_lyrics_line_text)
        val confirmed = "<00:01.001>Hello <00:02.111><00:02.500>there<00:03.789>"
        assertEquals(confirmed, whole.text.toString())
        whole.setText("<bad>Replacement")
        assertFalse(button(R.string.ui_lyrics_update_words).isEnabled)
        assertTrue(wordInputs().none { it.isEnabled })
        button(R.string.ui_lyrics_reset_line).performClick()
        assertEquals(confirmed, whole.text.toString())
        assertEquals("00:02.500", wordTime(2, R.string.ui_lyrics_word_start).text.toString())
        assertTrue(wordInputs().all { it.isEnabled })
        assertTrue(button(R.string.ui_confirm_line).isEnabled)
        button(R.string.ui_confirm_line).performClick()
        await { draft().lines.single().text == "Hello there" }
        assertEquals(2500L, draft().lines.single().segments.last().startMs)
    }

    private fun assertFullyVisible(view: View) {
        val bounds = Rect()
        assertTrue(view.getGlobalVisibleRect(bounds))
        assertEquals(view.width, bounds.width())
        assertEquals(view.height, bounds.height())
        val root = view.rootView
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        assertTrue(bounds.left >= location[0] && bounds.top >= location[1])
        assertTrue(bounds.right <= location[0] + root.width && bounds.bottom <= location[1] + root.height)
    }

    private fun capture(name: String) {
        settle()
        val root = dialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(System.getProperty("java.io.tmpdir"), "airlyrics-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun wordTime(index: Int, res: Int) = inputs().single {
        it.contentDescription == "${activity.getString(R.string.ui_lyrics_word_text, index)}, ${activity.getString(res)}"
    }
    private fun field(res: Int) = inputs().single { it.contentDescription == activity.getString(res) }
    private fun wordInputs() = inputs().filter { input ->
        (1..inputs().size).any { input.contentDescription == activity.getString(R.string.ui_lyrics_word_text, it) }
    }
    private fun dialog(): Dialog = requireNotNull(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing })
    private fun row(): View? = ShadowDialog.getLatestDialog()?.window?.decorView?.let { root ->
        descendants(root).filterIsInstance<RecyclerView>().singleOrNull()
            ?.findViewHolderForAdapterPosition(0)?.itemView?.let { item ->
                descendants(item).firstOrNull { it.isClickable && !it.contentDescription.isNullOrEmpty() }
            }
    }
    private fun inputs() = descendants(dialog().window!!.decorView).filterIsInstance<EditText>()
    private fun button(res: Int): TextView = descendants(dialog().window!!.decorView).filterIsInstance<TextView>()
        .first { it.text.toString() == activity.getString(res) && it.isShown }
    private fun draft() = WordLyricsDocument.parse(activity.graph.uiHost.editorSession.state.value!!.text)
    private fun settle() = ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
}
