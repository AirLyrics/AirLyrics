package com.andsi.airlyrics.app.host

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.interaction.EditorSession
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLyricsEditorSheetInstrumentedTest {
    @Test fun actionRowKeepsFullWidthForLongAndEmptyLyrics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var list: RecyclerView
            scenario.onActivity { activity ->
                val session = EditorSession(
                    id = "layout-preview",
                    item = LocalLyricsUiItem(
                        name = "preview", modifiedTimeMillis = 0, sizeBytes = 0,
                        canDelete = true, displayTitle = "Editor layout"
                    ),
                    target = LyricsStorage.LocalLyricsEditTarget.PLAIN,
                    text = "[00:01.00]x\n[00:02.00]A long lyric that wraps across multiple lines in the editor\n[00:03.00]\n[00:04.00]♪ music ♪"
                )
                val dialog = activity.graph.uiHost.showLocalLyricsEditorSheet(session).dialog
                list = descendants(dialog.window!!.decorView).filterIsInstance<RecyclerView>().single()
            }
            instrumentation.waitForIdleSync()

            for (index in listOf(1, 2)) {
                scenario.onActivity { list.scrollToPosition(index) }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val holder = requireNotNull(list.findViewHolderForAdapterPosition(index))
                    val row = descendants(holder.itemView)
                        .first { it.isClickable && !it.contentDescription.isNullOrEmpty() }
                    assertTrue(row.performClick())
                }
                Thread.sleep(350)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val holder = requireNotNull(list.findViewHolderForAdapterPosition(index))
                    val content = (holder.itemView as ViewGroup).getChildAt(0)
                    val buttons = descendants(holder.itemView).filterIsInstance<TextView>().filter {
                        it.text.toString() in listOf(
                            activity.getString(R.string.ui_edit_line),
                            activity.getString(R.string.ui_delete),
                            activity.getString(R.string.ui_add_next_line)
                        )
                    }
                    assertEquals(3, buttons.size)
                    val actions = buttons.first().parent as LinearLayout
                    assertEquals(list.width - list.paddingLeft - list.paddingRight, holder.itemView.width)
                    assertEquals(holder.itemView.width - content.paddingLeft - content.paddingRight, actions.width)
                    assertTrue(buttons.last().width > buttons.first().width)
                    assertTrue(buttons.all { it.height >= (48 * activity.resources.displayMetrics.density).toInt() })
                    assertEquals(1, buttons[0].lineCount)
                    assertEquals(1, buttons[1].lineCount)
                    assertTrue(buttons[2].lineCount <= 2)
                }
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
