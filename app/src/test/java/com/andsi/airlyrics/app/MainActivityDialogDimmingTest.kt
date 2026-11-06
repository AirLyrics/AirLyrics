package com.andsi.airlyrics.app

import com.andsi.airlyrics.app.host.showLocalLyricsEditorSheet
import com.andsi.airlyrics.app.interaction.EditorSession
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.components.dismissAirDialogImmediately
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MainActivityDialogDimmingTest {
    @Test fun confirmationOverLyricsEditorDoesNotLightenBackground() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val host = controller.get().graph.uiHost
            val editor = host.showLocalLyricsEditorSheet(editorSession()).dialog
            val editorDim = editor.window!!.attributes.dimAmount
            assertTrue(editorDim > AirUiTokens.Layout.DialogDimAmount)

            val confirmation = host.showAirDialog(
                title = "Delete lyrics?", positiveText = "Delete", negativeText = "Cancel"
            )
            assertEquals(editorDim, confirmation.window!!.attributes.dimAmount, 0.001f)
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
            confirmation.cancel()
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
            assertFalse(confirmation.isShowing)
            assertTrue(editor.isShowing)
            assertEquals(editorDim, editor.window!!.attributes.dimAmount, 0.001f)

            editor.dismiss()
            val standalone = host.showAirDialog(title = "Info")
            assertEquals(AirUiTokens.Layout.DialogDimAmount, standalone.window!!.attributes.dimAmount, 0.001f)
        }
    }

    @Test fun coordinatedDimKeepsUnderlyingShadeThroughoutEntryAndExit() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val host = controller.get().graph.uiHost
            val editor = host.showLocalLyricsEditorSheet(editorSession()).dialog
            val editorDim = editor.window!!.attributes.dimAmount
            val dialog = host.showAirDialog(title = "Info", coordinatedDim = true)
            assertEquals(editorDim, dialog.window!!.attributes.dimAmount, 0.001f)
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
            assertEquals(editorDim, dialog.window!!.attributes.dimAmount, 0.001f)
            dialog.dismiss()
            ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS)
            assertEquals(editorDim, dialog.window!!.attributes.dimAmount, 0.001f)
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
            assertFalse(dialog.isShowing)
            assertTrue(editor.isShowing)

            // A registered window that is no longer showing must not affect later dialogs.
            editor.dismissAirDialogImmediately()
            val standalone = host.showAirDialog(title = "Info", coordinatedDim = true)
            assertEquals(0f, standalone.window!!.attributes.dimAmount, 0.001f)
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
            assertEquals(AirUiTokens.Layout.DialogDimAmount, standalone.window!!.attributes.dimAmount, 0.001f)
        }
    }

    private fun editorSession() = EditorSession(
        id = "dimming-preview",
        item = LocalLyricsUiItem(
            name = "preview", modifiedTimeMillis = 0, sizeBytes = 0,
            canDelete = true, displayTitle = "Editor"
        ),
        target = LyricsStorage.LocalLyricsEditTarget.PLAIN,
        text = "[00:01.00]Lyrics"
    )
}
