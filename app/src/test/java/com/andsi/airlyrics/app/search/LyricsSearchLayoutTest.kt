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
                .single { it.text.toString()==activity.getString(R.string.search_apply) }
            assertTrue(apply.isShown)
            assertTrue(apply.isEnabled)
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
