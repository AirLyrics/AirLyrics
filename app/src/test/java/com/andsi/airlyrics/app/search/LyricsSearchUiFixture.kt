package com.andsi.airlyrics.app.search

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.LyricsUnavailableReason
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import com.andsi.airlyrics.lyrics.search.ManualWordDetails
import com.andsi.airlyrics.lyrics.search.ManualWordOption
import com.andsi.airlyrics.lyrics.search.ManualWordPage
import com.andsi.airlyrics.lyrics.search.ManualWordRecording
import com.andsi.airlyrics.lyrics.search.ManualWordSource
import com.andsi.airlyrics.lyrics.search.SpotifyIdLyricsResult
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.testutil.MainDispatcherRule
import com.andsi.airlyrics.testutil.descendants
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.assertTrue
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

internal class LyricsSearchUiFixture : TestWatcher() {
    val access = FakeLyricsSearchAccess()
    private val main = MainDispatcherRule()
    private val work = WorkQueue()
    private val diffs = WorkQueue()
    private var previousFactory: ((Context) -> LyricsSearchDependencies)? = null
    private var previousSources = emptyList<PlainLyricsSearchSource>()
    val rule: TestRule = RuleChain.outerRule(main).around(this)

    override fun starting(description: Description) {
        previousFactory = LyricsSearchActivity.dependenciesFactory
        previousSources = LyricsSettingsStore.getPlainLyricsSearchSources(RuntimeEnvironment.getApplication())
        LyricsSearchActivity.dependenciesFactory = { LyricsSearchDependencies(access, work, work, diffs) }
    }

    override fun finished(description: Description) {
        LyricsSearchActivity.dependenciesFactory = previousFactory
        LyricsSettingsStore.setPlainLyricsSearchSources(RuntimeEnvironment.getApplication(), previousSources)
    }

    fun intent(song: SongIdentity) = Intent(RuntimeEnvironment.getApplication(), LyricsSearchActivity::class.java)
        .putExtra("search.title", song.title)
        .putExtra("search.artist", song.artist)
        .putExtra("search.album", song.album)
        .putExtra("search.duration", song.durationMs)

    fun launch(sources: List<PlainLyricsSearchSource>, intent: Intent): ActivityController<LyricsSearchActivity> {
        LyricsSettingsStore.setPlainLyricsSearchSources(RuntimeEnvironment.getApplication(), sources)
        return Robolectric.buildActivity(LyricsSearchActivity::class.java, intent).setup().visible().also { settle() }
    }

    fun content(activity: LyricsSearchActivity): ViewGroup =
        requireNotNull(activity.findViewById(android.R.id.content))

    fun button(activity: LyricsSearchActivity, @StringRes label: Int): MaterialButton {
        val text = activity.getString(label)
        return descendants(content(activity)).asSequence().filterIsInstance<MaterialButton>()
            .single { it.isShown && (it.text.toString() == text) }
    }

    fun showSources(activity: LyricsSearchActivity): LyricsSourcesSheet {
        assertTrue(requireNotNull(activity.sourceCapsule()).performClick())
        settle()
        return activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
    }

    fun row(activity: LyricsSearchActivity, label: String): View =
        descendants(content(activity)).asSequence()
            .filterIsInstance<TextView>()
            .filter { it.isShown && (it.text.toString() == label) }
            .flatMap { generateSequence(it.parent as? View) { view -> view.parent as? View } }
            .first { it.isClickable }

    fun wordSearchDialog(activity: LyricsSearchActivity): WordLyricsSearchDialog {
        assertTrue(button(activity, R.string.search_try_word_search).performClick())
        activity.supportFragmentManager.executePendingTransactions()
        return activity.supportFragmentManager.findFragmentByTag(WordLyricsSearchDialog.TAG) as WordLyricsSearchDialog
    }

    fun submitWordSearch(activity: LyricsSearchActivity) {
        val dialog = wordSearchDialog(activity).requireDialog()
        assertTrue(dialog.findViewById<View>(android.R.id.button1).performClick())
        settle()
    }

    fun finishRequests() {
        main.dispatcher.scheduler.runCurrent()
        while (work.hasTasks) {
            work.drain()
            main.dispatcher.scheduler.runCurrent()
        }
        settle()
    }

    fun finishDiffs() {
        diffs.drain()
        settle()
    }

    fun settle() {
        main.dispatcher.scheduler.runCurrent()
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        main.dispatcher.scheduler.runCurrent()
    }

    fun layout(view: View, height: Int = 900, width: Int = 400) {
        repeat(2) {
            settle()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
    }

    private class WorkQueue : CoroutineDispatcher(), Executor {
        private val tasks = ArrayDeque<Runnable>()
        val hasTasks: Boolean get() = tasks.isNotEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) = execute(block)

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}

internal class FakeLyricsSearchAccess : LyricsSearchAccess {
    var candidates = emptyList<LyricsCandidate>()
    var fetchResult: ManualLyricsFetchResult = ManualLyricsFetchResult.Unavailable(LyricsUnavailableReason.UNKNOWN)
    var spotifyIdResult: SpotifyIdLyricsResult? = null
    var onWordPage: (ManualWordSource, SongIdentity, Int) -> ManualWordPage = { _, _, page ->
        ManualWordPage(emptyList(), page)
    }
    var onRecording: (ManualWordRecording) -> ManualWordDetails = { ManualWordDetails() }
    var revision: String? = null
    val applied = mutableListOf<Pair<SongIdentity, LyricsProviderResult>>()

    override fun search(source: PlainLyricsSearchSource, query: SongIdentity, token: LyricsLookupCancellationToken) =
        candidates

    override fun resolveInternationalTitle(query: SongIdentity, token: LyricsLookupCancellationToken): SongIdentity? = null

    override fun fetch(candidate: LyricsCandidate, target: SongIdentity, token: LyricsLookupCancellationToken) =
        fetchResult

    override fun findBySpotifyId(id: String, mediaSong: SongIdentity?, target: SongIdentity, token: LyricsLookupCancellationToken) =
        spotifyIdResult

    override fun searchWordRecordings(
        source: ManualWordSource,
        query: SongIdentity,
        page: Int,
        keyword: String,
        token: LyricsLookupCancellationToken,
    ) = onWordPage(source, query, page)

    override fun loadWordRecording(recording: ManualWordRecording, token: LyricsLookupCancellationToken) =
        onRecording(recording)

    override fun loadWordOption(option: ManualWordOption, token: LyricsLookupCancellationToken) = ManualWordDetails()

    override fun protectedRevision(target: SongIdentity) = revision

    override fun apply(
        target: SongIdentity,
        result: LyricsProviderResult,
        token: LyricsLookupCancellationToken,
        approvedRevision: String?,
    ): Boolean {
        applied.add(target to result)
        return true
    }
}
