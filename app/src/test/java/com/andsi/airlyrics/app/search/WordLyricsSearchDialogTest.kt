package com.andsi.airlyrics.app.search

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.descendants
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
@Config(qualifiers="zh-rTW-w400dp-h900dp-mdpi")
class WordLyricsSearchDialogTest {
    private fun pauseCloseFrames() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16))
    }
    @org.junit.After fun restoreAnimationClock() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
    }

    private val song=SongIdentity("我的悲傷是水做的 日語版","Hanser","Album",225000)

    @Test fun prefillsMetadataValidatesTitleAndSubmitsOnlyOnce() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val results=mutableListOf<android.os.Bundle>()
            activity.supportFragmentManager.setFragmentResultListener(WordLyricsSearchDialog.RESULT,activity) { _,result ->
                results+=result
            }
            val dialog=show(activity).requireDialog() as LyricsSearchInputDialog
            settle()
            val fields=fields(dialog)
            assertEquals(song.title,fields[0].editText!!.text.toString())
            assertEquals(song.artist,fields[1].editText!!.text.toString())
            fields[0].editText!!.setText("  ")
            dialog.findViewById<View>(android.R.id.button1).performClick()
            assertNotNull(fields[0].error)
            assertTrue(results.isEmpty())
            fields[0].editText!!.setText(" Edited title ")
            fields[1].editText!!.setText(" Singer ")
            pauseCloseFrames()
            dialog.findViewById<View>(android.R.id.button1).performClick()
            dialog.findViewById<View>(android.R.id.button1).performClick()
            val result=results.single()
            assertEquals("Edited title",result.getString(WordLyricsSearchDialog.TITLE))
            assertEquals("Singer",result.getString(WordLyricsSearchDialog.ARTIST))
            assertEquals(0L,result.getLong(WordLyricsSearchDialog.DURATION))
            assertEquals("",result.getString(WordLyricsSearchDialog.ALBUM))
            assertEquals(song.storageKey(),result.getString(WordLyricsSearchDialog.TARGET))
            assertTrue(dialog.isLeaving)
            ShadowLooper.idleMainLooper(64,TimeUnit.MILLISECONDS)
            assertTrue(dialog.isShowing)
            assertTrue(dialog.card.alpha in 0.001f..0.999f)
            assertTrue(dialog.card.scaleX < 1f)
            assertTrue(dialog.card.translationY > 0f)
            settle()
            assertFalse(dialog.isShowing)
            assertEquals(0f,activity.interactionUi.maxShowingDimAmount(),0.001f)
        }
    }
    @Test fun recreationRestoresBothDraftFieldsWithoutSubmitting() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val old=show(controller.get()).requireDialog() as LyricsSearchInputDialog
            fields(old)[0].editText!!.setText("Draft title")
            fields(old)[1].editText!!.setText("Draft singer")
            controller.recreate()
            settle()
            assertFalse(old.isShowing)
            val restored=controller.get().supportFragmentManager.findFragmentByTag(WordLyricsSearchDialog.TAG) as WordLyricsSearchDialog
            val dialog=restored.requireDialog() as LyricsSearchInputDialog
            assertEquals("Draft title",fields(dialog)[0].editText!!.text.toString())
            assertEquals("Draft singer",fields(dialog)[1].editText!!.text.toString())
            assertTrue(session(controller.get()).state.value.results.isEmpty())
        }
    }
    @Test fun explicitAlbumAndDurationSurviveRecreationAndInvalidDurationCannotSubmit() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val old=show(controller.get()).requireDialog()
            fields(old)[2].editText!!.setText("Selected album")
            fields(old)[3].editText!!.setText("999999999999999999999")
            old.findViewById<View>(android.R.id.button1).performClick()
            assertNotNull(fields(old)[3].error)
            assertTrue(session(controller.get()).state.value.results.isEmpty())
            fields(old)[3].editText!!.setText("3:45")
            controller.recreate();settle()
            val restored=(controller.get().supportFragmentManager.findFragmentByTag(WordLyricsSearchDialog.TAG) as WordLyricsSearchDialog).requireDialog()
            assertEquals("Selected album",fields(restored)[2].editText!!.text.toString())
            assertEquals("3:45",fields(restored)[3].editText!!.text.toString())
        }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun optionalMatchingAndDurationHelpExpandInlineAndUseMinutesSeconds() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val replies=mutableListOf<android.os.Bundle>()
            activity.supportFragmentManager.setFragmentResultListener(WordLyricsSearchDialog.RESULT,activity) { _,r -> replies+=r }
            val dialog=show(activity).requireDialog()
            settle()
            val views=descendants(dialog.window!!.decorView)
            assertFalse(views.filterIsInstance<TextView>().any { it.isShown && it.text.toString()==activity.getString(R.string.search_use_current) })
            assertFalse(fields(dialog)[3].isShown)
            views.filterIsInstance<com.google.android.material.button.MaterialButton>().single {
                it.text.toString()==activity.getString(R.string.search_match_options)
            }.performClick()
            assertTrue(fields(dialog)[3].isShown)
            val duration=fields(dialog)[3]
            assertNull(duration.helperText)
            val fragments=activity.supportFragmentManager.fragments.size
            val info=duration.findViewById<View>(com.google.android.material.R.id.text_input_end_icon)
            info.performClick()
            assertEquals(activity.getString(R.string.search_duration_help),duration.helperText.toString())
            assertEquals(fragments,activity.supportFragmentManager.fragments.size)
            layout(dialog.window!!.decorView,400,900);settle();layout(dialog.window!!.decorView,400,900)
            val card=(dialog as LyricsSearchInputDialog).card
            val bitmap=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888)
            card.draw(Canvas(bitmap))
            File("/tmp/airlyrics-word-dialog-options.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
            info.performClick()
            assertNull(duration.helperText)
            duration.editText!!.setText("4:02")
            dialog.findViewById<View>(android.R.id.button1).performClick()
            assertEquals(242000L,replies.single().getLong(WordLyricsSearchDialog.DURATION))
        }
    }

    @Test fun cancelUsesTheSameWindowAndPreservesUnderlyingDimming() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val sheet=LyricsSourcesSheet.create(setOf(PlainLyricsSearchSource.NETEASE))
            sheet.showNow(activity.supportFragmentManager,LyricsSourcesSheet.TAG)
            settle()
            val shade=sheet.requireDialog().window!!.attributes.dimAmount
            val dialog=show(activity).requireDialog() as LyricsSearchInputDialog
            settle()
            assertEquals(shade,dialog.window!!.attributes.dimAmount,0.001f)
            dialog.cancel()
            settle()
            assertFalse(dialog.isShowing)
            assertTrue(sheet.requireDialog().isShowing)
            assertTrue(session(activity).state.value.results.isEmpty())
        }
    }
    @Test fun aDialogForAnOldTargetCannotStartAQueryForTheNewTarget() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val dialog=show(activity).requireDialog()
            session(activity).switchTarget(song.copy(title="Next song"))
            dialog.findViewById<View>(android.R.id.button1).performClick()
            settle()
            assertTrue(session(activity).state.value.results.isEmpty())
            assertEquals("Next song",session(activity).state.value.target.title)
        }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun dialogFitsInBothThemesAndShortWindows() {
        val app=RuntimeEnvironment.getApplication()
        try {
            for(dark in listOf(false,true)) {
                ThemeSettingsStore.setDark(app,dark)
                Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
                    val dialog=show(controller.get()).requireDialog() as LyricsSearchInputDialog
                    val decor=dialog.window!!.decorView
                    layout(decor,400,900)
                    settle()
                    layout(decor,400,900)
                    val card=dialog.card
                    val bitmap=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888)
                    card.draw(Canvas(bitmap))
                    File("/tmp/airlyrics-word-dialog-${if(dark) "dark" else "light"}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                    for(height in listOf(360,240)) {
                        layout(decor,640,height)
                        assertTrue(card.height<=height)
                        assertTrue(fields(dialog).all { it.editText!!.isEnabled })
                    }
                }
            }
        } finally { ThemeSettingsStore.setDark(app,false) }
    }
    private fun show(activity:LyricsSearchActivity)=WordLyricsSearchDialog.create(song.copy(album="",durationMs=0),song.storageKey()).also {
        it.showNow(activity.supportFragmentManager,WordLyricsSearchDialog.TAG)
    }
    private fun fields(dialog:android.app.Dialog)=descendants(dialog.window!!.decorView).filterIsInstance<TextInputLayout>()
    private fun session(activity:LyricsSearchActivity)=LyricsSearchActivity::class.java.getDeclaredField("session")
        .apply { isAccessible=true }.get(activity) as LyricsSearchSession
    private fun intent()=Intent(RuntimeEnvironment.getApplication(),LyricsSearchActivity::class.java)
        .putExtra("search.title",song.title).putExtra("search.artist",song.artist)
        .putExtra("search.album",song.album).putExtra("search.duration",song.durationMs)
    private fun settle()=ShadowLooper.idleMainLooper(500,TimeUnit.MILLISECONDS)
    private fun layout(view:View,width:Int,height:Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
}
