package com.andsi.airlyrics.app.search

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.providers.ManualLyricsNative
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.LyricsUnavailableReason
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import com.andsi.airlyrics.lyrics.search.ManualLyricsSearch
import com.andsi.airlyrics.lyrics.search.ManualWordDetails
import com.andsi.airlyrics.lyrics.search.ManualWordOption
import com.andsi.airlyrics.lyrics.search.ManualWordPage
import com.andsi.airlyrics.lyrics.search.ManualWordRecording
import com.andsi.airlyrics.lyrics.search.ManualWordSource
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.test.InstrumentationFailureDiagnostics
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsSearchInstrumentedTest {
    @get:Rule val diagnostics = InstrumentationFailureDiagnostics()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val access = DeviceSearchAccess()
    private val requests = ConcurrentLinkedQueue<Runnable>()
    private lateinit var originalSources: List<PlainLyricsSearchSource>

    @Before
    fun configureSearch() {
        originalSources = LyricsSettingsStore.getPlainLyricsSearchSources(context)
        setSources(PlainLyricsSearchSource.NETEASE)
        val dispatcher = Executor { requests.add(it) }.asCoroutineDispatcher()
        LyricsSearchActivity.dependenciesFactory = {
            LyricsSearchDependencies(access, io = dispatcher, diffExecutor = Executor { it.run() })
        }
    }

    @After
    fun restoreSearch() {
        LyricsSearchActivity.dependenciesFactory = null
        if (::originalSources.isInitialized) {
            LyricsSettingsStore.setPlainLyricsSearchSources(context, originalSources)
        }
    }

    @Test
    fun searchShowsMissingSourcesDialogAcrossRecreation() {
        setSources()
        launchSearch().use { scenario ->
            fun assertDialog() {
                awaitUi("Search window did not gain focus") { it.window.decorView.hasWindowFocus() }
                lateinit var message: String
                lateinit var ok: String
                scenario.onActivity {
                    message = it.getString(R.string.search_no_sources_selected)
                    ok = it.getString(R.string.ui_ok)
                }
                val automation = instrumentation.uiAutomation
                automation.executeAndWaitForEvent(
                    { submitSearch(scenario) },
                    { event ->
                        val appWindow = event.packageName?.toString() == context.packageName
                        appWindow && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                    },
                    5_000L,
                )
                val dialog = requireNotNull(automation.rootInActiveWindow)
                assertTrue(dialog.findAccessibilityNodeInfosByText(message).any { it.text.toString() == message })
                val button = dialog.findAccessibilityNodeInfosByText(ok)
                    .single { it.isClickable && it.text.toString() == ok }
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                completeRequests()
                assertTrue(access.searchedQueries.isEmpty())
            }
            assertDialog()
            scenario.recreate()
            assertDialog()
        }
    }

    @Test
    fun manualJniRejectsUnknownProviderAndMissingCredentialsWithoutNetwork() {
        val track = JSONObject().put("id", "").put("title", "Song").put("artist", "Artist")
            .put("album", "").put("duration_ms", 200_000)
        val request = JSONObject().put("operation", "search").put("provider", "unknown").put("track", track)
        val unknown = JSONObject(ManualLyricsNative.requestJson(request.toString(), "", 0))
        assertFalse(unknown.getBoolean("ok"))
        assertEquals("ParseError", unknown.getString("error_type"))
        request.put("provider", "spicy")
        val credentials = JSONObject(ManualLyricsNative.requestJson(request.toString(), "", 0))
        assertFalse(credentials.getBoolean("ok"))
        assertEquals("NeedCredential", credentials.getString("error_type"))
    }

    @Test
    fun searchWindowRetainsEditedQueryAcrossRecreation() {
        launchSearch("Original song", "Original artist", "Album", 200_000L).use { scenario ->
            scenario.onActivity { activity ->
                val fields = views(activity).filterIsInstance<EditText>()
                assertEquals("Original song", fields[0].text.toString())
                fields[0].setText("Edited query")
                fields[1].setText("Edited artist")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val fields = views(activity).filterIsInstance<EditText>()
                assertEquals("Edited query", fields[0].text.toString())
                assertEquals("Edited artist", fields[1].text.toString())
                assertTrue(texts(activity).any { it.text.toString().contains("Original song") })
                assertTrue(texts(activity).any {
                    it.text == activity.getString(R.string.search_regular_search) && it.isEnabled
                })
            }
            for ((orientation, expected) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
            )) {
                scenario.onActivity { it.requestedOrientation = orientation }
                awaitUi("Orientation did not settle") {
                    it.resources.configuration.orientation == expected && it.window.decorView.hasWindowFocus()
                }
                scenario.onActivity { activity ->
                    val fields = views(activity).filterIsInstance<EditText>()
                    assertEquals("Edited query", fields[0].text.toString())
                    assertEquals("Edited artist", fields[1].text.toString())
                }
            }
            saveScreenshot("manual-search.png")
        }
    }

    @Test
    fun appleCandidateAndWordTranslationPreviewSurviveRecreation() {
        val source = PlainLyricsSearchSource.APPLE
        setSources(source)
        val song = SongIdentity("Apple manual preview", "Artist", "Album", 200_000)
        val candidate = LyricsCandidate(source, "42", song, 0.9, "tr")
        val raw = """{"ok":true,"id":"42","lrc":"[00:01.00]Hello","translated_lrc":"[00:01.00]你好",
            "word_by_word":[{"start_ms":1000,"end_ms":2000,"text":"Hello","translated_text":"你好",
            "segments":[{"text":"Hello","start_ms":1000,"end_ms":2000}]}]}"""
        access.candidates = mapOf(source to listOf(candidate))
        val search = ManualLyricsSearch(context) { _, _ -> raw }
        access.preview = search::fetch

        launchSearch(song.title, song.artist).use { scenario ->
            submitSearch(scenario)
            completeRequests()
            selectCandidate(scenario, song.title)
            completeRequests()
            awaitUi("Apple word-timed preview did not appear") {
                hasText(it, R.string.search_word_lyrics)
            }
            scenario.recreate()
            awaitUi("Apple translation did not survive recreation") { activity ->
                texts(activity).any { it.isShown && it.text.toString().contains("你好") } &&
                    hasText(activity, R.string.search_word_lyrics)
            }
            assertEquals(listOf(candidate), access.fetchedCandidates)
            clickAction(scenario, R.string.search_apply_current)
            completeRequests()
            val applied = access.appliedLyrics.single()
            assertEquals("Hello", applied.wordByWordLines.single().text)
            assertTrue(applied.translatedLrc.orEmpty().contains("你好"))
        }
    }

    @Test
    fun resultStatusesAllowSwipeToExpandSearchControls() {
        launchSearch().use { scenario ->
            scenario.onActivity { activity ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            for (status in listOf("loading", "empty", "error")) {
                access.searchFailure = IllegalStateException("offline").takeIf { status == "error" }
                submitSearch(scenario)
                if (status != "loading") completeRequests()
                awaitUi("The $status results page did not appear") { activity ->
                    appBar(activity).bottom == 0 && when (status) {
                        "loading" -> shownLoadingViews(activity) > 0
                        "empty" -> hasText(activity, R.string.search_empty)
                        else -> hasText(activity, R.string.search_failed)
                    }
                }
                for (startFraction in listOf(.25f, .05f)) {
                    scenario.onActivity { appBar(it).setExpanded(false, false) }
                    awaitUi("Search controls did not collapse") { appBar(it).bottom == 0 }
                    swipeDownInPager(startFraction, .85f)
                    awaitUi("$status swipe from $startFraction did not expand the search controls") {
                        appBar(it).top == 0
                    }
                }
            }
        }
    }

    @Test
    fun sourcePagesAndEmptyPreviewSurviveRecreation() {
        val first = PlainLyricsSearchSource.NETEASE
        val second = PlainLyricsSearchSource.LRCLIB
        setSources(first, second)
        val previewTitle = "LRCLIB candidate ".repeat(8).trim()
        val previewSong = SongIdentity(previewTitle, "Artist", previewTitle, 200_000)
        access.candidates = mapOf(
            first to listOf(candidate(first, "NetEase candidate")),
            second to listOf(LyricsCandidate(second, "42", previewSong, 1.0)),
        )
        launchSearch("Original song", "Artist").use { scenario ->
            submitSearch(scenario)
            completeRequests()
            awaitUi("Source navigation must reach the top") { activity ->
                appBar(activity).bottom == 0 && pager(activity).adapter?.itemCount == 2
            }
            scenario.onActivity { activity -> requireNotNull(tabs(activity).getTabAt(1)).select() }
            awaitUi("Second source did not settle") { secondSourceIsSelected(it) }
            scenario.recreate()
            awaitUi("Second source did not survive recreation") { secondSourceIsSelected(it) }
            selectCandidate(scenario, previewTitle)
            completeRequests()
            awaitUi("Empty preview did not appear") { hasText(it, R.string.search_no_content) }

            scenario.recreate()
            awaitUi("Empty preview did not survive recreation") { hasText(it, R.string.search_no_content) }
            scenario.onActivity { activity ->
                val texts = texts(activity)
                val title = texts.single { it.isShown && it.text.toString() == previewTitle }
                assertFalse(texts.any { it !== title && it.isShown && it.text.toString().contains(previewTitle) })
                assertFalse(hasText(activity, R.string.search_lyrics))
                assertFalse(texts.any { it.isShown && it.text.toString().contains("Original song") })
                val titleScroll = title.parent as HorizontalScrollView
                assertTrue(titleScroll.canScrollHorizontally(1))
                titleScroll.scrollTo(title.width, 0)
                assertTrue(titleScroll.scrollX > 0)
                assertTrue(texts.any { it.isShown && it.text == activity.getString(R.string.search_apply_current) && !it.isEnabled })
                val back = backButton(activity)
                assertTrue(back is ImageButton)
                assertTrue(back.performClick())
                assertFalse(activity.isFinishing)
            }
            awaitUi("Back did not restore the previously selected source and collapsed search controls") {
                secondSourceIsSelected(it) && hasText(it, R.string.search_lyrics) && appBar(it).bottom == 0
            }
            scenario.onActivity { activity ->
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            swipeDownInPager(.30f, .80f)
            awaitUi("Source pager swipe did not expand the search controls") { activity ->
                appBar(activity).top == 0 &&
                    descendants(appBar(activity)).filterIsInstance<EditText>().take(2).all { it.isShown }
            }
            scenario.onActivity { appBar(it).setExpanded(false, false) }
            awaitUi("Search controls did not collapse") { appBar(it).bottom == 0 }
            saveScreenshot("manual-source-pages.png")
        }
    }

    @Test
    fun unavailableReasonsAreDistinctAndSurviveRecreation() {
        val source = PlainLyricsSearchSource.NETEASE
        val candidate = candidate(source, "Candidate")
        access.candidates = mapOf(source to listOf(candidate))
        val messages = linkedMapOf(
            LyricsUnavailableReason.SOURCE_EMPTY to R.string.search_source_empty,
            LyricsUnavailableReason.INSTRUMENTAL to R.string.search_no_content,
            LyricsUnavailableReason.UNSUPPORTED_FORMAT to R.string.search_unsupported_format,
            LyricsUnavailableReason.NO_USABLE_LINES to R.string.search_no_usable_lines,
            LyricsUnavailableReason.UNKNOWN to R.string.search_no_content,
            LyricsUnavailableReason.NO_TIMELINE to R.string.search_no_timeline,
        )
        launchSearch().use { scenario ->
            submitSearch(scenario)
            completeRequests()
            for ((index, entry) in messages.entries.withIndex()) {
                val (reason, message) = entry
                access.preview = { _, _, _ -> ManualLyricsFetchResult.Unavailable(reason) }
                if (index == 0) selectCandidate(scenario, candidate.song.title)
                else clickAction(scenario, R.string.search_retry)
                completeRequests()
                awaitUi("Unavailable message did not appear for $reason") { hasText(it, message) }
                scenario.onActivity { activity ->
                    assertFalse(messages.values.filter { it != message }.any { hasText(activity, it) })
                    assertTrue(texts(activity).any {
                        it.isShown && it.text == activity.getString(R.string.search_apply_current) && !it.isEnabled
                    })
                }
            }
            scenario.recreate()
            awaitUi("Unavailable reason did not survive recreation") { hasText(it, R.string.search_no_timeline) }
            scenario.onActivity { assertTrue(backButton(it).performClick()) }
            awaitUi("Leaving preview did not clear the unavailable message") {
                !hasText(it, R.string.search_no_timeline) && hasText(it, R.string.search_lyrics)
            }
        }
    }

    @Test
    fun loadingMotionReplacesTextAndClearsWhenPreviewFails() {
        val source = PlainLyricsSearchSource.NETEASE
        val candidate = candidate(source, "Candidate")
        access.candidates = mapOf(source to listOf(candidate))
        access.preview = { _, _, _ -> error("offline") }
        launchSearch().use { scenario ->
            submitSearch(scenario)
            awaitUi("Search loading indicator did not appear") {
                shownLoadingViews(it) > 0
            }
            scenario.onActivity { assertFalse(hasText(it, R.string.ui_loading)) }
            completeRequests()
            selectCandidate(scenario, candidate.song.title)
            awaitUi("Preview did not show a single loading indicator") {
                shownLoadingViews(it) == 1
            }
            saveScreenshot("manual-lyrics-loading.png")
            completeRequests()
            awaitUi("Preview failure did not replace its loading indicator") {
                shownLoadingViews(it) == 0 && hasText(it, R.string.search_failed)
            }
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test
    fun backFromResultsExpandsQueryBeforeLeavingSearch() {
        val source = PlainLyricsSearchSource.LRCLIB
        setSources(source)
        val candidate = candidate(source, "Candidate")
        access.candidates = mapOf(source to listOf(candidate))
        launchSearch("Original", "Artist").use { scenario ->
            scenario.onActivity { activity ->
                views(activity).filterIsInstance<EditText>().first().setText("Edited query")
            }
            submitSearch(scenario)
            completeRequests()
            awaitUi("Search did not show collapsed results") {
                appBar(it).bottom == 0 && texts(it).any { view -> view.isShown && view.text == candidate.song.title }
            }
            assertEquals(listOf(source to "Edited query"), access.searchedQueries)
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse(activity.isFinishing)
            }
            awaitUi("Back did not expand the search controls") { appBar(it).top == 0 }
            scenario.onActivity { activity ->
                assertEquals("Edited query", views(activity).filterIsInstance<EditText>().first().text.toString())
                assertTrue(texts(activity).any { it.isShown && it.text == candidate.song.title })
            }
            scenario.recreate()
            awaitUi("Expanded search controls did not survive recreation") { appBar(it).top == 0 }
            scenario.onActivity { activity ->
                assertTrue(backButton(activity).performClick())
                assertTrue(activity.isFinishing)
            }
        }
    }

    @Test
    fun sourceSheetKeepsDraftAcrossRotationAndOnlyCommitsOnConfirm() {
        val initial = PlainLyricsSearchSource.NETEASE
        val added = PlainLyricsSearchSource.LRCLIB
        launchSearch().use { scenario ->
            scenario.onActivity { activity ->
                assertSourceCount(activity, 1)
                openSourceSheet(activity)
                val row = sourceRow(activity, added)
                assertFalse(row.isSelected)
                assertTrue(row.performClick())
                assertSourceCount(activity, 1)
            }
            scenario.recreate()
            awaitUi("Source sheet did not restore its draft and bounds") { activity ->
                val dialog = sourceSheet(activity)?.dialog
                val morph = dialog?.window?.decorView?.let(::descendants)
                    ?.filterIsInstance<LyricsSourcesMorphView>()?.singleOrNull()
                morph != null && morph.panelBounds.width() > morph.capsuleBounds.width()
            }
            scenario.onActivity { activity ->
                val morph = sheetViews(activity).filterIsInstance<LyricsSourcesMorphView>().single()
                assertTrue(morph.panelBounds.width() > morph.capsuleBounds.width())
                assertEquals(morph.capsuleBounds.right, morph.panelBounds.right, 0.01f)
                assertTrue(sourceRow(activity, added).isSelected)
                assertSourceCount(activity, 1)
            }
            saveScreenshot("manual-source-picker.png")
            scenario.onActivity { activity ->
                assertTrue(confirmSourcesButton(activity, 2).performClick())
            }
            awaitUi("Confirmed source selection did not close the sheet") { activity ->
                sourceSheet(activity) == null &&
                    activity.sourceCapsule()?.text.toString() == activity.getString(R.string.search_sources_count, 2)
            }
            scenario.onActivity { activity ->
                openSourceSheet(activity)
                for (source in listOf(initial, added)) {
                    val row = sourceRow(activity, source)
                    assertTrue(row.isSelected)
                    assertTrue(row.performClick())
                }
                assertFalse(confirmSourcesButton(activity, 0).isEnabled)
                val cancel = sheetViews(activity).single {
                    it.contentDescription == activity.getString(R.string.ui_cancel)
                }
                assertTrue(cancel.performClick())
            }
            awaitUi("Source sheet did not close after cancel") { sourceSheet(it) == null }
            scenario.onActivity { assertSourceCount(it, 2) }
        }
        launchSearch().use { scenario ->
            scenario.onActivity { assertSourceCount(it, 1) }
        }
        setSources(PlainLyricsSearchSource.SPICY)
        launchSearch().use { scenario ->
            scenario.onActivity { activity ->
                openSourceSheet(activity)
                assertTrue(sourceRow(activity, PlainLyricsSearchSource.SPICY).isSelected)
                assertFalse(sourceRow(activity, initial).isSelected)
            }
        }
    }

    @Test
    fun fieldResetIconsOnlyShowForChangedFieldsAndRestoreIndependently() {
        val descriptions = listOf(R.string.search_restore_title, R.string.search_restore_artist, R.string.search_restore_album)
        fun icons(activity: LyricsSearchActivity) = descriptions.map { description ->
            views(activity).single { it.contentDescription == activity.getString(description) }
        }
        launchSearch("Song", "Artist").use { scenario ->
            scenario.onActivity { activity -> assertTrue(icons(activity).none { it.isShown }) }
            clickAction(scenario, R.string.search_more)
            awaitUi("More search conditions did not reveal the album field") {
                views(it).filterIsInstance<EditText>().getOrNull(2)?.isShown == true
            }
            scenario.onActivity { activity ->
                val fields = views(activity).filterIsInstance<EditText>()
                fields[0].setText("Edited title")
                fields[1].setText("Edited artist")
                fields[2].setText("Edited album")
            }
            awaitUi("Edited fields did not show settled reset icons") {
                icons(it).all { icon -> icon.isShown && icon.isEnabled && icon.alpha == 1f }
            }
            saveScreenshot("manual-field-resets.png")
            scenario.onActivity { assertTrue(icons(it)[1].performClick()) }
            awaitUi("Artist reset did not update the field") { !icons(it)[1].isEnabled }
            scenario.onActivity { activity ->
                val fields = views(activity).filterIsInstance<EditText>()
                assertEquals(listOf("Edited title", "Artist", "Edited album"), fields.map { it.text.toString() })
                assertEquals(fields[1].length(), fields[1].selectionStart)
                assertEquals(fields[1].length(), fields[1].selectionEnd)
                fields[0].setText("Song")
            }
            scenario.recreate()
            awaitUi("Independent reset icons did not survive recreation") {
                icons(it).map { icon -> icon.isShown } == listOf(false, false, true)
            }
            scenario.onActivity { assertTrue(icons(it)[2].performClick()) }
            awaitUi("Last reset icon did not disappear") { icons(it).none { icon -> icon.isShown } }
            scenario.onActivity { activity ->
                val fields = views(activity).filterIsInstance<EditText>()
                assertEquals(listOf("Song", "Artist", ""), fields.map { it.text.toString() })
                assertEquals(0, fields[2].selectionStart)
                assertFalse(icons(activity)[2].isEnabled)
                fields[0].setText("Another title")
            }
            awaitUi("Title edit did not show its reset icon") { icons(it)[0].isEnabled }
            scenario.onActivity { assertTrue(icons(it)[0].performClick()) }
            scenario.onActivity { activity ->
                val field = views(activity).filterIsInstance<EditText>().first()
                assertEquals(field.length(), field.selectionStart)
                field.setText("Edited during dismissal")
            }
            awaitUi("Title reset icon did not recover from the interrupted dismissal") { activity ->
                icons(activity)[0].run { isShown && isEnabled && alpha == 1f && scaleX == 1f }
            }
            assertTrue(access.searchedQueries.isEmpty())
        }
    }

    @Test
    fun configuredOrderIsSharedBySourceSheetAndResultPages() {
        val order = listOf(PlainLyricsSearchSource.SPICY, PlainLyricsSearchSource.LRCLIB, PlainLyricsSearchSource.NETEASE)
        val all = order + PlainLyricsSearchSource.MUSIXMATCH
        setSources(*order.toTypedArray())
        launchSearch().use { scenario ->
            scenario.onActivity { activity ->
                assertSourceCount(activity, 3)
                openSourceSheet(activity)
            }
            scenario.recreate()
            awaitUi("Source sheet did not restore") { sourceSheet(it)?.dialog?.isShowing == true }
            scenario.onActivity { activity ->
                val names = all.map { activity.localizedPlainLyricsProviderName(it.key) }
                val views = sheetViews(activity)
                val shownNames = views.mapNotNull { it.contentDescription?.toString()?.takeIf { name -> name in names } }
                assertEquals(names, shownNames)
                val first = sourceRow(activity, order.first())
                assertTrue(first.performClick())
                assertTrue(first.performClick())
                assertTrue(confirmSourcesButton(activity, 3).performClick())
            }
            awaitUi("Source sheet did not close") { sourceSheet(it) == null }
            submitSearch(scenario)
            awaitUi("Result pages did not appear in configured order") { tabs(it).tabCount == order.size }
            scenario.onActivity { assertTabOrder(it, order) }
            completeRequests()
            scenario.recreate()
            awaitUi("Result pages did not survive recreation") { tabs(it).tabCount == order.size }
            scenario.onActivity { assertTabOrder(it, order) }
        }
    }

    private fun launchSearch(
        title: String = "Song",
        artist: String = "",
        album: String = "",
        durationMs: Long = 0L,
    ): ActivityScenario<LyricsSearchActivity> = ActivityScenario.launch(
        Intent(context, LyricsSearchActivity::class.java)
            .putExtra("search.title", title)
            .putExtra("search.artist", artist)
            .putExtra("search.album", album)
            .putExtra("search.duration", durationMs),
    )

    private fun submitSearch(scenario: ActivityScenario<LyricsSearchActivity>) =
        clickAction(scenario, R.string.search_regular_search)

    private fun clickAction(scenario: ActivityScenario<LyricsSearchActivity>, label: Int) {
        scenario.onActivity { activity ->
            val button = texts(activity).single {
                it.isShown && it.isClickable && it.isEnabled && it.text == activity.getString(label)
            }
            assertTrue(button.performClick())
        }
    }

    private fun selectCandidate(scenario: ActivityScenario<LyricsSearchActivity>, title: String) {
        awaitUi("Candidate $title did not appear") { candidateLabel(it, title) != null }
        scenario.onActivity { activity ->
            val label = requireNotNull(candidateLabel(activity, title))
            val row = generateSequence<View>(label) { it.parent as? View }.first { it.isClickable }
            assertTrue(row.performClick())
        }
    }

    private fun candidateLabel(activity: LyricsSearchActivity, title: String): TextView? =
        texts(activity).firstOrNull { label ->
            label.text == title && label.isShown && label.getGlobalVisibleRect(Rect()) &&
                generateSequence<View>(label) { it.parent as? View }.any { it.isClickable }
        }

    private fun completeRequests() {
        instrumentation.waitForIdleSync()
        while (true) {
            val request = requests.poll() ?: break
            request.run()
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitUi(message: String, ready: (LyricsSearchActivity) -> Boolean) {
        val finished = CountDownLatch(1)
        lateinit var callback: Choreographer.FrameCallback
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val choreographer = Choreographer.getInstance()
            callback = Choreographer.FrameCallback {
                try {
                    val activity = monitor.getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<LyricsSearchActivity>().singleOrNull()
                    if (activity != null && ready(activity)) finished.countDown()
                    else choreographer.postFrameCallback(callback)
                } catch (error: Throwable) {
                    failure = error
                    finished.countDown()
                }
            }
            choreographer.postFrameCallback(callback)
        }
        try {
            assertTrue(message, finished.await(5L, TimeUnit.SECONDS))
            failure?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { Choreographer.getInstance().removeFrameCallback(callback) }
        }
    }

    private fun swipeDownInPager(startFraction: Float, endFraction: Float) {
        var swipe: Triple<Float, Float, Float>? = null
        awaitUi("Lyrics search pager did not gain an unobscured touch region") { activity ->
            val decor = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(decor)
            val pager = pager(activity)
            val pagerBounds = Rect()
            val windowBounds = Rect()
            if (decor.hasWindowFocus() && pager.isShown && !pager.isLayoutRequested &&
                insets?.isVisible(WindowInsetsCompat.Type.ime()) != true &&
                pager.getGlobalVisibleRect(pagerBounds) && decor.getGlobalVisibleRect(windowBounds)
            ) {
                val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
                val top = maxOf(pagerBounds.top, windowBounds.top + (bars?.top ?: 0))
                val bottom = minOf(pagerBounds.bottom, windowBounds.bottom - (bars?.bottom ?: 0))
                if (bottom - top >= 120) {
                    swipe = Triple(
                        pagerBounds.exactCenterX(),
                        top + (bottom - top) * startFraction,
                        top + (bottom - top) * endFraction,
                    )
                }
            }
            swipe != null
        }
        val (x, startY, endY) = requireNotNull(swipe)
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, x, startY + (endY - startY) * step / 20, 0,
            )
            try {
                instrumentation.sendPointerSync(event)
            } catch (failure: Throwable) {
                diagnostics.captureNow(failure)
                throw failure
            } finally {
                event.recycle()
            }
            SystemClock.sleep(20)
        }
    }

    private fun saveScreenshot(filename: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                PlatformTestStorageRegistry.getInstance().openOutputFile(filename).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun setSources(vararg sources: PlainLyricsSearchSource) =
        LyricsSettingsStore.setPlainLyricsSearchSources(context, sources.toList())

    private fun candidate(source: PlainLyricsSearchSource, title: String) =
        LyricsCandidate(source, "42", SongIdentity(title, "Artist", "", 200_000), 1.0)

    private fun secondSourceIsSelected(activity: LyricsSearchActivity) =
        pager(activity).run { isShown && currentItem == 1 && scrollState == ViewPager2.SCROLL_STATE_IDLE }

    private fun assertTabOrder(activity: LyricsSearchActivity, order: List<PlainLyricsSearchSource>) {
        val tabs = tabs(activity)
        assertEquals(order.size, tabs.tabCount)
        for ((index, source) in order.withIndex()) {
            val label = descendants(requireNotNull(tabs.getTabAt(index)?.customView))
                .filterIsInstance<TextView>().single().text.toString()
            assertTrue(label.startsWith(activity.localizedPlainLyricsProviderName(source.key)))
        }
    }

    private fun openSourceSheet(activity: LyricsSearchActivity) {
        assertTrue(requireNotNull(activity.sourceCapsule()).performClick())
    }

    private fun sourceSheet(activity: LyricsSearchActivity): LyricsSourcesSheet? =
        activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as? LyricsSourcesSheet

    private fun sheetViews(activity: LyricsSearchActivity): List<View> {
        val dialog = requireNotNull(sourceSheet(activity)).requireDialog()
        return descendants(requireNotNull(dialog.window).decorView)
    }

    private fun sourceRow(activity: LyricsSearchActivity, source: PlainLyricsSearchSource): View =
        sheetViews(activity).single { it.contentDescription == activity.localizedPlainLyricsProviderName(source.key) }

    private fun confirmSourcesButton(activity: LyricsSearchActivity, count: Int): TextView =
        sheetViews(activity).filterIsInstance<TextView>().single {
            it.text == activity.getString(R.string.search_sources_use, count)
        }

    private fun assertSourceCount(activity: LyricsSearchActivity, count: Int) {
        assertEquals(activity.getString(R.string.search_sources_count, count), activity.sourceCapsule()?.text.toString())
    }

    private fun appBar(activity: LyricsSearchActivity) = views(activity).filterIsInstance<AppBarLayout>().single()
    private fun pager(activity: LyricsSearchActivity) = views(activity).filterIsInstance<ViewPager2>().single()
    private fun tabs(activity: LyricsSearchActivity) = views(activity).filterIsInstance<TabLayout>().single()
    private fun backButton(activity: LyricsSearchActivity) =
        views(activity).single { it.contentDescription == activity.getString(R.string.search_back) }

    private fun shownLoadingViews(activity: LyricsSearchActivity) =
        views(activity).count { it is LyricsLoadingView && it.isShown }

    private fun texts(activity: LyricsSearchActivity) = views(activity).filterIsInstance<TextView>()
    private fun hasText(activity: LyricsSearchActivity, label: Int) =
        texts(activity).any { it.isShown && it.text == activity.getString(label) }

    private fun views(activity: LyricsSearchActivity) =
        descendants(activity.findViewById(android.R.id.content))

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else {
        emptyList()
    }

    private class DeviceSearchAccess : LyricsSearchAccess {
        var candidates: Map<PlainLyricsSearchSource, List<LyricsCandidate>> = emptyMap()
        var searchFailure: Exception? = null
        var preview: (LyricsCandidate, SongIdentity, LyricsLookupCancellationToken) -> ManualLyricsFetchResult =
            { _, _, _ -> ManualLyricsFetchResult.Unavailable(LyricsUnavailableReason.UNKNOWN) }
        val searchedQueries = mutableListOf<Pair<PlainLyricsSearchSource, String>>()
        val fetchedCandidates = mutableListOf<LyricsCandidate>()
        val appliedLyrics = mutableListOf<LyricsProviderResult>()

        override fun search(
            source: PlainLyricsSearchSource,
            query: SongIdentity,
            token: LyricsLookupCancellationToken,
        ): List<LyricsCandidate> {
            searchedQueries += source to query.title
            searchFailure?.let { throw it }
            return candidates[source].orEmpty()
        }

        override fun fetch(
            candidate: LyricsCandidate,
            target: SongIdentity,
            token: LyricsLookupCancellationToken,
        ): ManualLyricsFetchResult {
            fetchedCandidates += candidate
            return preview(candidate, target, token)
        }

        override fun apply(
            target: SongIdentity,
            result: LyricsProviderResult,
            token: LyricsLookupCancellationToken,
            approvedRevision: String?,
        ): Boolean {
            appliedLyrics += result
            return true
        }

        override fun resolveInternationalTitle(query: SongIdentity, token: LyricsLookupCancellationToken) = null
        override fun findBySpotifyId(
            id: String,
            mediaSong: SongIdentity?,
            target: SongIdentity,
            token: LyricsLookupCancellationToken,
        ) = error("Unexpected Spotify lookup")

        override fun searchWordRecordings(
            source: ManualWordSource,
            query: SongIdentity,
            page: Int,
            keyword: String,
            token: LyricsLookupCancellationToken,
        ): ManualWordPage = error("Unexpected word search")

        override fun loadWordRecording(
            recording: ManualWordRecording,
            token: LyricsLookupCancellationToken,
        ): ManualWordDetails = error("Unexpected word recording")

        override fun loadWordOption(
            option: ManualWordOption,
            token: LyricsLookupCancellationToken,
        ): ManualWordDetails = error("Unexpected word option")

        override fun protectedRevision(target: SongIdentity) = null
    }
}
