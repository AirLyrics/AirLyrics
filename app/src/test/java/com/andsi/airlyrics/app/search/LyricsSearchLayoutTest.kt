package com.andsi.airlyrics.app.search

import android.content.ClipboardManager
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.UntimedLyrics
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.providers.SpicyLyricsParser
import com.andsi.airlyrics.lyrics.providers.spicyFixture
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult
import com.andsi.airlyrics.lyrics.search.ManualWordDetails
import com.andsi.airlyrics.lyrics.search.ManualWordPage
import com.andsi.airlyrics.lyrics.search.ManualWordRecording
import com.andsi.airlyrics.lyrics.search.ManualWordSource
import com.andsi.airlyrics.lyrics.search.ManualWordVersion
import com.andsi.airlyrics.lyrics.search.SpotifyIdLyricsResult
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.descendants
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class LyricsSearchLayoutTest {
    private val fixture = LyricsSearchUiFixture()
    @get:Rule val searchUi = fixture.rule

    private val target = SongIdentity("8bit Voyager", "Lime", durationMs = 0)
    private val netease = PlainLyricsSearchSource.NETEASE
    private val spicy = PlainLyricsSearchSource.SPICY
    private val supplier = ManualWordSource.NETEASE
    private val spotifyId = "5l94Up12BJSjhH7Mhr2ji4"
    private val words = listOf(
        WordByWordLine(
            1000, 2000, "你好", listOf(WordByWordSegment("你", 1000, 1400), WordByWordSegment("好", 1400, 2000)),
        ),
    )

    @Test fun formWithoutSourcesRemainsBoundedAtKeyboardHeights() {
        fixture.launch(emptyList(), fixture.intent(target)).use { controller ->
            val content = fixture.content(controller.get())
            val query = descendants(content).asSequence().filterIsInstance<NestedScrollView>()
                .single { it.isShown && descendants(it).any { child -> child is EditText } }
            val appBar = appBar(content)
            for (height in listOf(1000, 300, 180, 1000)) {
                fixture.layout(content, height)
                assertEquals(View.GONE, appBar.visibility)
                assertEquals(View.VISIBLE, query.visibility)
                query.scrollTo(0, 100_000)
                val range = (query.getChildAt(0).height - query.height).coerceAtLeast(0)
                assertEquals(range, query.scrollY)
                query.scrollTo(0, 0)
                assertEquals(0, query.scrollY)
                assertTrue(descendants(query).filterIsInstance<EditText>().any(View::isShown))
            }
        }
    }

    @Test fun selectingSourcesBeforeSearchKeepsTheSameEditedForm() {
        fixture.launch(emptyList(), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            val content = fixture.content(activity)
            val appBar = appBar(content)
            val query = descendants(content).asSequence().filterIsInstance<NestedScrollView>()
                .single { it.isShown && descendants(it).any { child -> child is EditText } }
            val input = descendants(query).filterIsInstance<EditText>().first()
            input.setText("Edited query")
            fixture.layout(content)

            val sheet = fixture.showSources(activity)
            val picker = descendants(sheet.requireDialog().window!!.decorView)
            val sourceChoice = picker.single {
                it.contentDescription == activity.localizedPlainLyricsProviderName(spicy.key)
            }
            assertTrue(sourceChoice.performClick())
            assertTrue(picker.filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.search_sources_use) }.performClick())

            for (height in listOf(1000, 300, 180, 1000)) {
                fixture.layout(content, height)
                assertEquals(View.VISIBLE, appBar.visibility)
                assertEquals(View.GONE, query.visibility)
                assertSame(input, descendants(appBar).filterIsInstance<EditText>().first())
                assertEquals("Edited query", input.text.toString())
            }
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun spicyIdEntryIsAvailableBeforeSearchAndOnlyExactRowsHaveBadge() {
        val app = RuntimeEnvironment.getApplication()
        val dark = ThemeSettingsStore.isDark(app)
        ThemeSettingsStore.setDark(app, enabled = true)
        try {
            val song = SongIdentity("8bit Voyager", "Kankitsu", "Groundbreaking Compilation Album", 140000)
            val exact = LyricsCandidate(spicy, spotifyId, song, 0.0, spotifyIdMatch = true)
            val other = LyricsCandidate(spicy, "0X5X6D3kZyDEG8Im1LQaVf", song.copy(title = "8 Bit Voyager (Energy Sound Version)"), 0.9)
            fixture.access.candidates = listOf(other)
            fixture.access.spotifyIdResult = SpotifyIdLyricsResult(
                exact, LyricsProviderResult("spicy", "Spicy", "[00:01.00]Hello"),
            )

            fixture.launch(listOf(spicy), fixture.intent(target).putExtra("search.spotifyId", spotifyId)).use { controller ->
                val activity = controller.get()
                val content = fixture.content(activity)
                fixture.layout(content)
                val fragment = spotifyDialog(activity)
                val dialog = fragment.requireDialog()
                val field = descendants(dialog.window!!.decorView).filterIsInstance<TextInputLayout>().single()
                field.editText!!.setText("spotify:episode:$spotifyId")
                dialog.findViewById<View>(android.R.id.button1).performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(field.error)
                fragment.dismissNow()

                submitSearch(activity)
                val visibleTexts = visibleTextViews(content).map { it.text.toString() }
                assertTrue(visibleTexts.contains(song.title))
                assertTrue(visibleTexts.any { song.artist in it && spotifyId in it })
                assertEquals(1, visibleTextViews(content).count {
                    it.contentDescription == activity.getString(R.string.search_id_match)
                })
            }
        } finally {
            ThemeSettingsStore.setDark(app, enabled = dark)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    fun spotifyPreviewKeepsTitleIdAndDisplaysTheActualTimingPrecision() {
        val song = SongIdentity("Fetched song", "Fetched artist", "Fetched album", 10000)
        val candidate = LyricsCandidate(spicy, spotifyId, song, 0.0, spotifyIdMatch = true)
        for ((type, label) in listOf("Line" to R.string.search_line_lyrics, "Syllable" to R.string.search_word_lyrics)) {
            val raw = JSONObject(spicyFixture(type)).apply {
                getJSONObject("track").put("id", spotifyId)
                getJSONObject("response").getJSONObject("Body").put("id", spotifyId)
            }
            val lyrics = SpicyLyricsParser.parse(raw.toString(), "key", System.currentTimeMillis())!!
            fixture.access.spotifyIdResult = SpotifyIdLyricsResult(candidate, lyrics)
            fixture.launch(listOf(spicy), fixture.intent(target)).use { controller ->
                val activity = controller.get()
                val content = fixture.content(activity)
                fixture.layout(content)
                val dialog = spotifyDialog(activity).requireDialog()
                val field = descendants(dialog.window!!.decorView).filterIsInstance<TextInputLayout>().single()
                field.editText!!.setText(spotifyId)
                assertTrue(dialog.findViewById<View>(android.R.id.button1).performClick())
                fixture.finishRequests()
                fixture.layout(content)
                selectRow(activity, song.title)

                val visible = visibleTextViews(content)
                val texts = visible.map { it.text.toString() }
                assertTrue(texts.contains(song.title))
                assertTrue(texts.any { spotifyId in it })
                assertTrue(texts.contains(activity.getString(label)))
                val lyricText = visible.single { it.isTextSelectable }.text.toString()
                if (type == "Line") {
                    assertEquals(lyrics.plainLrc, lyricText)
                    assertFalse(lyricText.contains('<'))
                    assertFalse(texts.contains(activity.getString(R.string.search_word_lyrics)))
                } else {
                    assertTrue(lyricText.contains("<00:01.001>Hel<00:01.432><00:01.567>"))
                    assertTrue(lyricText.endsWith("<00:03.789>"))
                }
            }
        }
    }

    @Test fun untimedPreviewShowsParagraphsTranslationAndAnEnabledApplyAction() {
        val source = PlainLyricsSearchSource.LRCLIB
        val song = target.copy(title = "Untimed candidate")
        val untimed = UntimedLyrics("First line\n\n第二段", "Translation\n\n译文")
        val lyrics = LyricsProviderResult("lrclib", "LRCLIB", "", untimedLyrics = untimed)
        setPreview(source, song, lyrics)

        fixture.launch(listOf(source), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            submitSearch(activity)
            selectRow(activity, song.title)
            val texts = visibleTextViews(fixture.content(activity)).map { it.text.toString() }
            assertTrue(texts.contains(activity.getString(R.string.ui_untimed_lyrics)))
            assertTrue(texts.contains(activity.getString(R.string.search_untimed_notice)))
            assertTrue(texts.contains(untimed.text))
            assertTrue(texts.contains(untimed.translatedText))
            assertTrue(fixture.button(activity, R.string.search_apply_current).isEnabled)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun previewCopiesDisplayedLyricsAndTranslation() = verifyPreviewCopy(400, "Copy lyrics")

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowChinesePreviewCopiesDisplayedLyricsAndTranslation() = verifyPreviewCopy(320, "复制歌词")

    private fun verifyPreviewCopy(width: Int, copyLabel: String) {
        val song = target.copy(title = "あのね", durationMs = 199_000)
        val original = "[00:00.64]ただ君を歌う\n[00:04.00]夜空を見上げる\n"
        val translation = "[00:00.64]只想唱你的歌\n[00:04.00]抬头仰望夜空"
        setPreview(netease, song, LyricsProviderResult("netease", "NetEase Lyrics", original, translatedLrc = translation))

        fixture.launch(listOf(netease), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            val content = fixture.content(activity)
            val clipboard = activity.getSystemService(ClipboardManager::class.java)!!
            submitSearch(activity, width)
            selectRow(activity, song.title, width)

            val (copyLyrics, copyTranslation) = descendants(content).filterIsInstance<LyricsPreviewCopyButton>()
                .filter { it.isShown }
            assertEquals(copyLabel, copyLyrics.contentDescription)
            assertEquals(activity.getString(R.string.search_copy_translation), copyTranslation.contentDescription)
            for (button in listOf(copyLyrics, copyTranslation)) {
                val row = button.parent as ViewGroup
                assertEquals(row.width, button.right)
                assertTrue(row.getChildAt(0).right <= button.left)
                assertEquals(48, button.width)
                assertEquals(48, button.height)
            }
            assertTrue(copyLyrics.performClick())
            assertEquals(original, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(activity.getString(R.string.search_copied), ViewCompat.getStateDescription(copyLyrics))
            assertNull(ViewCompat.getStateDescription(copyTranslation))

            assertTrue(copyTranslation.performClick())
            assertEquals(translation, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS)
            assertNull(ViewCompat.getStateDescription(copyLyrics))
            assertNull(ViewCompat.getStateDescription(copyTranslation))
        }
    }

    @Test fun wordSearchEntryPrefillsAllMetadataAndKeepsTheCurrentActivity() {
        val song = target.copy(album = "Fixture album", durationMs = 242000)
        fixture.launch(listOf(netease), fixture.intent(song)).use { controller ->
            val activity = controller.get()
            val content = fixture.content(activity)
            fixture.layout(content)
            val dialog = fixture.wordSearchDialog(activity).requireDialog()
            assertNull(shadowOf(activity).nextStartedActivity)
            val views = descendants(dialog.window!!.decorView)
            val fields = views.filterIsInstance<TextInputLayout>()
            assertEquals("8bit Voyager", fields[0].editText!!.text.toString())
            assertEquals("Lime", fields[1].editText!!.text.toString())
            assertEquals("Fixture album", fields[2].editText!!.text.toString())
            assertEquals("4:02", fields[3].editText!!.text.toString())
            assertFalse(fields[2].isShown)
            assertFalse(fields[3].isShown)
            views.filterIsInstance<MaterialButton>()
                .single { it.text.toString() == activity.getString(R.string.search_match_options) }.performClick()
            assertTrue(fields[2].isShown)
            assertTrue(fields[3].isShown)
            assertEquals("Fixture album", fields[2].editText!!.text.toString())
            assertEquals("4:02", fields[3].editText!!.text.toString())
            assertTrue(visibleTextViews(content).any { it.text == activity.getString(R.string.search_try_word_search) })
        }
    }

    @Test
    @Config(qualifiers = "zh-rTW-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wordCatalogueAndPreviewShowSuppliersAndKeepApplyReachable() {
        fixture.access.onWordPage = { provider, query, page ->
            if (provider == supplier) throw IllegalStateException("parse failed")
            ManualWordPage(listOf(ManualWordRecording(provider, "42", query.copy(title = "${provider.key} recording"))), page)
        }
        val lyrics = LyricsProviderResult("qq", "QQ", "[00:01.000]你好", wordByWordLines = words)
        fixture.access.onRecording = { recording ->
            ManualWordDetails(listOf(ManualWordVersion(recording.id, recording.source, recording.song, lyrics)))
        }
        fixture.launch(listOf(netease), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            val content = fixture.content(activity)
            fixture.layout(content)
            fixture.submitWordSearch(activity)
            fixture.finishRequests()
            fixture.layout(content)

            val labels = visibleTextViews(content)
            assertTrue(labels.any { "酷狗" in it.text })
            assertTrue(labels.any { "QQ 音樂" in it.text })
            assertTrue(labels.any { it.text == activity.getString(R.string.search_regular_search) })
            assertTrue(labels.any { it.text == activity.getString(R.string.search_lyrics) })
            val retryLabel = activity.getString(R.string.search_retry)
            assertFalse(labels.filterIsInstance<MaterialButton>().any { it.text.toString() == retryLabel })
            val retryRow = labels.single { it.isClickable && retryLabel in it.text }
            assertTrue(retryRow.height >= 48)
            assertEquals(0f, retryRow.elevation, 0.001f)

            selectRow(activity, "qq recording")
            val wordVersion = visibleTextViews(content).single { "QQ 音樂" in it.text && "逐字" in it.text }
            selectRow(activity, wordVersion.text.toString())
            val apply = fixture.button(activity, R.string.search_apply_current)
            assertTrue(apply.isEnabled)
            val bounds = Rect()
            assertTrue(apply.getGlobalVisibleRect(bounds))
            assertTrue(bounds.height() >= 48 && bounds.bottom <= 900)
            assertTrue(apply.performClick())
            fixture.finishRequests()
            assertEquals(listOf(target to lyrics), fixture.access.applied)
            assertTrue(activity.isFinishing)
        }
    }

    @Test
    @Config(qualifiers = "zh-rTW-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun recordingCatalogueAndIndependentTimingChoicesAreVisible() {
        val recording = ManualWordRecording(supplier, "42", target.copy(title = "Catalogue recording"))
        fixture.access.onWordPage = { provider, _, page ->
            ManualWordPage(if (provider == supplier) listOf(recording) else emptyList(), page, hasMore = provider == supplier)
        }
        val line = ManualWordVersion("42", supplier, recording.song, LyricsProviderResult("netease", "netease", "[00:01.000]你好"))
        fixture.access.onRecording = {
            ManualWordDetails(listOf(line, line.copy(lyrics = line.lyrics.copy(wordByWordLines = words))))
        }
        fixture.launch(listOf(netease), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            val content = fixture.content(activity)
            fixture.layout(content)
            fixture.submitWordSearch(activity)
            fixture.finishRequests()
            fixture.layout(content)
            var texts = visibleTextViews(content).map { it.text.toString() }
            assertFalse(texts.any { activity.getString(R.string.search_lyrics_unchecked) in it })
            assertEquals(1, texts.count { it == activity.getString(R.string.search_more_songs) })

            selectRow(activity, recording.song.title)
            texts = visibleTextViews(content).map { it.text.toString() }
            assertTrue(texts.any { activity.getString(R.string.search_version_line) in it })
            assertTrue(texts.any { activity.getString(R.string.search_version_word) in it })
            assertTrue(texts.contains(activity.getString(R.string.search_back_recordings)))
            assertEquals(2, wordList(content).adapter!!.itemCount)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun catalogueKeepsPaginationAtTheEndWithoutRecordingWarnings() {
        val recordings = (1..30).map { index ->
            ManualWordRecording(supplier, index.toString(), target.copy(album = "Album $index"), requiresConfirmation = index == 1)
        }
        fixture.access.onWordPage = { provider, _, page ->
            ManualWordPage(if (provider == supplier) recordings else emptyList(), page, hasMore = provider == supplier)
        }
        fixture.launch(listOf(netease), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            val content = fixture.content(activity)
            fixture.layout(content)
            fixture.submitWordSearch(activity)
            fixture.layout(content)
            assertTrue(descendants(content).filterIsInstance<LyricsLoadingView>().any { it.isShown })
            assertFalse(visibleTextViews(content).any { "Album 1" in it.text })

            fixture.finishRequests()
            appBar(content).setExpanded(false, false)
            fixture.layout(content)
            assertFalse(descendants(content).filterIsInstance<LyricsLoadingView>().any { it.isShown })
            val moreText = activity.getString(R.string.search_more_songs)
            assertFalse(visibleTextViews(content).any { it.text == moreText })
            val list = wordList(content)
            assertFalse(descendants(list).filterIsInstance<ImageButton>().any { it.isShown })

            list.scrollToPosition(list.adapter!!.itemCount - 1)
            fixture.layout(content)
            val footer = visibleTextViews(content).single { it.text == moreText }
            val bounds = Rect()
            assertTrue(footer.getGlobalVisibleRect(bounds))
            assertTrue(bounds.height() >= 48)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    fun paginationPreservesTheVisibleSongAndOffsetAcrossLoadingAndBackgroundDiffing() =
        verifyPaginationViewport(appendRows = true)

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    fun anEmptyFinalPageKeepsTheReadingPositionAndShowsTheEndOfResults() =
        verifyPaginationViewport(appendRows = false)

    private fun verifyPaginationViewport(appendRows: Boolean) {
        val firstPage = (1..30).map { index -> ManualWordRecording(supplier, index.toString(), target.copy(title = "Song $index")) }
        val secondPage = if (appendRows) (31..50).map { index ->
            ManualWordRecording(supplier, index.toString(), target.copy(title = "Song $index"))
        } else emptyList()
        fixture.access.onWordPage = { provider, _, page ->
            when {
                provider != supplier -> ManualWordPage(emptyList(), page)
                page == 1 -> ManualWordPage(firstPage, page, hasMore = true)
                else -> ManualWordPage(secondPage, page)
            }
        }
        fixture.launch(listOf(netease), fixture.intent(target)).use { controller ->
            val activity = controller.get()
            val content = fixture.content(activity)
            fixture.layout(content)
            fixture.submitWordSearch(activity)
            fixture.finishRequests()
            val appBar = appBar(content)
            appBar.setExpanded(false, false)
            fixture.layout(content)
            val list = wordList(content)
            list.scrollToPosition(list.adapter!!.itemCount - 1)
            fixture.layout(content)
            val manager = list.layoutManager as LinearLayoutManager
            val position = manager.findFirstVisibleItemPosition()
            assertTrue(position in 1 until firstPage.size)
            val id = list.adapter!!.getItemId(position)
            val top = manager.findViewByPosition(position)!!.top
            val title = visibleTextViews(manager.findViewByPosition(position)!!).first().text.toString()
            val appBarBottom = appBar.bottom
            val footer = list.findViewHolderForAdapterPosition(firstPage.size)!!.itemView
            val footerTop = footer.top
            fun assertReadingPosition() {
                assertSame(list, wordList(content))
                assertEquals(position, manager.findFirstVisibleItemPosition())
                assertEquals(id, list.adapter!!.getItemId(position))
                val firstRow = manager.findViewByPosition(position)!!
                assertEquals(title, visibleTextViews(firstRow).first().text.toString())
                assertEquals(top, firstRow.top)
                assertEquals(appBarBottom, appBar.bottom)
            }

            val more = visibleTextViews(footer).single { it.text == activity.getString(R.string.search_more_songs) }
            assertTrue(more.performClick())
            fixture.layout(content)
            assertReadingPosition()
            val progress = descendants(footer).filterIsInstance<LyricsLoadingView>().single { it.isShown }
            assertEquals(footerTop, footer.top)
            assertEquals(1, descendants(content).filterIsInstance<LyricsLoadingView>().count { it.isShown })

            fixture.finishRequests()
            fixture.layout(content)
            assertReadingPosition()
            if (appendRows) {
                assertSame(progress, descendants(footer).filterIsInstance<LyricsLoadingView>().single { it.isShown })
            }
            fixture.finishDiffs()
            fixture.layout(content)
            val expectedCount = firstPage.size + secondPage.size + 1
            assertEquals(expectedCount, list.adapter!!.itemCount)
            assertReadingPosition()
            assertFalse(descendants(content).filterIsInstance<LyricsLoadingView>().any { it.isShown })
            list.scrollToPosition(expectedCount - 1)
            fixture.layout(content)
            val end = visibleTextViews(list).single { it.text == activity.getString(R.string.search_no_more_results) }
            assertFalse(end.isClickable)
            assertFalse(visibleTextViews(list).any { it.text == activity.getString(R.string.search_more_songs) })
        }
    }

    private fun submitSearch(activity: LyricsSearchActivity, width: Int = 400) {
        fixture.layout(fixture.content(activity), width = width)
        assertTrue(fixture.button(activity, R.string.search_regular_search).performClick())
        fixture.finishRequests()
        fixture.layout(fixture.content(activity), width = width)
    }

    private fun selectRow(activity: LyricsSearchActivity, title: String, width: Int = 400) {
        assertTrue(fixture.row(activity, title).performClick())
        fixture.finishRequests()
        fixture.layout(fixture.content(activity), width = width)
        fixture.finishDiffs()
        fixture.layout(fixture.content(activity), width = width)
    }

    private fun setPreview(source: PlainLyricsSearchSource, song: SongIdentity, lyrics: LyricsProviderResult) {
        fixture.access.candidates = listOf(LyricsCandidate(source, "42", song, 1.0))
        fixture.access.fetchResult = ManualLyricsFetchResult.Available(lyrics)
    }

    private fun spotifyDialog(activity: LyricsSearchActivity): SpotifyIdSearchDialog {
        assertTrue(fixture.button(activity, R.string.search_try_spotify_id).performClick())
        activity.supportFragmentManager.executePendingTransactions()
        return activity.supportFragmentManager.findFragmentByTag(SpotifyIdSearchDialog.TAG) as SpotifyIdSearchDialog
    }

    private fun appBar(content: View) = descendants(content).filterIsInstance<AppBarLayout>().single()

    private fun wordList(content: View) = descendants(content).filterIsInstance<RecyclerView>()
        .single { it.isShown && it.adapter is ConcatAdapter }

    private fun visibleTextViews(content: View) = descendants(content).filterIsInstance<TextView>().filter { it.isShown }
}
