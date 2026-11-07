package com.andsi.airlyrics.app

import android.app.Dialog
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LocalWordLyricsEditorTest : LyricsStorageTestBase() {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val line = WordByWordLine(1001, 3789, "Hello world", listOf(
        WordByWordSegment("Hello ", 1001, 2111), WordByWordSegment("world", 2444, 3789)
    ), translatedText = "你好")

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
        await { inputs().size == 9 }
        assertEquals("Hello world", inputs().first().text.toString())
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
        await { inputs().size == 9 }
        button(R.string.ui_add_word).performClick()
        await { inputs().size == 12 }
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
        await { inputs().size == 9 }
        val whole = inputs().single { it.contentDescription == activity.getString(R.string.ui_lyrics_line_text) }
        whole.setText("Hello 新世界")
        val words = inputs().filter { it.contentDescription.toString().startsWith("Word ") &&
            it.contentDescription.toString().substringAfter("Word ").all(Char::isDigit) }
        assertEquals(listOf("Hello ", "新", "世", "界"), words.map { it.text.toString() })
        whole.setText("")
        assertFalse(button(R.string.ui_confirm_line).isEnabled)
        whole.setText("Hello 新世界")
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
