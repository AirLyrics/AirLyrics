package com.andsi.airlyrics.app.search

import android.app.Dialog
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class LyricsSearchDialogDimmingTest {
    private val fixture = LyricsSearchUiFixture()
    @get:Rule val searchUi = fixture.rule

    private val source = PlainLyricsSearchSource.NETEASE
    private val target = SongIdentity("Song", "Artist", durationMs = 0)
    private val candidate = LyricsCandidate(source, "42", target.copy(title = "Replacement"), 1.0)

    @Test fun replacementConfirmationDoesNotLightenSourcesSheetBackground() {
        launchSearchWithReplacement().use { controller ->
            val activity = controller.get()
            val sheet = fixture.showSources(activity)
            val shade = sheet.requireDialog().window!!.attributes.dimAmount
            val confirmation = showReplacementConfirmation(activity)

            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
            fixture.settle()
            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
            confirmation.cancel()
            fixture.settle()
            assertFalse(confirmation.isShowing)
            assertTrue(sheet.requireDialog().isShowing)
            assertEquals(shade, activity.interactionUi.maxShowingDimAmount(), 0.001f)
            assertTrue(fixture.access.applied.isEmpty())

            sheet.dismissNow()
            assertEquals(0f, activity.interactionUi.maxShowingDimAmount(), 0.001f)
        }
    }

    @Test fun missingSourcesPromptKeepsUnderlyingShadeDuringEntryAndExit() {
        fixture.launch(emptyList(), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            val sheet = fixture.showSources(activity)
            val shade = sheet.requireDialog().window!!.attributes.dimAmount

            assertTrue(fixture.button(activity, R.string.search_regular_search).performClick())
            val prompt = ShadowDialog.getLatestDialog() as MissingSourcesDialog
            assertEquals(shade, prompt.window!!.attributes.dimAmount, 0.001f)
            fixture.settle()
            assertEquals(shade, prompt.window!!.attributes.dimAmount, 0.001f)
            prompt.dismiss()
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            assertEquals(shade, prompt.window!!.attributes.dimAmount, 0.001f)
            fixture.settle()
            assertFalse(prompt.isShowing)
            assertTrue(sheet.requireDialog().isShowing)

            sheet.dismissNow()
            assertEquals(0f, activity.interactionUi.maxShowingDimAmount(), 0.001f)
            assertTrue(fixture.button(activity, R.string.search_regular_search).performClick())
            val standalone = ShadowDialog.getLatestDialog()
            assertEquals(0f, standalone.window!!.attributes.dimAmount, 0.001f)
            fixture.settle()
            assertEquals(0.42f, standalone.window!!.attributes.dimAmount, 0.001f)
        }
    }

    @Test fun restoredSourcesSheetStillProtectsBackgroundOfNewConfirmations() {
        launchSearchWithReplacement().use { controller ->
            val oldActivity = controller.get()
            val oldSheet = fixture.showSources(oldActivity).requireDialog()
            controller.recreate()
            fixture.settle()

            val activity = controller.get()
            val restored = activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
            assertFalse(oldSheet.isShowing)
            assertEquals(0f, oldActivity.interactionUi.maxShowingDimAmount(), 0.001f)
            val shade = restored.requireDialog().window!!.attributes.dimAmount
            val confirmation = showReplacementConfirmation(activity)
            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
            fixture.settle()
            assertEquals(shade, confirmation.window!!.attributes.dimAmount, 0.001f)
        }
    }

    private fun launchSearchWithReplacement() = fixture.launch(listOf(source), fixture.intent(target)).also { controller ->
        fixture.access.revision = "existing-local-lyrics"
        fixture.access.candidates = listOf(candidate)
        fixture.access.fetchResult = ManualLyricsFetchResult.Available(
            LyricsProviderResult("netease", "NetEase", "[00:01.00]Replacement lyrics"),
        )
        val activity = controller.get()
        assertTrue(fixture.button(activity, R.string.search_regular_search).performClick())
        fixture.finishRequests()
        fixture.layout(fixture.content(activity))
    }

    private fun showReplacementConfirmation(activity: LyricsSearchActivity): Dialog {
        fixture.layout(fixture.content(activity))
        assertTrue(fixture.row(activity, candidate.song.title).performClick())
        fixture.finishRequests()
        fixture.layout(fixture.content(activity))
        assertTrue(fixture.button(activity, R.string.search_apply_current).performClick())
        fixture.finishRequests()
        return ShadowDialog.getLatestDialog()
    }
}
