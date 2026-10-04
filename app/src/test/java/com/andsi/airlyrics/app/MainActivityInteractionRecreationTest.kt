package com.andsi.airlyrics.app

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.DisplayScopeStore
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.state.FloatingPanelId
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class MainActivityInteractionRecreationTest {
    private lateinit var context: Context
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("floating_lyrics_style", PREFS_NAME, "floating_quick_control", "display_scope").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, FALLBACK_LYRICS_DIR).deleteRecursively()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        settle()
    }
    @After fun cleanup() { controller.close(); ShadowDialog.reset() }

    @Test fun panelUndoAndUnrelatedSettingsSurviveRecreation() {
        FloatingLyricsStyleStore.setTextSize(activity, 40f)
        floatingPage()
        descendants(activity.window.decorView).single { it.getTag(R.id.interaction_anchor) == "panel:TEXT_SIZE" }.performClick()
        settle()
        action(R.string.ui_reset).performClick()
        assertEquals(18f, FloatingLyricsStyleStore.getStyle(activity).textSizeSp)
        controller.recreate(); settle()
        assertEquals(FloatingPanelId.TEXT_SIZE, activity.graph.viewModel.interactions.panel)
        FloatingLyricsStyleStore.setTextColor(activity, 0xff123456.toInt())
        action(R.string.ui_undo).performClick()
        assertEquals(40f, FloatingLyricsStyleStore.getStyle(activity).textSizeSp)
        assertEquals(0xff123456.toInt(), FloatingLyricsStyleStore.getStyle(activity).textColor)
        action(R.string.ui_close).performClick(); settle()
        controller.recreate(); settle()
        assertNull(activity.graph.viewModel.interactions.panel)
        assertTrue(descendants(activity.window.decorView).none { it.contentDescription == activity.getString(R.string.ui_undo) })
    }

    @Test fun floatingPageScrollSurvivesRecreation() {
        floatingPage()
        val scroll = descendants(activity.window.decorView).filterIsInstance<ScrollView>().first()
        scroll.scrollTo(0, 240); settle()
        val oldY = scroll.scrollY
        assertTrue(oldY > 0)
        controller.recreate(); settle()
        val restored = descendants(activity.window.decorView).filterIsInstance<ScrollView>().first()
        assertEquals(oldY, restored.scrollY)
    }

    @Test fun importDialogAndGuideRestoreWithoutLaunchingPickerAgain() {
        activity.graph.lyricsWorkflow.showImportLyricsDialog(SongIdentity("Song", "Artist", "", 0), true, true)
        settle()
        button(latestDialog(), R.string.ui_lyrics_format_guide).performClick(); settle()
        button(latestDialog(), R.string.ui_lyrics_format_ttml_tab).performClick(); settle()
        val oldDialog = latestDialog()
        controller.recreate(); settle()
        assertFalse(oldDialog.isShowing)
        assertNotNull(activity.graph.viewModel.interactions.read("importChoices"))
        assertEquals("TTML", activity.graph.viewModel.interactions.read("formatGuide")?.getString("page"))
        assertTrue(button(latestDialog(), R.string.ui_lyrics_format_ttml_tab).isSelected)
        button(latestDialog(), R.string.ui_ok).performClick(); settle()
        assertNull(activity.graph.viewModel.interactions.read("formatGuide"))
    }

    @Test fun editingDraftAndSelectionRestoreAndCancelDoesNotSaveLyrics() {
        val editor = openEditor()
        editor.setText("[00:01.00]unsaved draft")
        editor.setSelection(4, 9)
        val oldDialog = latestDialog()
        controller.recreate(); settle()
        await { latestDialogOrNull()?.let { descendants(it.window!!.decorView).any { v -> v is EditText } } == true }
        val restored = descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single()
        assertFalse(oldDialog.isShowing)
        assertEquals("[00:01.00]unsaved draft", restored.text.toString())
        assertEquals(4, restored.selectionStart)
        assertEquals(9, restored.selectionEnd)
        button(latestDialog(), R.string.ui_cancel).performClick(); settle()
        assertNull(activity.graph.viewModel.interactions.read("editor"))
        assertTrue(LyricsStorage.readLocalLyricsItemText(activity, LyricsStorage.listAllLyrics(activity).single(), LyricsStorage.LocalLyricsEditTarget.PLAIN)!!.contains("original"))
    }

    @Test fun editorRestoresFromSavedInstanceStateIntoNewViewModel() {
        val editor = openEditor()
        editor.setText("[00:01.00]persistent draft")
        editor.setSelection(12)
        val oldModel = activity.graph.viewModel
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        await { activity.graph.uiHost.editorSession.state.value?.text == "[00:01.00]persistent draft" }
        settle()
        assertNotSame(oldModel, activity.graph.viewModel)
        val restored = descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals("[00:01.00]persistent draft", restored.text.toString())
        assertEquals(12, restored.selectionStart)
    }

    @Test fun rgbExpansionSurvivesRecreation() {
        floatingPage()
        descendants(activity.window.decorView).single { it.getTag(R.id.interaction_anchor) == "panel:TEXT_COLOR" }.performClick()
        settle()
        descendants(activity.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(R.string.ui_rgb_tune) }.performClick()
        controller.recreate(); settle()
        assertEquals(FloatingPanelId.TEXT_COLOR, activity.graph.viewModel.interactions.panel)
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == activity.getString(R.string.ui_hide_rgb) && it.isShown })
    }

    @Suppress("DEPRECATION")
    @Test fun appPickerDraftAndQuerySurviveRecreationUntilExplicitSave() {
        val packageName = "example.music"
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "MusicActivity"
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
            nonLocalizedLabel = "Music player"
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), info)
        activity.graph.displayScopeWorkflow.showAppPicker()
        await { latestDialogOrNull()?.let { dialog ->
            descendants(dialog.window!!.decorView).filterIsInstance<ListView>().any { it.count > 0 }
        } == true }
        button(latestDialog(), R.string.ui_select_all).performClick()
        descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single().setText("Music")
        assertTrue(DisplayScopeStore.selectedPackages(context).isEmpty())
        controller.recreate(); settle()
        await { descendants(latestDialog().window!!.decorView).filterIsInstance<ListView>().single().count == 1 }
        assertEquals("Music", descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single().text.toString())
        assertEquals(setOf(context.packageName, packageName), activity.graph.viewModel.interactions.read("appPicker")?.getStringArrayList("selected")?.toSet())
        assertTrue(DisplayScopeStore.selectedPackages(context).isEmpty())
        button(latestDialog(), R.string.ui_save).performClick(); settle()
        assertEquals(setOf(context.packageName, packageName), DisplayScopeStore.selectedPackages(context))
        assertNull(activity.graph.viewModel.interactions.read("appPicker"))
    }

    @Test fun canceledAppPickerDoesNotRestoreOrPersistSelection() {
        DisplayScopeStore.setSelectedPackages(context, setOf("previous.selection"))
        activity.graph.displayScopeWorkflow.showAppPicker(); settle()
        descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single().setText("draft query")
        button(latestDialog(), R.string.ui_cancel).performClick(); settle()
        controller.recreate(); settle()
        assertNull(activity.graph.viewModel.interactions.read("appPicker"))
        assertEquals(setOf("previous.selection"), DisplayScopeStore.selectedPackages(context))
    }

    private fun openEditor(): EditText {
        assertTrue(LyricsStorage.savePlainLyrics(context = context, title = "Song", artist = "Artist", duration = 180000L, album = "Album", plainLrc = "[00:01.00]original"))
        val host = activity.graph.uiHost
        host.localLyricsRow(host.savedLyricsState().lyrics.single()).performClick()
        await { latestDialogOrNull()?.let { descendants(it.window!!.decorView).any { v -> v is EditText } } == true }
        settle()
        return descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single()
    }
    private fun floatingPage() {
        activity.graph.viewModel.selectPage(Page.FLOATING)
        activity.graph.uiInvalidator.rebuildCurrentPage(false, false)
        settle()
    }
    private fun action(res: Int): View = descendants(activity.window.decorView).first { it.contentDescription == activity.getString(res) }
    private fun button(dialog: Dialog, res: Int): View = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString().lineSequence().first() == activity.getString(res) }
    private fun latestDialogOrNull(): Dialog? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
    private fun latestDialog(): Dialog = requireNotNull(latestDialogOrNull())
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun settle() { ShadowLooper.idleMainLooper(350, java.util.concurrent.TimeUnit.MILLISECONDS) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertTrue("Timed out waiting for background operation", condition())
    }
}
