package com.andsi.airlyrics.app.search

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.LyricsUnavailableReason
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.viewpager2.widget.ViewPager2
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.providers.ManualLyricsNative
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsSearchInstrumentedTest {
    private lateinit var originalSources: List<PlainLyricsSearchSource>

    @Before fun configureSearchSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        originalSources = LyricsSettingsStore.getPlainLyricsSearchSources(context)
        LyricsSettingsStore.setPlainLyricsSearchSources(context, listOf(PlainLyricsSearchSource.NETEASE))
    }

    @After fun restoreSearchSources() {
        if (::originalSources.isInitialized) {
            LyricsSettingsStore.setPlainLyricsSearchSources(
                InstrumentationRegistry.getInstrumentation().targetContext, originalSources
            )
        }
    }

    @Test fun searchStaysDisabledWithoutSelectedSourcesAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LyricsSettingsStore.setPlainLyricsSearchSources(context, emptyList())
        val intent = Intent(context, LyricsSearchActivity::class.java)
            .putExtra("search.title", "Song").putExtra("search.artist", "Artist")
        ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
            fun assertDisabled() = scenario.onActivity { activity ->
                val button = descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<com.google.android.material.button.MaterialButton>()
                    .single { it.text.toString() == activity.getString(R.string.ui_search) }
                assertFalse("Search requires a selected source", button.isEnabled)
            }
            assertDisabled()
            scenario.recreate()
            assertDisabled()
        }
    }

    @Test fun manualJniRejectsUnknownProviderAndMissingCredentialsWithoutNetwork() {
        val request=JSONObject().put("operation","search").put("provider","unknown")
            .put("track",JSONObject().put("id","").put("title","Song").put("artist","Artist").put("album","").put("duration_ms",200000))
        val unknown=JSONObject(ManualLyricsNative.requestJson(request.toString(),"",0))
        assertFalse(unknown.getBoolean("ok"))
        assertEquals("ParseError",unknown.getString("error_type"))
        request.put("provider","spicy")
        val credentials=JSONObject(ManualLyricsNative.requestJson(request.toString(),"",0))
        assertEquals("NeedCredential",credentials.getString("error_type"))
    }
    @Test fun searchWindowRetainsEditedQueryAcrossRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val intent=Intent(context,LyricsSearchActivity::class.java)
            .putExtra("search.title","Original song").putExtra("search.artist","Original artist")
            .putExtra("search.album","Album").putExtra("search.duration",200000L)
        ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                val fields=views.filterIsInstance<EditText>()
                assertEquals("Original song",fields[0].text.toString())
                fields[0].setText("Edited query")
                fields[1].setText("Edited artist")
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                val fields=views.filterIsInstance<EditText>()
                assertEquals("Edited query",fields[0].text.toString())
                assertEquals("Edited artist",fields[1].text.toString())
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("Original song") })
                assertTrue("Search must remain enabled with an edited query and a configured source",
                    views.filterIsInstance<TextView>().any { it.text.toString()==activity.getString(R.string.ui_search) && it.isEnabled })
            }
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                scenario.onActivity { it.requestedOrientation = orientation }
                val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                    Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                var ready = false
                val deadline = SystemClock.uptimeMillis() + 5000
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { ready = it.resources.configuration.orientation == expected }
                    if (!ready) SystemClock.sleep(100)
                }
                assertTrue("Orientation did not settle", ready)
                scenario.onActivity { activity ->
                    val fields = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                    assertEquals("Edited query", fields[0].text.toString())
                }
            }
            // Surface rotation animations finish after the Activity reports its new configuration.
            SystemClock.sleep(700)
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                PlatformTestStorageRegistry.getInstance().openOutputFile("manual-search.png").use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
            }
        }
    }
    @Test fun appleCandidateAndWordTranslationPreviewSurviveRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LyricsSettingsStore.setPlainLyricsSearchSources(context, listOf(PlainLyricsSearchSource.APPLE))
        val song = SongIdentity("Apple manual preview", "Artist", "Album", 200000)
        val candidate = LyricsCandidate(PlainLyricsSearchSource.APPLE, "42", song, 0.9, "tr")
        val raw = """{"ok":true,"id":"42","lrc":"[00:01.00]Hello","translated_lrc":"[00:01.00]你好",
            "word_by_word":[{"start_ms":1000,"end_ms":2000,"text":"Hello","translated_text":"你好",
            "segments":[{"text":"Hello","start_ms":1000,"end_ms":2000}]}]}"""
        val search = com.andsi.airlyrics.lyrics.search.ManualLyricsSearch(context) { _, _ -> raw }
        val result = search.fetch(candidate, song, com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken(song.storageKey(), 1))
            as com.andsi.airlyrics.lyrics.search.ManualLyricsFetchResult.Available
        val intent = Intent(context, LyricsSearchActivity::class.java)
            .putExtra("search.title", song.title).putExtra("search.artist", song.artist)
        ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val session = session(activity)
                assertTrue(session.sourceOrder.contains(PlainLyricsSearchSource.APPLE))
                state(session).value = session.state.value.copy(
                    results = mapOf(PlainLyricsSearchSource.APPLE to SourceResults(candidates = listOf(candidate))),
                    previewCandidate = candidate, preview = SearchPreview(candidate, result.lyrics)
                )
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("tr", session(activity).state.value.previewCandidate?.storefront)
                val texts = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                assertTrue(texts.any { it.text == activity.getString(R.string.search_word_lyrics) })
                assertTrue(texts.any { it.text.toString().contains("你好") })
                assertTrue(texts.any { it.text == activity.getString(R.string.search_apply) && it.isEnabled })
            }
        }
    }

    @Test fun sourcePagesAndEmptyPreviewSurviveRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val intent=Intent(context,LyricsSearchActivity::class.java)
            .putExtra("search.title","Original song").putExtra("search.artist","Artist")
        val first=PlainLyricsSearchSource.NETEASE
        val second=PlainLyricsSearchSource.LRCLIB
        val a=LyricsCandidate(first,"42",SongIdentity("NetEase candidate","Artist","",200000),1.0)
        val previewSong=SongIdentity("LRCLIB candidate ".repeat(8).trim(),"Artist","",200000)
        val b=LyricsCandidate(second,"42",previewSong.copy(album=previewSong.title),1.0)
        ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val session=session(activity)
                LyricsSearchActivity::class.java.getDeclaredField("pendingSourceFocus").apply {
                    isAccessible=true
                }.setBoolean(activity,true)
                state(session).value=session.state.value.copy(results=linkedMapOf(
                    first to SourceResults(candidates=listOf(a)),
                    second to SourceResults(candidates=listOf(b))
                ))
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(700)
            scenario.onActivity { activity ->
                val appBar=descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<com.google.android.material.appbar.AppBarLayout>().single()
                assertEquals("Source navigation must reach the top",0,appBar.bottom)
                val pager=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<ViewPager2>().single()
                assertEquals(2,pager.adapter!!.itemCount)
                pager.setCurrentItem(1,false)
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val pager=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<ViewPager2>().single()
                assertEquals(1,pager.currentItem)
                val session=session(activity)
                state(session).value=session.state.value.copy(previewCandidate=b)
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val texts=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                val title=texts.single { it.isShown && it.text.toString()==b.song.title }
                assertFalse(texts.any { it!==title && it.isShown && it.text.toString().contains(b.song.title) })
                assertFalse(texts.any { it.isShown && it.text==activity.getString(R.string.search_lyrics) })
                assertFalse(texts.any { it.isShown && it.text.toString().contains("Original song") })
                val titleScroll=title.parent as android.widget.HorizontalScrollView
                assertTrue(titleScroll.canScrollHorizontally(1))
                titleScroll.scrollTo(title.width,0)
                assertTrue(titleScroll.scrollX>0)
                assertTrue(texts.any { it.isShown && it.text==activity.getString(R.string.search_no_content) })
                assertTrue(texts.any { it.isShown && it.text==activity.getString(R.string.search_apply) && !it.isEnabled })
                val back=descendants(activity.findViewById(android.R.id.content))
                    .single { it.contentDescription==activity.getString(R.string.search_back) }
                assertTrue(back is android.widget.ImageButton)
                back.performClick()
                assertFalse(activity.isFinishing)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val pager=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<ViewPager2>().single()
                assertTrue(pager.isShown)
                assertEquals(1,pager.currentItem)
                assertTrue(descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().any {
                    it.text==activity.getString(R.string.search_lyrics)
                })
                val appBar=descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<com.google.android.material.appbar.AppBarLayout>().single()
                assertEquals(0,appBar.bottom)
            }
            val metrics=context.resources.displayMetrics
            val downTime=SystemClock.uptimeMillis()
            for(step in 0..20) {
                val action=when(step) {
                    0 -> android.view.MotionEvent.ACTION_DOWN
                    20 -> android.view.MotionEvent.ACTION_UP
                    else -> android.view.MotionEvent.ACTION_MOVE
                }
                val event=android.view.MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,
                    metrics.widthPixels*0.5f,metrics.heightPixels*(0.3f+0.6f*step/20),0)
                instrumentation.sendPointerSync(event)
                event.recycle()
                SystemClock.sleep(20)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            scenario.onActivity { activity ->
                val appBar=descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<com.google.android.material.appbar.AppBarLayout>().single()
                assertEquals(0,appBar.top)
                assertTrue(descendants(appBar).filterIsInstance<EditText>().take(2).all { it.isShown })
                appBar.setExpanded(false,false)
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                PlatformTestStorageRegistry.getInstance().openOutputFile("manual-source-pages.png").use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
            }
        }
    }
    @Test fun unavailableReasonsAreDistinctAndSurviveRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val candidate=LyricsCandidate(PlainLyricsSearchSource.NETEASE,"42",SongIdentity("Song","Artist","",200000),1.0)
        val messages=mapOf(
            LyricsUnavailableReason.SOURCE_EMPTY to R.string.search_source_empty,
            LyricsUnavailableReason.INSTRUMENTAL to R.string.search_no_content,
            LyricsUnavailableReason.NO_TIMELINE to R.string.search_no_timeline,
            LyricsUnavailableReason.UNSUPPORTED_FORMAT to R.string.search_unsupported_format,
            LyricsUnavailableReason.NO_USABLE_LINES to R.string.search_no_usable_lines,
            LyricsUnavailableReason.UNKNOWN to R.string.search_no_content
        )
        ActivityScenario.launch<LyricsSearchActivity>(Intent(context,LyricsSearchActivity::class.java).putExtra("search.title","Song")).use { scenario ->
            for ((reason,message) in messages) {
                scenario.onActivity { activity ->
                    val session=session(activity)
                    state(session).value=session.state.value.copy(previewCandidate=candidate,previewUnavailableReason=reason)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val texts=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                    assertTrue(texts.any { it.isShown && it.text==activity.getString(message) })
                    assertFalse(messages.values.filter { it!=message }.any { other -> texts.any { it.isShown && it.text==activity.getString(other) } })
                    assertTrue(texts.any { it.isShown && it.text==activity.getString(R.string.search_apply) && !it.isEnabled })
                }
            }
            scenario.onActivity { activity ->
                val session=session(activity)
                state(session).value=session.state.value.copy(previewUnavailableReason=LyricsUnavailableReason.NO_TIMELINE)
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val texts=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                assertTrue(texts.any { it.isShown && it.text==activity.getString(R.string.search_no_timeline) })
                session(activity).backToResults()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNull(session(activity).state.value.previewUnavailableReason)
                val texts=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
                assertFalse(texts.any { it.isShown && it.text==activity.getString(R.string.search_no_timeline) })
            }
        }
    }

    @Test fun loadingMotionReplacesTextAndClearsWhenPreviewFails() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val intent=Intent(context,LyricsSearchActivity::class.java).putExtra("search.title","Song")
        val source=PlainLyricsSearchSource.NETEASE
        val candidate=LyricsCandidate(source,"42",SongIdentity("Song","Artist","Song",200000),1.0)
        ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val session=session(activity)
                state(session).value=session.state.value.copy(results=mapOf(source to SourceResults(loading=true)))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                assertTrue(views.filterIsInstance<LyricsLoadingView>().any { it.isShown })
                assertFalse(views.filterIsInstance<TextView>().any { it.isShown && it.text==activity.getString(R.string.ui_loading) })
                val session=session(activity)
                state(session).value=session.state.value.copy(
                    results=mapOf(source to SourceResults(candidates=listOf(candidate))),
                    previewCandidate=candidate,previewLoading=true
                )
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                assertEquals(1,views.filterIsInstance<LyricsLoadingView>().count { it.isShown })
            }
            SystemClock.sleep(450)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                PlatformTestStorageRegistry.getInstance().openOutputFile("manual-lyrics-loading.png").use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
            }
            scenario.onActivity { activity ->
                val session=session(activity)
                state(session).value=session.state.value.copy(previewLoading=false,error=IllegalStateException("offline"))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                assertFalse(views.filterIsInstance<LyricsLoadingView>().any { it.isShown })
                assertTrue(views.filterIsInstance<TextView>().any { it.isShown && it.text==activity.getString(R.string.search_failed) })
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test fun backFromResultsExpandsQueryBeforeLeavingSearch() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val intent=Intent(context,LyricsSearchActivity::class.java).putExtra("search.title","Original")
        val source=PlainLyricsSearchSource.LRCLIB
        val candidate=LyricsCandidate(source,"42",SongIdentity("Candidate","Artist","",200000),1.0)
        ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val session=session(activity)
                session.editQuery("Edited query","Artist","")
                state(session).value=session.state.value.copy(
                    results=mapOf(source to SourceResults(candidates=listOf(candidate))),
                    submittedQuery=session.state.value.query
                )
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<com.google.android.material.appbar.AppBarLayout>().single().setExpanded(false,false)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertTrue(session(activity).resultsScroll>0)
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse(activity.isFinishing)
                // A repeated back during expansion must not accidentally close the window.
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse(activity.isFinishing)
            }
            SystemClock.sleep(700)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                val appBar=views.filterIsInstance<com.google.android.material.appbar.AppBarLayout>().single()
                assertEquals(0,appBar.top)
                assertEquals("Edited query",views.filterIsInstance<EditText>().first().text.toString())
                assertEquals(source,session(activity).activeSource)
                assertEquals(listOf(candidate),session(activity).state.value.results[source]!!.candidates)
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val views=descendants(activity.findViewById(android.R.id.content))
                assertEquals(0,views.filterIsInstance<com.google.android.material.appbar.AppBarLayout>().single().top)
                views.single { it.contentDescription==activity.getString(R.string.search_back) }.performClick()
                assertTrue(activity.isFinishing)
            }
        }
    }

    @Test fun sourceSheetKeepsDraftAcrossRotationAndOnlyCommitsOnConfirm() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val original=LyricsSettingsStore.getPlainLyricsSearchSources(context)
        val initial=setOf(PlainLyricsSearchSource.NETEASE)
        val added=PlainLyricsSearchSource.LRCLIB
        val intent=Intent(context,LyricsSearchActivity::class.java).putExtra("search.title","Song")
        try {
            LyricsSettingsStore.setPlainLyricsSearchSources(context,initial.toList())
            ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(initial,session(activity).state.value.sources)
                    LyricsSourcesSheet.create(initial).showNow(activity.supportFragmentManager,LyricsSourcesSheet.TAG)
                    val sheet=activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
                    descendants(sheet.requireDialog().window!!.decorView).single {
                        it.contentDescription==activity.localizedPlainLyricsProviderName(added.key)
                    }.performClick()
                    assertEquals(initial,session(activity).state.value.sources)
                }
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val sheet=activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
                    val backing=sheet.requireDialog().findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    assertNull(backing.background)
                    assertNull(backing.backgroundTintList)
                    assertEquals(0f,backing.elevation)
                    val views=descendants(sheet.requireDialog().window!!.decorView)
                    assertTrue(views.single { it.contentDescription==activity.localizedPlainLyricsProviderName(added.key) }.isSelected)
                    assertEquals(initial,session(activity).state.value.sources)
                }
                SystemClock.sleep(350)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    PlatformTestStorageRegistry.getInstance().openOutputFile("manual-source-picker.png").use {
                        bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                    bitmap.recycle()
                }
                scenario.onActivity { activity ->
                    val sheet=activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
                    descendants(sheet.requireDialog().window!!.decorView).filterIsInstance<TextView>().single {
                        it.text==activity.getString(R.string.search_sources_use,2)
                    }.performClick()
                    assertEquals(initial+added,session(activity).state.value.sources)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    LyricsSourcesSheet.create(initial+added).showNow(activity.supportFragmentManager,LyricsSourcesSheet.TAG)
                    val sheet=activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
                    val views=descendants(sheet.requireDialog().window!!.decorView)
                    for(source in initial+added) {
                        views.single { it.contentDescription==activity.localizedPlainLyricsProviderName(source.key) }.performClick()
                    }
                    assertFalse(views.filterIsInstance<TextView>().single {
                        it.text==activity.getString(R.string.search_sources_use,0)
                    }.isEnabled)
                    sheet.dismissNow()
                    assertEquals(initial+added,session(activity).state.value.sources)
                }
            }
            // A new search window follows automatic source settings, not the last manual choice.
            ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
                scenario.onActivity { activity -> assertEquals(initial,session(activity).state.value.sources) }
            }
            LyricsSettingsStore.setPlainLyricsSearchSources(context,listOf(PlainLyricsSearchSource.SPICY))
            ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(setOf(PlainLyricsSearchSource.SPICY),session(activity).state.value.sources)
                }
            }
        } finally { LyricsSettingsStore.setPlainLyricsSearchSources(context,original) }
    }

    @Test fun fieldResetIconsOnlyShowForChangedFieldsAndRestoreIndependently() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val intent=Intent(context,LyricsSearchActivity::class.java)
            .putExtra("search.title","Song").putExtra("search.artist","Artist")
        val descriptions=listOf(R.string.search_restore_title,R.string.search_restore_artist,R.string.search_restore_album)
        fun icons(activity:LyricsSearchActivity)=descriptions.map { description ->
            descendants(activity.findViewById(android.R.id.content)).single { it.contentDescription==activity.getString(description) }
        }
        ActivityScenario.launch<LyricsSearchActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(icons(activity).none { it.isShown })
                session(activity).toggleMore()
                val fields=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                fields[0].setText("Edited title")
                fields[1].setText("Edited artist")
                fields[2].setText("Edited album")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertTrue(icons(activity).all { it.isShown && it.isEnabled }) }
            SystemClock.sleep(400)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                PlatformTestStorageRegistry.getInstance().openOutputFile("manual-field-resets.png").use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
            }
            scenario.onActivity { activity -> icons(activity)[1].performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fields=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                assertEquals(listOf("Edited title","Artist","Edited album"),fields.map { it.text.toString() })
                assertFalse(icons(activity)[1].isEnabled)
                assertEquals(fields[1].length(),fields[1].selectionStart)
                assertEquals(fields[1].length(),fields[1].selectionEnd)
                fields[0].setText("Song")
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(350)
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(listOf(false,false,true),icons(activity).map { it.isShown })
                icons(activity)[2].performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fields=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                assertEquals(listOf("Song","Artist",""),fields.map { it.text.toString() })
                assertEquals(0,fields[2].selectionStart)
                assertFalse(icons(activity)[2].isEnabled)
                assertTrue(session(activity).state.value.results.isEmpty())
            }
            SystemClock.sleep(350)
            scenario.onActivity { activity ->
                assertTrue(icons(activity).none { it.isShown })
                val field=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()[0]
                field.setText("Another title")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> icons(activity)[0].performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val field=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()[0]
                assertEquals(field.length(),field.selectionStart)
                field.setText("Edited during dismissal")
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(450)
            scenario.onActivity { activity ->
                assertTrue(icons(activity)[0].isShown)
                assertTrue(icons(activity)[0].isEnabled)
                assertEquals(1f,icons(activity)[0].alpha)
                assertEquals(1f,icons(activity)[0].scaleX)
            }
        }
    }

    @Test fun configuredOrderIsSharedBySourceSheetAndResultPages() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val original=LyricsSettingsStore.getPlainLyricsSearchSources(context)
        val order=listOf(PlainLyricsSearchSource.SPICY,PlainLyricsSearchSource.LRCLIB,PlainLyricsSearchSource.NETEASE)
        val all=order+PlainLyricsSearchSource.MUSIXMATCH
        try {
            LyricsSettingsStore.setPlainLyricsSearchSources(context,order)
            ActivityScenario.launch<LyricsSearchActivity>(Intent(context,LyricsSearchActivity::class.java).putExtra("search.title","Song")).use { scenario ->
                scenario.onActivity { activity ->
                    val session=session(activity)
                    assertEquals(order,session.state.value.sources.toList())
                    LyricsSourcesSheet.create(session.state.value.sources,session.sourceOrder).showNow(activity.supportFragmentManager,LyricsSourcesSheet.TAG)
                }
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val sheet=activity.supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) as LyricsSourcesSheet
                    val names=all.map { activity.localizedPlainLyricsProviderName(it.key) }
                    val views=descendants(sheet.requireDialog().window!!.decorView)
                    assertEquals(names,views.mapNotNull { it.contentDescription?.toString()?.takeIf { name -> name in names } })
                    val first=views.single { it.contentDescription==names.first() }
                    first.performClick()
                    first.performClick()
                    views.filterIsInstance<TextView>().single { it.text==activity.getString(R.string.search_sources_use,3) }.performClick()
                    val session=session(activity)
                    assertEquals(order,session.state.value.sources.toList())
                    state(session).value=session.state.value.copy(results=order.associateWith { SourceResults(loading=true) })
                }
                instrumentation.waitForIdleSync()
                fun assertTabs(activity:LyricsSearchActivity) {
                    val tabs=descendants(activity.findViewById(android.R.id.content)).filterIsInstance<com.google.android.material.tabs.TabLayout>().single()
                    assertEquals(order.size,tabs.tabCount)
                    for ((index,source) in order.withIndex()) {
                        val label=descendants(tabs.getTabAt(index)!!.customView!!).filterIsInstance<TextView>().single().text.toString()
                        assertTrue(label.startsWith(activity.localizedPlainLyricsProviderName(source.key)))
                    }
                }
                scenario.onActivity { activity ->
                    assertTabs(activity)
                    val session=session(activity)
                    state(session).value=session.state.value.copy(results=session.state.value.results+(order.last() to SourceResults()))
                }
                instrumentation.waitForIdleSync()
                scenario.recreate()
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> assertTabs(activity) }
            }
        } finally { LyricsSettingsStore.setPlainLyricsSearchSources(context,original) }
    }

    // Seed deterministic network states without introducing production test hooks.
    private fun session(activity:LyricsSearchActivity):LyricsSearchSession =
        LyricsSearchActivity::class.java.getDeclaredField("session").apply { isAccessible=true }
            .get(activity) as LyricsSearchSession
    @Suppress("UNCHECKED_CAST")
    private fun state(session:LyricsSearchSession):MutableStateFlow<LyricsSearchState> =
        LyricsSearchSession::class.java.getDeclaredField("mutable").apply { isAccessible=true }
            .get(session) as MutableStateFlow<LyricsSearchState>

    private fun descendants(view:View):List<View> = listOf(view)+(if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
}
