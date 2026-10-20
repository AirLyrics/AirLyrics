package com.andsi.airlyrics.app

import android.content.Context
import android.animation.ValueAnimator
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.interaction.BatchLyricsProblem
import com.andsi.airlyrics.app.interaction.BatchLyricsStage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.testutil.awaitUiCondition
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.testutil.resetLyricsStorage
import com.andsi.airlyrics.ui.pages.settings.SavedLyricsSelectionIconView
import com.andsi.airlyrics.ui.pages.settings.SavedLyricsSelectionPanel
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import java.io.File
import java.util.concurrent.TimeUnit
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class MainActivityBatchLyricsTest {
    private lateinit var context: Context
    private var controller: ActivityController<MainActivity>? = null

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        resetLyricsStorage(context)
        for (title in listOf("One", "Two")) {
            assertTrue(LyricsStorage.savePlainLyrics(context, title, "Artist", 180_000, "[00:01.00]old $title"))
        }
    }

    @After fun cleanup() {
        ShadowChoreographer.setPaused(false)
        controller?.close()
        resetLyricsStorage(context)
    }

    @Test fun rowBorderFitsInsideWrapperBeforeAndAfterSelecting() {
        val activity = launch()
        fun assertFits() {
            rows(activity).forEach { row ->
                val wrapper = row.parent as View
                wrapper.measure(
                    View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.density.times(280).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                wrapper.layout(0, 0, wrapper.measuredWidth, wrapper.measuredHeight)
                assertTrue("Card bottom ${row.bottom} exceeds wrapper ${wrapper.height}", row.bottom <= wrapper.height)
                assertTrue(row.top >= 0)
            }
        }
        assertFits()
        descendants(activity.window.decorView).filterIsInstance<SavedLyricsSelectionIconView>().single().performClick()
        rows(activity).first().performClick()
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        assertFits()
    }

    @Test fun selectionButtonSurvivesRecreationAndBackExitsSelection() {
        val activity = launch()
        assertFalse(rows(activity).first().isLongClickable)
        descendants(activity.window.decorView).single {
            it.contentDescription == activity.getString(R.string.ui_multi_select)
        }.performClick()
        assertEquals(0, checkboxes(activity).count { it.isChecked })
        val originalRow = rows(activity).first()
        originalRow.performClick()
        assertSame(originalRow, rows(activity).first())
        assertEquals(1, checkboxes(activity).count { it.isChecked })
        assertEquals(2, checkboxes(activity).size)
        controller!!.recreate()
        val recreated = controller!!.get()
        // A recycled list creates only the rows that fit beside the restored action panel.
        awaitUiCondition { checkboxes(recreated).isNotEmpty() }
        assertEquals(1, recreated.graph.uiHost.interactions.read("savedLyricsSelection")
            ?.getStringArrayList("keys")?.size)
        assertEquals(1, checkboxes(recreated).count { it.isChecked })
        assertTrue(recreated.graph.handleBackNavigation())
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        assertTrue(checkboxes(recreated).isEmpty())
        assertEquals(SettingsSubPage.SAVED_LYRICS, recreated.graph.state.settingsSubPage)
    }

    @Test fun searchSelectAllAndSelectionButtonExit() {
        val activity = launch()
        fun clickAction(label: Int) {
            descendants(activity.window.decorView).single {
                it.contentDescription == activity.getString(label)
            }.performClick()
        }
        clickAction(R.string.ui_multi_select)
        clickAction(R.string.ui_search)
        descendants(activity.window.decorView).filterIsInstance<EditText>().single().setText("One")
        // Select all must use the current query even before the delayed filter runs.
        descendants(activity.window.decorView).filterIsInstance<TextView>().single {
            it.text == activity.getString(R.string.ui_select_all)
        }.performClick()
        assertEquals(1, checkboxes(activity).count { it.isChecked })
        val selectedKey = activity.graph.uiHost.interactions.read("savedLyricsSelection")
            ?.getStringArrayList("keys")?.single()
        assertEquals(activity.graph.uiHost.savedLyricsState().lyrics.single { it.title == "One" }.indexKey, selectedKey)
        fun selectionButton(label: Int) = descendants(activity.window.decorView)
            .filterIsInstance<TextView>().single { it.text == activity.getString(label) }
        selectionButton(R.string.ui_batch_deselect_all).performClick()
        assertEquals(0, checkboxes(activity).count { it.isChecked })
        selectionButton(R.string.ui_select_all).performClick()
        // Deselecting another search result must preserve the hidden selection.
        descendants(activity.window.decorView).filterIsInstance<EditText>().single().setText("Two")
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        selectionButton(R.string.ui_select_all).performClick()
        selectionButton(R.string.ui_batch_deselect_all).performClick()
        assertEquals(listOf(selectedKey), activity.graph.uiHost.interactions.read("savedLyricsSelection")
            ?.getStringArrayList("keys"))
        val replace = selectionButton(R.string.ui_batch_replace)
        val export = selectionButton(R.string.ui_batch_export)
        val delete = selectionButton(R.string.ui_delete)
        assertSame(replace.parent, export.parent)
        assertSame(replace.parent, delete.parent)
        assertTrue(selectionButton(R.string.ui_cancel).background != null)
        clickAction(R.string.ui_exit_multi_select)
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        assertTrue(checkboxes(activity).isEmpty())
        clickAction(R.string.ui_multi_select)
        assertEquals(0, checkboxes(activity).count { it.isChecked })
        val checkbox = checkboxes(activity).first()
        checkbox.performClick()
        assertTrue(checkbox.isChecked)
        checkbox.performClick()
        assertFalse(checkbox.isChecked)
    }

    @Test fun selectionAnimationReversesWithoutReplacingRowsOrControls() {
        val activity = launch()
        val icon = descendants(activity.window.decorView).filterIsInstance<SavedLyricsSelectionIconView>().single()
        val panel = descendants(activity.window.decorView).filterIsInstance<SavedLyricsSelectionPanel>().single()
        val row = rows(activity).first()
        val controls = descendants(panel).filterIsInstance<TextView>()
        icon.performClick()
        ShadowLooper.idleMainLooper(80L, TimeUnit.MILLISECONDS)
        assertEquals(icon.progress, panel.progress, 0.001f)
        icon.performClick()
        ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        icon.performClick()
        row.performClick()
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        assertEquals(1f, icon.progress, 0.001f)
        assertEquals(1f, panel.progress, 0.001f)
        assertSame(row, rows(activity).first())
        assertEquals(controls, descendants(panel).filterIsInstance<TextView>())
        assertTrue(row.isSelected)
        assertEquals(1, checkboxes(activity).count { it.isChecked })
        assertTrue(activity.graph.handleBackNavigation())
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        assertEquals(0f, icon.progress, 0.001f)
        assertEquals(View.GONE, panel.visibility)
        assertTrue(checkboxes(activity).isEmpty())
        assertSame(row, rows(activity).first())
        row.performClick()
        awaitUiCondition { ShadowDialog.getLatestDialog()?.isShowing == true }
    }

    @Test fun selectionSettlesImmediatelyWhenAnimationsAreDisabled() {
        val activity = launch()
        val icon = descendants(activity.window.decorView).filterIsInstance<SavedLyricsSelectionIconView>().single()
        val panel = descendants(activity.window.decorView).filterIsInstance<SavedLyricsSelectionPanel>().single()
        val oldScale = ValueAnimator.getDurationScale()
        try {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType, 0f))
            icon.performClick()
            assertEquals(1f, icon.progress, 0.001f)
            assertEquals(View.VISIBLE, panel.visibility)
            icon.performClick()
            assertEquals(0f, icon.progress, 0.001f)
            assertEquals(View.GONE, panel.visibility)
            assertTrue(checkboxes(activity).isEmpty())
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale", ClassParameter.from(Float::class.javaPrimitiveType, oldScale))
        }
    }

    @Test fun exportPickerSurvivesRecreationAndKeepsSelection() {
        val activity = launch()
        descendants(activity.window.decorView).filterIsInstance<SavedLyricsSelectionIconView>().single().performClick()
        rows(activity).first().performClick()
        descendants(activity.window.decorView).filterIsInstance<TextView>().single {
            it.text == activity.getString(R.string.ui_batch_export)
        }.performClick()
        val intent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/zip", intent.type)
        controller!!.recreate()
        val session = controller!!.get().graph.uiHost.batchLyricsSession
        assertEquals(BatchLyricsStage.EXPORT_PICKING, session.state.value?.stage)
        val destination = File(context.cacheDir, "selected.zip")
        try {
            session.exportDestinationSelected(Uri.fromFile(destination))
            session.exportDestinationSelected(Uri.fromFile(destination))
            awaitUiCondition { session.state.value?.stage == BatchLyricsStage.FINISHED }
            assertEquals(1, session.state.value!!.completed)
            assertFalse(session.state.value!!.exportFailed)
            java.util.zip.ZipFile(destination).use { assertEquals(1, it.size()) }
            assertNotNull(controller!!.get().graph.uiHost.interactions.read("savedLyricsSelection"))
            assertEquals(2, LyricsStorage.listAllLyrics(context).size)
        } finally { destination.delete() }
    }

    @Test fun cancellingExportPickerKeepsLyricsAndSelection() {
        val activity = launch()
        descendants(activity.window.decorView).filterIsInstance<SavedLyricsSelectionIconView>().single().performClick()
        rows(activity).first().performClick()
        val session = activity.graph.uiHost.batchLyricsSession
        assertTrue(session.beginExport(activity.graph.uiHost.savedLyricsState().lyrics))
        session.exportDestinationSelected(null)
        assertNull(session.state.value)
        assertNotNull(activity.graph.uiHost.interactions.read("savedLyricsSelection"))
        assertEquals(2, LyricsStorage.listAllLyrics(context).size)
    }

    @Test fun deleteAfterRecreationOnlyDeletesTheSelectedEntry() {
        val activity = launch()
        val selected = activity.graph.uiHost.savedLyricsState().lyrics.single { it.title == "One" }
        activity.graph.uiHost.manageSavedLyrics(listOf(selected), delete = true)
        controller!!.recreate()
        val recreated = controller!!.get()
        val session = recreated.graph.uiHost.batchLyricsSession
        assertEquals(BatchLyricsStage.DELETE, session.state.value?.stage)
        clickDialogButton(recreated.getString(R.string.ui_delete))
        session.confirm() // Repeated taps must not start a second operation.
        awaitUiCondition { session.state.value?.stage == BatchLyricsStage.FINISHED }
        assertEquals(1, session.state.value!!.completed)
        assertEquals(listOf("Two"), LyricsStorage.listAllLyrics(context).map { it.title })
    }

    @Test fun previewSurvivesRotationAndChangedSourceIsSkippedAtCommit() {
        val activity = launch()
        val selected = activity.graph.uiHost.savedLyricsState().lyrics
        val session = activity.graph.uiHost.batchLyricsSession
        assertTrue(session.begin(selected, delete = false))
        val one = File(context.cacheDir, "One.lrc").apply { writeText("[00:01.00]<00:01.00>new One") }
        val two = File(context.cacheDir, "Two.lrc").apply { writeText("[00:01.00]new Two") }
        session.filesSelected(listOf(Uri.fromFile(two), Uri.fromFile(one)))
        awaitUiCondition { session.state.value?.stage == BatchLyricsStage.PREVIEW }
        assertEquals(listOf("Two", "One"), session.state.value!!.rows.map { it.target?.title })
        controller!!.recreate()
        val retained = controller!!.get().graph.uiHost.batchLyricsSession
        assertSame(session, retained)
        assertEquals(BatchLyricsStage.PREVIEW, retained.state.value?.stage)
        two.writeText("[00:01.00]changed after preview")
        clickDialogButton(controller!!.get().getString(R.string.ui_batch_replace))
        awaitUiCondition { retained.state.value?.stage == BatchLyricsStage.FINISHED }
        assertEquals(1, retained.state.value!!.completed)
        assertEquals(BatchLyricsProblem.CHANGED, retained.state.value!!.failures.single().problem)
        assertTrue(LyricsStorage.hasWordByWordLyrics(context, "One", "Artist", 180_000))
        assertEquals("[00:01.00]old Two", LyricsStorage.readPlainLyrics(context, "Two", "Artist", 180_000))
        awaitUiCondition { dialogTexts().any { it.text == controller!!.get().getString(R.string.ui_batch_result) } }
    }

    @Test fun backingOutOfImportPreviewLeavesNoLoadingWindow() {
        val activity = launch()
        ShadowChoreographer.setPaused(true)
        val session = activity.graph.uiHost.batchLyricsSession
        assertTrue(session.begin(activity.graph.uiHost.savedLyricsState().lyrics, delete = false))
        val source = File(context.cacheDir, "One.lrc").apply { writeText("[00:01.00]new One") }
        session.filesSelected(listOf(Uri.fromFile(source)))
        awaitUiCondition { dialogTexts().any { it.text == activity.getString(R.string.ui_batch_replace) } }
        val preview = ShadowDialog.getLatestDialog()
        // Dialog.cancel() is the platform path used by back and outside-touch cancellation.
        preview.cancel()
        awaitUiCondition { session.state.value == null }
        ShadowLooper.idleMainLooper()
        assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing })
        assertEquals("[00:01.00]old One", LyricsStorage.readPlainLyrics(context, "One", "Artist", 180_000))
        assertTrue(session.begin(activity.graph.uiHost.savedLyricsState().lyrics, delete = false))
    }

    @Test fun partialImportClosesEveryLoadingWindowAndResultCanBeDismissed() {
        val activity = launch()
        ShadowChoreographer.setPaused(true)
        val session = activity.graph.uiHost.batchLyricsSession
        assertTrue(session.begin(activity.graph.uiHost.savedLyricsState().lyrics, delete = false))
        val one = File(context.cacheDir, "One.lrc").apply { writeText("[ti:One]\n[00:01.00]new One") }
        val two = File(context.cacheDir, "Two.lrc").apply { writeText("[ti:Wrong title]\n[00:01.00]new Two") }
        session.filesSelected(listOf(Uri.fromFile(one), Uri.fromFile(two)))
        awaitUiCondition { session.state.value?.stage == BatchLyricsStage.PREVIEW }
        assertEquals(1, session.state.value!!.rows.count { it.problem == BatchLyricsProblem.NO_MATCH })
        clickDialogButton(activity.getString(R.string.ui_batch_replace))
        awaitUiCondition { dialogTexts().any { it.text == activity.getString(R.string.ui_batch_result) } }
        assertEquals(1, session.state.value!!.completed)
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
        clickDialogButton(activity.getString(R.string.ui_ok))
        awaitUiCondition { session.state.value == null }
        ShadowLooper.idleMainLooper()
        assertTrue(ShadowDialog.getShownDialogs().none { it.isShowing })
        assertTrue(LyricsStorage.readPlainLyrics(context, "One", "Artist", 180_000)!!.contains("new One"))
        assertEquals("[00:01.00]old Two", LyricsStorage.readPlainLyrics(context, "Two", "Artist", 180_000))
    }

    @Test fun changedStorageDirectoryPreventsDeletion() {
        val activity = launch()
        val session = activity.graph.uiHost.batchLyricsSession
        assertTrue(session.begin(activity.graph.uiHost.savedLyricsState().lyrics, delete = true))
        LyricsStorage.saveLyricsDirUri(context, Uri.parse("content://unavailable/tree/changed"))
        session.confirm()
        awaitUiCondition { session.state.value?.stage == BatchLyricsStage.FINISHED }
        assertEquals(0, session.state.value!!.completed)
        assertEquals(2, session.state.value!!.failures.size)
        context.getSharedPreferences("lyrics_storage", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(2, LyricsStorage.listAllLyrics(context).size)
    }

    @Test fun recycledListRestoresScrollAndSelectAllIncludesOffscreenSearchResults() {
        repeat(60) { index ->
            assertTrue(LyricsStorage.savePlainLyrics(context, "Extra $index", "Artist", 180_000, "[00:01.00]test"))
        }
        val activity = launch()
        val list = descendants(activity.window.decorView).filterIsInstance<android.widget.ListView>().single()
        assertEquals(62, list.count)
        assertTrue(list.childCount < list.count)
        list.setSelection(35)
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        activity.graph.uiHost.interactionUi.capture()
        val anchor = activity.graph.uiHost.interactions.read("savedLyrics.list")!!.getString("key")
        assertNotNull(anchor)
        controller!!.recreate()
        val recreated = controller!!.get()
        awaitUiCondition {
            descendants(recreated.window.decorView).filterIsInstance<android.widget.ListView>().singleOrNull()?.count == 62
        }
        ShadowLooper.idleMainLooper(400L, TimeUnit.MILLISECONDS)
        recreated.graph.uiHost.interactionUi.capture()
        assertEquals(anchor, recreated.graph.uiHost.interactions.read("savedLyrics.list")!!.getString("key"))
        descendants(recreated.window.decorView).filterIsInstance<SavedLyricsSelectionIconView>().single().performClick()
        fun action(id: Int) = descendants(recreated.window.decorView).filterIsInstance<TextView>()
            .single { it.text == recreated.getString(id) }.performClick()
        action(R.string.ui_select_all)
        assertEquals(62, recreated.graph.uiHost.interactions.read("savedLyricsSelection")!!.getStringArrayList("keys")!!.size)
        descendants(recreated.window.decorView).filterIsInstance<EditText>().single().setText("Extra")
        action(R.string.ui_batch_deselect_all)
        assertEquals(2, recreated.graph.uiHost.interactions.read("savedLyricsSelection")!!.getStringArrayList("keys")!!.size)
    }

    private fun launch(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.graph.viewModel.selectPage(Page.SETTINGS)
        activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.SAVED_LYRICS)
        activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
        awaitUiCondition { rows(activity).isNotEmpty() }
        return activity
    }

    private fun rows(activity: MainActivity): List<View> = descendants(activity.window.decorView).filter {
        val key = it.getTag(R.id.interaction_anchor) as? String
        key?.startsWith("lyrics:") == true && '/' !in key
    }
    private fun checkboxes(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<CheckBox>().filter { it.visibility == View.VISIBLE }

    private fun dialogTexts(): List<TextView> = ShadowDialog.getLatestDialog()?.window?.decorView
        ?.let(::descendants)?.filterIsInstance<TextView>().orEmpty()

    private fun clickDialogButton(text: String) {
        awaitUiCondition { dialogTexts().any { it.text == text && it.isClickable } }
        dialogTexts().single { it.text == text && it.isClickable }.performClick()
    }
}
