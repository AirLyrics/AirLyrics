package com.andsi.airlyrics.app.search

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.UntimedLyrics
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.descendants
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LyricsSearchLayoutTest {
    @Test fun formWithoutSourcesRemainsBoundedAtKeyboardHeights() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val initial=session(activity).state.value.copy(sources=emptySet())
            render(activity,initial)
            val query=query(activity)
            val appBar=descendants(content).filterIsInstance<AppBarLayout>().single()
            for(height in listOf(1000,300,180,1000)) {
                layout(content,height)
                assertEquals(View.GONE,appBar.visibility)
                assertEquals(View.VISIBLE,query.visibility)
                query.scrollTo(0,100_000)
                val range=(query.getChildAt(0).height-query.height).coerceAtLeast(0)
                assertEquals(range,query.scrollY)
                query.scrollTo(0,0)
                assertEquals(0,query.scrollY)
                assertTrue(descendants(query).filterIsInstance<EditText>().isNotEmpty())
            }
        }
    }

    @Test fun sourcePagesBeforeSearchAndReturningToNoSourcesKeepTheSameForm() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val appBar=descendants(content).filterIsInstance<AppBarLayout>().single()
            val initial=session(activity).state.value.copy(sources=emptySet())
            render(activity,initial)
            val query=query(activity)
            val input=descendants(query).filterIsInstance<EditText>().first()
            input.setText("Edited query")
            val edited=initial.copy(query=initial.query.copy(title="Edited query"))
            render(activity,edited.copy(sources=setOf(PlainLyricsSearchSource.SPICY)))
            for (height in listOf(1000,300,180,1000)) {
                layout(content,height)
                assertEquals(View.VISIBLE,appBar.visibility)
                assertEquals(View.GONE,query.visibility)
                assertSame(input,descendants(appBar).filterIsInstance<EditText>().first())
            }
            render(activity,edited)
            assertEquals(View.GONE,appBar.visibility)
            assertEquals(View.VISIBLE,query.visibility)
            assertSame(input,descendants(query).filterIsInstance<EditText>().first())
            assertEquals("Edited query",input.text.toString())
        }
    }

    @Test
    @Config(qualifiers="en-rUS-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun spicyIdEntryIsAvailableBeforeSearchAndOnlyExactRowsHaveBadge() {
        val app=RuntimeEnvironment.getApplication()
        ThemeSettingsStore.setDark(app, enabled = true)
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val spicy=PlainLyricsSearchSource.SPICY
            val state=session(activity).state.value.copy(sources=setOf(spicy))
            render(activity,state)
            layout(content,900)
            ShadowLooper.idleMainLooper(300,TimeUnit.MILLISECONDS)
            val action=descendants(content).filterIsInstance<MaterialButton>()
                .first { it.text.toString()==activity.getString(R.string.search_spotify_id) }
            assertTrue(action.isShown)
            action.performClick()
            activity.supportFragmentManager.executePendingTransactions()
            ShadowLooper.idleMainLooper(100,TimeUnit.MILLISECONDS)
            val fragment=activity.supportFragmentManager.findFragmentByTag(SpotifyIdSearchDialog.TAG) as SpotifyIdSearchDialog
            val dialog=fragment.requireDialog()
            val field=descendants(dialog.window!!.decorView).filterIsInstance<TextInputLayout>().single()
            field.editText!!.setText("spotify:episode:5l94Up12BJSjhH7Mhr2ji4")
            dialog.findViewById<View>(android.R.id.button1).performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(field.error)
            fragment.dismissNow()

            val song=SongIdentity("8bit Voyager","Kankitsu","Groundbreaking Compilation Album",140000)
            render(activity,state.copy(results=mapOf(spicy to SourceResults(candidates=listOf(
                LyricsCandidate(spicy,"5l94Up12BJSjhH7Mhr2ji4",song,0.0,spotifyIdMatch=true),
                LyricsCandidate(spicy,"0X5X6D3kZyDEG8Im1LQaVf",song.copy(title="8 Bit Voyager (Energy Sound Version)"),0.9)
            )))))
            layout(content,900)
            ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
            layout(content,900)
            val visibleTexts=descendants(content).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            assertTrue(visibleTexts.contains(song.title))
            assertTrue(visibleTexts.any { song.artist in it && "5l94Up12BJSjhH7Mhr2ji4" in it })
            val badges=descendants(content).filterIsInstance<TextView>()
                .filter { it.contentDescription==activity.getString(R.string.search_id_match) && it.visibility==View.VISIBLE }.toList()
            assertEquals(1,badges.size)
            val file=File(System.getProperty("java.io.tmpdir"),"airlyrics-spicy-id-preview.png")
            val bitmap=Bitmap.createBitmap(400,900,Bitmap.Config.ARGB_8888)
            content.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        ThemeSettingsStore.setDark(app,false)
    }

    @Test
    @Config(qualifiers="en-rUS-w400dp-h900dp-mdpi")
    fun spotifyPreviewKeepsTitleIdAndDisplaysTheActualTimingPrecision() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val state=session(activity).state.value
            val id="5l94Up12BJSjhH7Mhr2ji4"
            val song=SongIdentity("Fetched song","Fetched artist","Fetched album",10000)
            val candidate=LyricsCandidate(PlainLyricsSearchSource.SPICY,id,song,0.0,spotifyIdMatch=true)
            for ((type,label) in listOf("Line" to R.string.search_line_lyrics, "Syllable" to R.string.search_word_lyrics)) {
                val raw=org.json.JSONObject(com.andsi.airlyrics.lyrics.providers.spicyFixture(type)).apply {
                    getJSONObject("track").put("id",id)
                    getJSONObject("response").getJSONObject("Body").put("id",id)
                }
                val lyrics=com.andsi.airlyrics.lyrics.providers.SpicyLyricsParser.parse(raw.toString(),"key",100)!!
                render(activity,state.copy(preview=SearchPreview(candidate,lyrics),previewCandidate=candidate))
                layout(content,900)
                ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
                val texts=descendants(content).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
                assertTrue(texts.contains(song.title))
                assertTrue(texts.any { id in it })
                assertTrue(texts.contains(activity.getString(label)))
                val lyricText=descendants(content).filterIsInstance<TextView>()
                    .single { it.isShown && it.isTextSelectable }.text.toString()
                if (type=="Line") {
                    assertEquals(lyrics.plainLrc,lyricText)
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
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val state=session(activity).state.value
            val candidate=LyricsCandidate(PlainLyricsSearchSource.LRCLIB,"42",state.target,1.0)
            val lyrics=LyricsProviderResult("lrclib","LRCLIB","",
                untimedLyrics=UntimedLyrics("First line\n\n第二段","Translation\n\n译文"))
            render(activity,state.copy(preview=SearchPreview(candidate,lyrics),previewCandidate=candidate))
            layout(content,900)
            ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
            val texts=descendants(content).filterIsInstance<TextView>().toList()
            assertTrue(texts.any { it.isShown && it.text.toString()==activity.getString(R.string.ui_untimed_lyrics) })
            assertTrue(texts.any { it.isShown && it.text.toString()==activity.getString(R.string.search_untimed_notice) })
            assertTrue(texts.any { it.isShown && it.text.toString()==lyrics.untimedLyrics!!.text })
            assertTrue(texts.any { it.isShown && it.text.toString()==lyrics.untimedLyrics!!.translatedText })
            val apply=texts.filterIsInstance<MaterialButton>()
                .single { it.text.toString()==activity.getString(R.string.search_apply_current) }
            assertTrue(apply.isShown)
            assertTrue(apply.isEnabled)
        }
    }

    @Test fun wordSearchEntryUsesAnInputDialogAndKeepsTheCurrentActivity() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            render(activity,session(activity).state.value.copy(sources=setOf(PlainLyricsSearchSource.NETEASE)))
            layout(content,900)
            ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
            val action=descendants(content).filterIsInstance<MaterialButton>().single {
                it.isShown && it.text.toString()==activity.getString(R.string.search_word_search)
            }
            action.performClick()
            activity.supportFragmentManager.executePendingTransactions()
            assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
            val fragment=activity.supportFragmentManager.findFragmentByTag(WordLyricsSearchDialog.TAG) as WordLyricsSearchDialog
            val fields=descendants(fragment.requireDialog().window!!.decorView).filterIsInstance<TextInputLayout>()
            assertEquals("8bit Voyager",fields[0].editText!!.text.toString())
            assertEquals("Lime",fields[1].editText!!.text.toString())
            assertTrue(session(activity).state.value.results.isEmpty())
        }
    }

    @Test
    @Config(qualifiers="zh-rTW-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inlineWordResultsShowSuppliersAndKeepApplyReachable() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val initial=session(activity).state.value
            val words=listOf(com.andsi.airlyrics.lyrics.WordByWordLine(1000,2000,"你好",listOf(
                com.andsi.airlyrics.lyrics.WordByWordSegment("你",1000,1400),
                com.andsi.airlyrics.lyrics.WordByWordSegment("好",1400,2000))))
            val rows=com.andsi.airlyrics.lyrics.search.ManualWordSource.entries.map { source ->
                LyricsCandidate(PlainLyricsSearchSource.NETEASE,"words:${source.key}",initial.target,1.0,
                    wordVersion=com.andsi.airlyrics.lyrics.search.ManualWordVersion(
                        "42",source,initial.target,
                        LyricsProviderResult(source.key,source.key,"[00:01.000]你好",wordByWordLines=words)))
            }
            render(activity,initial.copy(sources=setOf(PlainLyricsSearchSource.NETEASE),results=mapOf(
                PlainLyricsSearchSource.NETEASE to SourceResults(candidates=rows,wordQuery=initial.target))))
            layout(content,900)
            ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
            val labels=descendants(content).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            assertTrue(labels.any { it.contains("酷狗") && it.contains("逐字") })
            assertTrue(labels.any { it.contains("QQ 音樂") && it.contains("逐字") })
            assertTrue(labels.contains(activity.getString(R.string.search_regular_search)))
            assertTrue(labels.contains(activity.getString(R.string.search_lyrics)))
            val bitmap=Bitmap.createBitmap(400,900,Bitmap.Config.ARGB_8888)
            content.draw(Canvas(bitmap))
            File("/tmp/airlyrics-inline-word-results-v2.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            val failed=initial.copy(sources=setOf(PlainLyricsSearchSource.NETEASE),results=mapOf(
                PlainLyricsSearchSource.NETEASE to SourceResults(
                    candidates=rows.filter { it.providerKey=="kugou" },wordQuery=initial.target,
                    wordResults=mapOf(com.andsi.airlyrics.lyrics.search.ManualWordSource.NETEASE to
                        WordSourceResults(error=IllegalStateException("parse failed"))))))
            render(activity,failed)
            layout(content,900)
            ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
            val errorViews=descendants(content).filterIsInstance<TextView>().filter { it.isShown }
            val retryLabel=activity.getString(R.string.search_retry)
            assertFalse(errorViews.filterIsInstance<MaterialButton>().any { it.text.toString()==retryLabel })
            val retryRow=errorViews.single { it.isClickable && it.text.toString().contains(retryLabel) }
            assertTrue(retryRow.height>=48)
            assertEquals(0f,retryRow.elevation,0.001f)
            assertTrue(errorViews.any { it.text.toString().contains("酷狗") })
            val errorBitmap=Bitmap.createBitmap(400,900,Bitmap.Config.ARGB_8888)
            content.draw(Canvas(errorBitmap))
            File("/tmp/airlyrics-word-inline-error.png").outputStream().use {
                errorBitmap.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            val chosen=rows.last()
            render(activity,initial.copy(previewCandidate=chosen,preview=SearchPreview(chosen,chosen.wordVersion!!.lyrics)))
            layout(content,900)
            ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
            val texts=descendants(content).filterIsInstance<TextView>().filter { it.isShown }
            assertTrue(texts.any { it.text.toString().contains("QQ 音樂") })
            val apply=texts.filterIsInstance<MaterialButton>().single {
                it.text.toString()==activity.getString(R.string.search_apply_current)
            }
            assertTrue(apply.isEnabled)
            assertTrue(apply.bottom<=900)
        }
    }

    @Test
    @Config(qualifiers="zh-rTW-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun recordingCatalogueAndIndependentTimingChoicesAreVisible() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val initial=session(activity).state.value
            val source=PlainLyricsSearchSource.NETEASE
            val supplier=com.andsi.airlyrics.lyrics.search.ManualWordSource.NETEASE
            val recording=com.andsi.airlyrics.lyrics.search.ManualWordRecording(supplier,"42",initial.target)
            val row=LyricsCandidate(source,"recording:42",initial.target,1.0,recording=recording)
            val catalogue=SourceResults(candidates=listOf(row),wordQuery=initial.target,
                wordResults=mapOf(supplier to WordSourceResults(recordings=listOf(recording),page=1,hasMore=true)))
            fun show(result:SourceResults,name:String) {
                render(activity,initial.copy(sources=setOf(source),results=mapOf(source to result)))
                layout(content,900)
                ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
                layout(content,900)
                val bitmap=Bitmap.createBitmap(400,900,Bitmap.Config.ARGB_8888)
                content.draw(Canvas(bitmap))
                File("/tmp/airlyrics-word-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                bitmap.recycle()
            }
            show(catalogue,"catalogue")
            var texts=descendants(content).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            assertFalse(texts.any { activity.getString(R.string.search_lyrics_unchecked) in it })
            assertEquals(1,texts.count { it==activity.getString(R.string.search_more_songs) })
            val line=com.andsi.airlyrics.lyrics.search.ManualWordVersion("42",supplier,initial.target,
                LyricsProviderResult("netease","netease","[00:01.000]你好"))
            val words=listOf(com.andsi.airlyrics.lyrics.WordByWordLine(1000,2000,"你好",listOf(
                com.andsi.airlyrics.lyrics.WordByWordSegment("你",1000,1400),
                com.andsi.airlyrics.lyrics.WordByWordSegment("好",1400,2000))))
            val word=line.copy(lyrics=line.lyrics.copy(wordByWordLines=words))
            val details=catalogue.copy(wordDetail=WordRecordingState(recording,
                details=com.andsi.airlyrics.lyrics.search.ManualWordDetails(listOf(line,word))),
                candidates=listOf(line,word).map { LyricsCandidate(source,it.key,it.song,1.0,wordVersion=it) })
            show(details,"versions")
            texts=descendants(content).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            assertTrue(texts.any { activity.getString(R.string.search_version_line) in it })
            assertTrue(texts.any { activity.getString(R.string.search_version_word) in it })
            assertTrue(texts.contains(activity.getString(R.string.search_back_recordings)))
            val versionsList=descendants(content).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single {
                it.isShown && it.adapter is androidx.recyclerview.widget.ConcatAdapter
            }
            // The recording detail contains its lyric choices, with no cross-source action or catalogue footer.
            assertEquals(2,versionsList.adapter!!.itemCount)
        }
    }

    @Test
    @Config(qualifiers="en-rUS-w400dp-h900dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun catalogueKeepsPaginationAtTheEndAndRecordingHintsExpandInPlace() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val source=PlainLyricsSearchSource.NETEASE
            val supplier=com.andsi.airlyrics.lyrics.search.ManualWordSource.NETEASE
            val initial=session(activity).state.value
            val recordings=(1..30).map { n -> com.andsi.airlyrics.lyrics.search.ManualWordRecording(
                supplier,"$n",initial.target.copy(album="Album $n"),requiresConfirmation=n==1) }
            val rows=recordings.map { LyricsCandidate(source,it.key,it.song,1.0,recording=it) }
            render(activity,initial.copy(sources=setOf(source),results=mapOf(source to SourceResults(
                candidates=rows,wordQuery=initial.target,wordResults=mapOf(supplier to WordSourceResults(
                    recordings=recordings,page=1,hasMore=true))))))
            layout(content,900);ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS);layout(content,900)
            fun texts()=descendants(content).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
            fun capture(name:String) {
                val bitmap=Bitmap.createBitmap(400,900,Bitmap.Config.ARGB_8888)
                content.draw(Canvas(bitmap))
                File("/tmp/airlyrics-search-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            descendants(content).filterIsInstance<AppBarLayout>().single().setExpanded(false,false)
            layout(content,900);ShadowLooper.idleMainLooper(100,TimeUnit.MILLISECONDS);layout(content,900)
            assertFalse(texts().contains(activity.getString(R.string.search_more_songs)))
            assertFalse(texts().contains(activity.getString(R.string.search_version_check)))
            capture("clean-catalogue")
            val fragments=activity.supportFragmentManager.fragments.size
            val info=descendants(content).filterIsInstance<android.widget.ImageButton>().single {
                it.isShown && it.contentDescription==activity.getString(R.string.search_recording_hint)
            }
            info.performClick();ShadowLooper.idleMainLooper(100,TimeUnit.MILLISECONDS);layout(content,900)
            assertTrue(texts().contains(activity.getString(R.string.search_version_check)))
            assertEquals(fragments,activity.supportFragmentManager.fragments.size)
            capture("inline-hint")
            info.performClick();ShadowLooper.idleMainLooper(100,TimeUnit.MILLISECONDS)
            assertFalse(texts().contains(activity.getString(R.string.search_version_check)))
            val list=descendants(content).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single {
                it.adapter is androidx.recyclerview.widget.ConcatAdapter && it.isShown
            }
            list.scrollToPosition(list.adapter!!.itemCount-1)
            layout(content,900);ShadowLooper.idleMainLooper(100,TimeUnit.MILLISECONDS);layout(content,900)
            assertEquals(1,texts().count { it==activity.getString(R.string.search_more_songs) })
            val more=descendants(content).filterIsInstance<TextView>().single {
                it.isShown && it.text.toString()==activity.getString(R.string.search_more_songs)
            }
            val bounds=android.graphics.Rect()
            assertTrue(more.getGlobalVisibleRect(bounds))
            assertTrue(bounds.height()>=48)
            capture("clean-footer")
        }
    }

    private fun intent()=Intent(RuntimeEnvironment.getApplication(),LyricsSearchActivity::class.java)
        .putExtra("search.title","8bit Voyager").putExtra("search.artist","Lime")
    private fun session(activity:LyricsSearchActivity)=LyricsSearchActivity::class.java.getDeclaredField("session")
        .apply { isAccessible=true }.get(activity) as LyricsSearchSession
    private fun query(activity:LyricsSearchActivity)=LyricsSearchActivity::class.java.getDeclaredField("queryScroll")
        .apply { isAccessible=true }.get(activity) as NestedScrollView
    private fun render(activity:LyricsSearchActivity,state:LyricsSearchState) {
        LyricsSearchActivity::class.java.getDeclaredMethod("render",LyricsSearchState::class.java)
            .apply { isAccessible=true }.invoke(activity,state)
    }
    private fun layout(view:View,height:Int) {
        val width = 400
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
}
