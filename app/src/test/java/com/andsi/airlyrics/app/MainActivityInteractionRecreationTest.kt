package com.andsi.airlyrics.app

import com.andsi.airlyrics.testutil.seedPlainLyrics
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.testutil.awaitUiCondition as await
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ListView
import android.widget.TextView
import com.andsi.airlyrics.ui.components.LocalLyricsRowView
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.recyclerview.widget.RecyclerView
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
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        action(R.string.ui_reset_to_default).performClick()
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
        activity.graph.lyricsWorkflow.showImportLyricsDialog(
            SongIdentity("Song", "Artist", "", 0),
            plainImportEnabled = true,
            wordByWordImportEnabled = true
        )
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

    @Test fun editingDraftRestoresAndCancelDoesNotSaveLyrics() {
        openEditor()
        editFirstLine("unsaved draft")
        assertEquals("[00:01.00]unsaved draft", activity.graph.uiHost.editorSession.state.value?.text)
        val oldDialog = latestDialog()
        controller.recreate(); settle()
        await { editorRowOrNull()?.contentDescription == "[00:01.00], unsaved draft" }
        assertFalse(oldDialog.isShowing)
        assertEquals("[00:01.00]unsaved draft", activity.graph.uiHost.editorSession.state.value?.text)
        assertEquals("[00:01.00], unsaved draft", editorRow().contentDescription)
        button(latestDialog(), R.string.ui_cancel).performClick(); settle()
        assertNull(activity.graph.viewModel.interactions.read("editor"))
        assertTrue(LyricsStorage.readLocalLyricsItemText(activity, LyricsStorage.listAllLyrics(activity).single(), LyricsStorage.LocalLyricsEditTarget.PLAIN)!!.contains("original"))
    }

    @Config(qualifiers = "en-rUS-w1240dp-h775dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun recentLyricsEditorRestoresDraftAndReturnsToTheSameSongAfterResize() {
        val overview = openLyricsSettingsWithSongs(8)
        val row = descendants(overview).filterIsInstance<LocalLyricsRowView>()[2]
        val key = row.getTag(R.id.interaction_anchor)
        val scroll = generateSequence(row.parent as View) { it.parent as? View }
            .filterIsInstance<ScrollView>().first()
        scroll.scrollTo(0, scroll.scrollY + offsetWithin(row, scroll) + 4)
        val offset = offsetWithin(row, scroll)
        assertTrue(scroll.scrollY > 0)
        assertTrue(row.performClick())
        await { editorRowOrNull() != null }
        editFirstLine("unsaved draft")
        val sheet = latestDialog().findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        assertTrue("The editor can use both columns on a tablet", sheet.width > 640)

        RuntimeEnvironment.setQualifiers("en-rUS-w550dp-h720dp-port-mdpi")
        controller.recreate()
        settle()
        await { editorRowOrNull()?.contentDescription == "[00:01.00], unsaved draft" }
        assertEquals(SettingsSubPage.LYRICS, activity.graph.state.settingsSubPage)
        assertTrue(button(latestDialog(), R.string.ui_cancel).performClick())
        settle()

        val restored = requireNotNull(activity.graph.uiHost.contentContainer)
        await { descendants(restored).any { it.getTag(R.id.interaction_anchor) == key } }
        val restoredRow = descendants(restored).single { it.getTag(R.id.interaction_anchor) == key }
        val restoredScroll = descendants(restored).filterIsInstance<ScrollView>().single()
        assertEquals(offset, offsetWithin(restoredRow, restoredScroll))
        assertNull(activity.graph.uiHost.editorSession.state.value)
        assertTrue(LyricsStorage.listAllLyrics(activity).all { item ->
            LyricsStorage.readLocalLyricsItemText(activity, item, LyricsStorage.LocalLyricsEditTarget.PLAIN)
                ?.contains("original") == true
        })
    }

    @Config(qualifiers = "en-rUS-w550dp-h720dp-port-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun savedLyricsEditorClosesBackToTheSameListPosition() {
        val overview = openLyricsSettingsWithSongs(12)
        assertTrue(visibleAction(overview, R.string.ui_view_all).performClick())
        settle()
        val content = requireNotNull(activity.graph.uiHost.contentContainer)
        await { descendants(content).filterIsInstance<ListView>().any { it.count == 12 && it.childCount > 0 } }
        val list = descendants(content).filterIsInstance<ListView>().single()
        list.setSelectionFromTop(3, 18)
        settle()
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0).top
        assertTrue(first > 0)

        assertTrue(descendants(list.getChildAt(0)).filterIsInstance<LocalLyricsRowView>().single().performClick())
        await { editorRowOrNull() != null }
        assertTrue(button(latestDialog(), R.string.ui_cancel).performClick())
        settle()

        assertEquals(SettingsSubPage.SAVED_LYRICS, activity.graph.state.settingsSubPage)
        assertEquals(first, list.firstVisiblePosition)
        assertEquals(top, list.getChildAt(0).top)
    }

    private fun openLyricsSettingsWithSongs(count: Int): View {
        repeat(count) { index ->
            assertTrue(seedPlainLyrics(
                context = context,
                title = "Song ${index + 1}",
                artist = "Artist",
                duration = 180_000L,
                plainLrc = "[00:01.00]original"
            ))
        }
        val graph = activity.graph
        val host = graph.uiHost
        assertTrue((host.tabViews.getValue(Page.SETTINGS).parent as View).performClick())
        settle()
        graph.mainHandRenderer.finishPageTransition()
        val content = requireNotNull(host.contentContainer)
        assertTrue(visibleAction(content, R.string.ui_lyrics).performClick())
        settle()
        graph.mainHandRenderer.finishPageTransition()
        val overview = content.getChildAt(0)
        await { descendants(overview).filterIsInstance<LocalLyricsRowView>().size >= 8 }
        return overview
    }

    private fun visibleAction(root: View, res: Int): View {
        val label = descendants(root).filterIsInstance<TextView>()
            .single { it.isShown && it.text.toString() == activity.getString(res) }
        return generateSequence(label as View) { it.parent as? View }.first(View::isClickable)
    }

    private fun offsetWithin(view: View, parent: View): Int =
        IntArray(2).also(view::getLocationInWindow)[1] - IntArray(2).also(parent::getLocationInWindow)[1]

    @Test fun rowActionsFillTheSameWidthForShortLongAndEmptyLyrics() {
        openEditor("[00:01.00]x\n[00:02.00]A normal lyric line\n[00:03.00]A longer lyric that wraps across several display lines and keeps going\n[00:04.00]\n[00:05.00]♪ music ♪")
        val list = descendants(latestDialog().window!!.decorView).filterIsInstance<RecyclerView>().single()
        assertTrue(list.width > 0)
        val expectedWidth = list.width - list.paddingLeft - list.paddingRight

        for (index in 0..4) {
            list.scrollToPosition(index)
            settle()
            val holder = requireNotNull(list.findViewHolderForAdapterPosition(index))
            val row = descendants(holder.itemView).first { it.isClickable && !it.contentDescription.isNullOrEmpty() }
            assertTrue(row.performClick())
            settle()
            val actionButtons = descendants(holder.itemView).filterIsInstance<TextView>()
                .filter { it.text.toString() in listOf(
                    activity.getString(R.string.ui_edit_line),
                    activity.getString(R.string.ui_delete),
                    activity.getString(R.string.ui_add_next_line)
                ) }
            assertEquals(3, actionButtons.size)
            val actions = actionButtons.first().parent as LinearLayout
            assertEquals(expectedWidth, holder.itemView.width)
            assertEquals(expectedWidth - row.paddingLeft - row.paddingRight, actions.width)
            assertTrue(actionButtons.last().width > actionButtons.first().width)
            assertEquals(actionButtons[0].height, actionButtons[1].height)
            assertEquals(actionButtons[1].height, actionButtons[2].height)
        }
    }

    @Test fun deletingASelectedRowStaysInDraftUntilSaveAndCancelRestoresIt() {
        val original = "[00:01.00]first\n[00:02.00]\n[00:03.00]♪ music ♪"
        val afterDelete = "[00:01.00]first\n[00:03.00]♪ music ♪"
        openEditor(original)
        deleteSecondEditorRow()
        assertEquals(afterDelete, activity.graph.uiHost.editorSession.state.value?.text)
        assertEquals(original, storedLyrics())
        assertEquals(2, editorList().adapter?.itemCount)
        assertEquals("[00:03.00], ♪ music ♪", editorRow(1).contentDescription)
        assertFalse(button(latestDialog(), R.string.ui_edit_line).isShown)

        button(latestDialog(), R.string.ui_cancel).performClick()
        settle()
        assertEquals(original, storedLyrics())

        openEditor(original)
        deleteSecondEditorRow()
        button(latestDialog(), R.string.ui_save_changes).performClick()
        await { storedLyrics() == afterDelete }
        assertEquals(afterDelete, storedLyrics())
    }

    @Test fun editingAddingAndDeletingRowsUseTheSameDraft() {
        openEditor("[00:01.00]first\n[00:03.00]last")
        editorRow(0).performClick()
        settle()
        button(latestDialog(), R.string.ui_edit_line).performClick()
        await { descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().size == 2 }
        descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == activity.getString(R.string.ui_lyrics) }.setText("edited")
        button(latestDialog(), R.string.ui_confirm_line).performClick()
        await { activity.graph.uiHost.editorSession.state.value?.text == "[00:01.00]edited\n[00:03.00]last" }
        settle()

        editorRow(0).performClick()
        settle()
        button(latestDialog(), R.string.ui_add_next_line).performClick()
        await { descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().size == 2 }
        descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == activity.getString(R.string.ui_lyrics_time_hint) }.setText("00:02.00")
        descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == activity.getString(R.string.ui_lyrics) }.setText("middle")
        button(latestDialog(), R.string.ui_confirm_line).performClick()
        await { activity.graph.uiHost.editorSession.state.value?.text == "[00:01.00]edited\n[00:02.00]middle\n[00:03.00]last" }
        settle()

        deleteSecondEditorRow()
        assertEquals("[00:01.00]edited\n[00:03.00]last", activity.graph.uiHost.editorSession.state.value?.text)
        assertEquals(2, editorList().adapter?.itemCount)
    }

    @Test fun editorRestoresFromSavedInstanceStateIntoNewViewModel() {
        openEditor()
        editFirstLine("persistent draft")
        val draftId = activity.graph.uiHost.editorSession.state.value!!.id
        val oldModel = activity.graph.viewModel
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        await { activity.graph.uiHost.editorSession.state.value?.text == "[00:01.00]persistent draft" }
        await { editorRowOrNull()?.contentDescription == "[00:01.00], persistent draft" }
        settle()
        assertNotSame(oldModel, activity.graph.viewModel)
        assertEquals(draftId, activity.graph.uiHost.editorSession.state.value?.id)
        assertEquals("[00:01.00], persistent draft", editorRow().contentDescription)
    }

    @Test fun hexInputDraftRestoresAndAppliesToTheCurrentColorPanel() {
        floatingPage()
        descendants(activity.window.decorView).single {
            it.getTag(R.id.interaction_anchor) == "panel:TEXT_COLOR"
        }.performClick()
        settle()
        descendants(activity.window.decorView).single {
            it.getTag(R.id.interaction_anchor) == "floating.color.custom"
        }.performClick()
        settle()
        descendants(activity.window.decorView).single {
            it.getTag(R.id.interaction_anchor) == "floating.color.hex"
        }.performClick()
        settle()
        descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single().setText("#12")
        val before = FloatingLyricsStyleStore.getStyle(activity)
        controller.recreate()
        settle()
        assertEquals(FloatingPanelId.TEXT_COLOR, activity.graph.viewModel.interactions.panel)
        val input = descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals("#12", input.text.toString())
        input.setText("#a85350")
        button(latestDialog(), R.string.ui_apply).performClick()
        settle()
        val after = FloatingLyricsStyleStore.getStyle(activity)
        assertEquals(0xA85350, after.textColor and 0xFFFFFF)
        assertEquals(android.graphics.Color.alpha(before.textColor), android.graphics.Color.alpha(after.textColor))
        assertEquals(before.wordByWordHighlightColor, after.wordByWordHighlightColor)
        assertNull(activity.graph.viewModel.interactions.read("panel.color.hex"))
    }

    @Test fun rgbEditorSurvivesRecreationAndBackReturnsToEachColorPanel() {
        floatingPage()
        listOf(FloatingPanelId.TEXT_COLOR, FloatingPanelId.HIGHLIGHT_COLOR,
            FloatingPanelId.BACKGROUND, FloatingPanelId.SHADOW).forEach { id ->
            descendants(activity.window.decorView).single {
                it.getTag(R.id.interaction_anchor) == "panel:${id.name}"
            }.performClick()
            settle()
            descendants(activity.window.decorView).first {
                it.getTag(R.id.interaction_anchor) == "floating.color.custom" && it.isClickable
            }.performClick()
            controller.recreate(); settle()
            assertEquals(id, activity.graph.viewModel.interactions.panel)
            assertTrue(action(R.string.ui_back).isShown)
            assertEquals(3, descendants(activity.window.decorView).filterIsInstance<android.widget.SeekBar>()
                .count { it.isShown && it.getTag(R.id.interaction_anchor)?.toString()?.startsWith("floating.color.rgb.") == true })
            activity.onBackPressedDispatcher.onBackPressed()
            settle()
            assertEquals(id, activity.graph.viewModel.interactions.panel)
            assertTrue(descendants(activity.window.decorView).any {
                it.getTag(R.id.interaction_anchor) == "floating.color.custom" && it.isShown && it.isClickable
            })
            activity.onBackPressedDispatcher.onBackPressed()
            settle()
            assertNull(activity.graph.viewModel.interactions.panel)
        }
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

    private fun openEditor(plainLrc: String = "[00:01.00]original") {
        assertTrue(seedPlainLyrics(context = context, title = "Song", artist = "Artist", duration = 180000L, album = "Album", plainLrc = plainLrc))
        val host = activity.graph.uiHost
        host.localLyricsRow(host.savedLyricsState().lyrics.single()).performClick()
        await { latestDialogOrNull() is BottomSheetDialog && editorRowOrNull() != null }
        settle()
    }
    private fun editFirstLine(lyric: String) {
        assertTrue(editorRow().performClick()); settle()
        val editLine = button(latestDialog(), R.string.ui_edit_line)
        assertTrue(editLine.isShown)
        editLine.performClick()
        await { descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>().size == 2 }
        descendants(latestDialog().window!!.decorView).filterIsInstance<EditText>()
            .single { it.contentDescription == activity.getString(R.string.ui_lyrics) }
            .setText(lyric)
        button(latestDialog(), R.string.ui_confirm_line).performClick()
        await { activity.graph.uiHost.editorSession.state.value?.text == "[00:01.00]$lyric" }
        settle()
    }
    private fun editorRowOrNull(index: Int = 0): View? = latestDialogOrNull()?.window?.decorView
        ?.let { descendants(it).filterIsInstance<RecyclerView>().singleOrNull() }
        ?.findViewHolderForAdapterPosition(index)?.itemView
        ?.let { itemView ->
            descendants(itemView).firstOrNull { row ->
                row !== itemView && row.isClickable && !row.contentDescription.isNullOrEmpty()
            }
        }
    private fun editorRow(index: Int = 0): View = requireNotNull(editorRowOrNull(index))
    private fun editorList(): RecyclerView = descendants(latestDialog().window!!.decorView)
        .filterIsInstance<RecyclerView>().single()
    private fun deleteSecondEditorRow() {
        editorRow(1).performClick()
        settle()
        val holder = requireNotNull(editorList().findViewHolderForAdapterPosition(1))
        descendants(holder.itemView).filterIsInstance<TextView>()
            .single { it.text.toString() == activity.getString(R.string.ui_delete) }.performClick()
        settle()
    }
    private fun storedLyrics(): String? = LyricsStorage.readLocalLyricsItemText(
        activity, LyricsStorage.listAllLyrics(activity).single(), LyricsStorage.LocalLyricsEditTarget.PLAIN
    )
    private fun floatingPage() {
        activity.graph.viewModel.selectPage(Page.FLOATING)
        activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
        settle()
    }
    private fun action(res: Int): View = descendants(activity.window.decorView).first {
        when (res) {
            R.string.ui_reset_to_default -> it.getTag(R.id.interaction_anchor) == "floating.panel.reset"
            R.string.ui_undo -> it.getTag(R.id.interaction_anchor) == "floating.panel.undo"
            else -> it.contentDescription == activity.getString(res)
        }
    }
    private fun button(dialog: Dialog, res: Int): View = descendants(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString().lineSequence().first() == activity.getString(res) }
    private fun latestDialogOrNull(): Dialog? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
    private fun latestDialog(): Dialog = requireNotNull(latestDialogOrNull())
    private fun settle() { ShadowLooper.idleMainLooper(350, java.util.concurrent.TimeUnit.MILLISECONDS) }
}
