package com.andsi.airlyrics.app

import android.widget.EditText
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.LyricsStorageTestBase
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

@RunWith(RobolectricTestRunner::class)
class LocalUntimedLyricsEditorTest : LyricsStorageTestBase() {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val original = "First paragraph\n\n第二段\n[00:01.00]Literal text"

    @Before fun open() {
        assertTrue(LyricsStorage.savePlainLyrics(context, "Song", "Artist", 10000, original, isUntimed = true))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val host = activity.graph.uiHost
        val item = host.savedLyricsState().lyrics.single()
        assertTrue(item.isUntimed)
        host.localLyricsRow(item).performClick()
        await { ShadowDialog.getLatestDialog() is BottomSheetDialog && inputOrNull() != null }
    }
    @After fun close() { controller.close(); ShadowDialog.reset() }

    @Test fun rawParagraphsAreEditableAndSurviveRecreationAndSave() {
        val edited = original + "\n\nNew paragraph"
        assertEquals(original, input().text.toString())
        input().setText(edited)
        assertEquals(edited, activity.graph.uiHost.editorSession.state.value!!.text)
        controller.recreate()
        await { inputOrNull()?.text?.toString() == edited }
        assertTrue(activity.graph.uiHost.editorSession.state.value!!.item.isUntimed)
        button(R.string.ui_save_changes).performClick()
        await { activity.graph.uiHost.editorSession.state.value == null }
        val item = LyricsStorage.listAllLyrics(context).single()
        assertTrue(item.isUntimed)
        assertEquals(edited, LyricsStorage.readLocalLyricsItemText(context, item, LyricsStorage.LocalLyricsEditTarget.PLAIN))
    }

    @Test fun cancelDoesNotChangeTheSavedText() {
        input().setText("Discarded\n\nDraft")
        button(R.string.ui_cancel).performClick()
        await { activity.graph.uiHost.editorSession.state.value == null }
        assertEquals(original, LyricsStorage.readPlainLyrics(context, "Song", "Artist", 10000))
    }

    @Test fun emptyTextCannotBeSaved() {
        input().setText(" \n ")
        assertFalse(button(R.string.ui_save_changes).isEnabled)
        input().setText("Restored")
        assertTrue(button(R.string.ui_save_changes).isEnabled)
    }

    private fun inputOrNull(): EditText? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
        ?.let { descendants(it).filterIsInstance<EditText>().singleOrNull() }
    private fun input() = requireNotNull(inputOrNull())
    private fun button(res: Int) = descendants(ShadowDialog.getLatestDialog().window!!.decorView)
        .filterIsInstance<TextView>().first { it.text.toString() == activity.getString(res) }
}
