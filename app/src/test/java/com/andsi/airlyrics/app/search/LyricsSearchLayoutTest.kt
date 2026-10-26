package com.andsi.airlyrics.app.search

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.NestedScrollView
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.testutil.descendants
import com.google.android.material.appbar.AppBarLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LyricsSearchLayoutTest {
    @Test fun initialFormScrollIsBoundedAndCanReturnToTopAtDifferentHeights() {
        val intent=Intent(RuntimeEnvironment.getApplication(),LyricsSearchActivity::class.java)
            .putExtra("search.title","Song").putExtra("search.artist","Artist")
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val query=descendants(content).filterIsInstance<NestedScrollView>().single()
            val appBar=descendants(content).filterIsInstance<AppBarLayout>().single()
            for(height in listOf(1000,300,180,1000)) {
                content.measure(exact(400),exact(height))
                content.layout(0,0,400,height)
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

    @Test fun resultModeAndReturningToEmptyModeKeepTheSameForm() {
        val intent=Intent(RuntimeEnvironment.getApplication(),LyricsSearchActivity::class.java)
            .putExtra("search.title","Song")
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent).setup().visible().use { controller ->
            val activity=controller.get()
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val query=descendants(content).filterIsInstance<NestedScrollView>().single()
            val appBar=descendants(content).filterIsInstance<AppBarLayout>().single()
            val input=descendants(query).filterIsInstance<EditText>().first()
            input.setText("Edited query")
            val session=LyricsSearchActivity::class.java.getDeclaredField("session").apply { isAccessible=true }
                .get(activity) as LyricsSearchSession
            val render=LyricsSearchActivity::class.java.getDeclaredMethod("render",LyricsSearchState::class.java)
                .apply { isAccessible=true }
            val initial=session.state.value
            render.invoke(activity,initial.copy(results=mapOf(PlainLyricsSearchSource.NETEASE to SourceResults(loading=true))))
            assertEquals(View.GONE,query.visibility)
            assertEquals(View.VISIBLE,appBar.visibility)
            assertSame(input,descendants(appBar).filterIsInstance<EditText>().first())
            assertEquals(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL,
                (appBar.getChildAt(0).layoutParams as AppBarLayout.LayoutParams).scrollFlags)
            render.invoke(activity,initial)
            assertEquals(View.GONE,appBar.visibility)
            assertEquals(View.VISIBLE,query.visibility)
            assertSame(input,descendants(query).filterIsInstance<EditText>().first())
            assertEquals("Edited query",input.text.toString())
            assertEquals(0,session.resultsScroll)
        }
    }

    private fun exact(size:Int)=View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY)
}
