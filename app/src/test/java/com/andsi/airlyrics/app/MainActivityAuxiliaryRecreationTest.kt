package com.andsi.airlyrics.app

import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.testutil.awaitUiCondition as await
import com.andsi.airlyrics.app.interaction.LyricsDraftStore
import kotlinx.coroutines.runBlocking
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.showFullText
import com.andsi.airlyrics.ui.state.restoreAuxiliaryDialogs
import com.andsi.airlyrics.ui.state.confirmOperation
import com.andsi.airlyrics.ui.model.ConfirmationAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MainActivityAuxiliaryRecreationTest {
    @Test fun longReaderRestoresOnlyOnceAndCancelDeletesItsFile() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val text = "Long text 阅读内容 ".repeat(6000)
            controller.get().graph.uiHost.showFullText("Reader", text)
            val id = controller.get().graph.viewModel.interactions.read("aux.reader")!!.getString("file")!!
            val old = ShadowDialog.getLatestDialog()
            controller.recreate()
            val host = controller.get().graph.uiHost
            host.restoreAuxiliaryDialogs()
            await { host.auxiliaryDialogs["reader"]?.isShowing == true }
            val restored = host.auxiliaryDialogs["reader"]!!
            assertFalse(old.isShowing)
            host.restoreAuxiliaryDialogs()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertSame(restored, host.auxiliaryDialogs["reader"])
            assertTrue(descendants(restored.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == text })
            restored.dismiss()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertNull(host.interactions.read("aux.reader"))
            assertNull(runBlocking { host.readerContent.load(id) })
        }
    }

    @Test fun confirmationClickIsConsumedSynchronouslyAndDoesNotRestore() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val host = controller.get().graph.uiHost
            host.confirmOperation(ConfirmationAction.DeleteAll, "Delete", "All lyrics", host.getString(R.string.ui_delete))
            val dialog = ShadowDialog.getLatestDialog()
            val button = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .first { it.text.toString() == host.getString(R.string.ui_delete) && it.hasOnClickListeners() }
            button.performClick()
            assertNull(host.interactions.read("confirmation"))
            button.performClick()
            controller.recreate()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertNull(controller.get().graph.uiHost.activeConfirmationId)
            assertNull(controller.get().graph.viewModel.interactions.read("confirmation"))
        }
    }

    @Test fun sessionDraftPruningCannotDeleteAnotherWindowsDraft() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { first ->
            val firstStore = LyricsDraftStore.forSession(first.get(), first.get().graph.viewModel.interactions, "reader-drafts")
            firstStore.write("draft-one", "first window").get()
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { second ->
                val secondStore = LyricsDraftStore.forSession(second.get(), second.get().graph.viewModel.interactions, "reader-drafts")
                secondStore.write("draft-two", "second window").get()
                secondStore.prune("draft-two").get()
                assertEquals("first window", firstStore.read("draft-one").get())
                assertEquals("second window", secondStore.read("draft-two").get())
            }
        }
    }

}
