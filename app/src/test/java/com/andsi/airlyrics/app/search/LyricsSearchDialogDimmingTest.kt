package com.andsi.airlyrics.app.search

import android.app.Dialog
import android.content.Intent
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LyricsSearchDialogDimmingTest {
    @Test fun replacementConfirmationDoesNotLightenSourcesSheetBackground() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            val activity = controller.get()
            settle()
            val sheet = showSources(activity)
            val shade = sheet.requireDialog().window!!.attributes.dimAmount
            val confirmation = showReplacementConfirmation(activity)

            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
            settle()
            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
            confirmation.cancel()
            settle()
            assertFalse(confirmation.isShowing)
            assertTrue(sheet.requireDialog().isShowing)
            assertEquals(shade, activity.interactionUi.maxShowingDimAmount(), 0.001f)

            sheet.dismissNow()
            assertEquals(0f, activity.interactionUi.maxShowingDimAmount(), 0.001f)
        }
    }

    @Test fun missingSourcesPromptKeepsUnderlyingShadeDuringEntryAndExit() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            val activity = controller.get()
            settle()
            val sheet = showSources(activity)
            val shade = sheet.requireDialog().window!!.attributes.dimAmount

            // Cover a queued prompt arriving after another modal has already opened.
            LyricsSearchActivity::class.java.getDeclaredMethod("showMissingSourcesDialog")
                .apply { isAccessible = true }.invoke(activity)
            val prompt = ShadowDialog.getLatestDialog() as MissingSourcesDialog
            assertEquals(shade, prompt.window!!.attributes.dimAmount, 0.001f)
            settle()
            assertEquals(shade, prompt.window!!.attributes.dimAmount, 0.001f)
            prompt.dismiss()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            assertEquals(shade, prompt.window!!.attributes.dimAmount, 0.001f)
            settle()
            assertFalse(prompt.isShowing)
            assertTrue(sheet.requireDialog().isShowing)

            sheet.dismissNow()
            assertEquals(0f, activity.interactionUi.maxShowingDimAmount(), 0.001f)
            LyricsSearchActivity::class.java.getDeclaredMethod("showMissingSourcesDialog")
                .apply { isAccessible = true }.invoke(activity)
            val standalone = ShadowDialog.getLatestDialog()
            assertEquals(0f, standalone.window!!.attributes.dimAmount, 0.001f)
            settle()
            assertEquals(0.42f, standalone.window!!.attributes.dimAmount, 0.001f)
        }
    }

    @Test fun restoredSourcesSheetStillProtectsBackgroundOfNewConfirmations() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java, intent()).setup().visible().use { controller ->
            val oldActivity = controller.get()
            settle()
            val oldSheet = showSources(oldActivity).requireDialog()
            controller.recreate()
            settle()

            val activity = controller.get()
            val restored = activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
            assertFalse(oldSheet.isShowing)
            assertEquals(0f, oldActivity.interactionUi.maxShowingDimAmount(), 0.001f)
            val shade = restored.requireDialog().window!!.attributes.dimAmount
            val confirmation = showReplacementConfirmation(activity)
            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
            settle()
            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
        }
    }

    private fun showSources(activity: LyricsSearchActivity) =
        LyricsSourcesSheet.create(setOf(PlainLyricsSearchSource.NETEASE)).also {
            it.showNow(activity.supportFragmentManager, LyricsSourcesSheet.TAG)
            settle()
        }

    private fun showReplacementConfirmation(activity: LyricsSearchActivity): Dialog {
        val session = LyricsSearchActivity::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(activity) as LyricsSearchSession
        LyricsSearchActivity::class.java.getDeclaredMethod("render", LyricsSearchState::class.java)
            .apply { isAccessible = true }
            .invoke(activity, session.state.value.copy(confirmationRevision = "existing-local-lyrics"))
        return ShadowDialog.getLatestDialog()
    }

    private fun intent() = Intent(RuntimeEnvironment.getApplication(), LyricsSearchActivity::class.java)
        .putExtra("search.title", "Song").putExtra("search.artist", "Artist")

    private fun settle() = ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
}
