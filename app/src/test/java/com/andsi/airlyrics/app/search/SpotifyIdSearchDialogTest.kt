package com.andsi.airlyrics.app.search

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="en-rUS-w400dp-h900dp-mdpi")
class SpotifyIdSearchDialogTest {
    @Test fun confirmationIsSentOnceAndWindowLeavesBeforeFragmentIsRemoved() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val ids=mutableListOf<String>()
            activity.supportFragmentManager.setFragmentResultListener(SpotifyIdSearchDialog.RESULT,activity) { _, result ->
                ids += result.getString(SpotifyIdSearchDialog.ID)!!
            }
            val fragment=show(activity)
            val dialog=fragment.requireDialog() as LyricsSearchInputDialog
            settle(600)
            field(dialog).editText!!.setText("https://open.spotify.com/track/5l94Up12BJSjhH7Mhr2ji4")
            dialog.findViewById<View>(android.R.id.button1).performClick()
            dialog.findViewById<View>(android.R.id.button1).performClick()
            assertEquals(listOf("5l94Up12BJSjhH7Mhr2ji4"),ids)
            assertTrue(dialog.isShowing)
            assertTrue(dialog.isLeaving)
            settle(300)
            assertFalse(dialog.isShowing)
            assertNull(activity.supportFragmentManager.findFragmentByTag(SpotifyIdSearchDialog.TAG))
            assertEquals(0f,activity.interactionUi.maxShowingDimAmount(),0.001f)
        }
    }

    @Test fun inputWindowPreservesTheShadeOfAnUnderlyingSourcesSheet() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val sheet=LyricsSourcesSheet.create(setOf(com.andsi.airlyrics.core.model.PlainLyricsSearchSource.SPICY))
            sheet.showNow(activity.supportFragmentManager,LyricsSourcesSheet.TAG)
            settle(600)
            val shade=sheet.requireDialog().window!!.attributes.dimAmount
            val dialog=show(activity).requireDialog() as LyricsSearchInputDialog
            assertEquals(shade,dialog.window!!.attributes.dimAmount,0.001f)
            settle(300)
            dialog.cancel()
            settle(64)
            assertEquals(shade,dialog.window!!.attributes.dimAmount,0.001f)
            settle(300)
            assertFalse(dialog.isShowing)
            assertTrue(sheet.requireDialog().isShowing)
            assertEquals(shade,activity.interactionUi.maxShowingDimAmount(),0.001f)
        }
    }

    @Test fun cancellingDuringEntryDoesNotSnapOrLeaveAWindowBehind() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val activity=controller.get()
            val dialog=show(activity).requireDialog() as LyricsSearchInputDialog
            settle(48)
            val alpha=dialog.card.alpha
            val scale=dialog.card.scaleX
            dialog.cancel()
            assertEquals(alpha,dialog.card.alpha,0.001f)
            assertEquals(scale,dialog.card.scaleX,0.001f)
            settle(400)
            assertFalse(dialog.isShowing)
            assertEquals(0f,activity.interactionUi.maxShowingDimAmount(),0.001f)
        }
    }

    @Test fun recreationRestoresDraftAndImmediatelyReleasesTheOldWindow() {
        Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
            val old=show(controller.get()).requireDialog() as LyricsSearchInputDialog
            field(old).editText!!.setText("unfinished-link")
            settle(48)
            controller.recreate()
            settle(300)
            assertFalse(old.isShowing)
            val restored=controller.get().supportFragmentManager.findFragmentByTag(SpotifyIdSearchDialog.TAG) as SpotifyIdSearchDialog
            val dialog=restored.requireDialog() as LyricsSearchInputDialog
            assertEquals("unfinished-link",field(dialog).editText!!.text.toString())
            assertEquals(1f,dialog.card.alpha,0.001f)
            assertEquals(1f,dialog.card.scaleX,0.001f)
        }
    }

    @Test fun disabledSystemAnimationPresentsAndDismissesWithoutMotion() {
        ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",0f)
        try {
            Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
                val dialog=show(controller.get()).requireDialog() as LyricsSearchInputDialog
                assertEquals(1f,dialog.card.alpha,0.001f)
                assertEquals(1f,dialog.card.scaleX,0.001f)
                dialog.dismiss()
                assertFalse(dialog.isShowing)
            }
        } finally {
            ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",1f)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun themedInputAndActionsFitAboveTheKeyboardAndInShortWindows() {
        val app=RuntimeEnvironment.getApplication()
        for (dark in listOf(false,true)) {
            ThemeSettingsStore.setDark(app,dark)
            Robolectric.buildActivity(LyricsSearchActivity::class.java,intent()).setup().visible().use { controller ->
                val activity=controller.get()
                val dialog=show(activity).requireDialog() as LyricsSearchInputDialog
                val decor=dialog.window!!.decorView
                layout(decor,400,900)
                settle(600)
                layout(decor,400,900)
                val textStrong=AirLyricsTheme.palette(dark,ThemeSettingsStore.getAccent(activity)).textStrong
                assertEquals(textStrong,field(dialog).editText!!.currentTextColor)
                assertTrue(dialog.card.width <= 360)
                capture(dialog,if (dark) "dark" else "light")

                val root=dialog.card.parent.parent as ViewGroup
                ViewCompat.dispatchApplyWindowInsets(
                    root,
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,320))
                        .setVisible(WindowInsetsCompat.Type.ime(),true).build(),
                )
                layout(decor,400,900)
                settle(300)
                val bounds=Rect()
                dialog.card.getDrawingRect(bounds)
                root.offsetDescendantRectToMyCoords(dialog.card,bounds)
                assertTrue(bounds.bottom <= (root.height-root.paddingBottom+1))

                for (height in listOf(360,240)) {
                    layout(decor,640,height)
                    assertTrue(dialog.card.height <= height)
                    assertTrue(field(dialog).editText!!.isEnabled)
                }
            }
        }
        ThemeSettingsStore.setDark(app, enabled = false)
    }

    private fun show(activity:LyricsSearchActivity)=SpotifyIdSearchDialog.create(null).also {
        it.showNow(activity.supportFragmentManager,SpotifyIdSearchDialog.TAG)
    }
    private fun field(dialog:LyricsSearchInputDialog)=descendants(dialog.window!!.decorView)
        .asSequence().filterIsInstance<TextInputLayout>().single()
    private fun intent()=Intent(RuntimeEnvironment.getApplication(),LyricsSearchActivity::class.java)
        .putExtra("search.title","Song").putExtra("search.artist","Artist")
    private fun settle(ms:Long)=ShadowLooper.idleMainLooper(ms,TimeUnit.MILLISECONDS)
    private fun layout(view:View,width:Int,height:Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
    }
    private fun capture(dialog:LyricsSearchInputDialog,theme:String) {
        val card=dialog.card
        val bitmap=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888)
        card.draw(Canvas(bitmap))
        File(System.getProperty("java.io.tmpdir"),"airlyrics-id-dialog-$theme.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
