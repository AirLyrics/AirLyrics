package com.andsi.airlyrics.app.search

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.airTonalButtonStyle
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.button.MaterialButton
import org.junit.After
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
@Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsSearchButtonStyleTest {
    @After fun clearTheme() {
        RuntimeEnvironment.getApplication().getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun searchAndDialogActionsFollowBothButtonStylesInBothThemes() {
        val app = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            for (style in ButtonStyle.entries) {
                ThemeSettingsStore.setDark(app, dark)
                ThemeSettingsStore.setButtonStyle(app, style)
                val intent = Intent(app, LyricsSearchActivity::class.java).putExtra("search.title", "Song")
                Robolectric.buildActivity(LyricsSearchActivity::class.java, intent).setup().visible().use { controller ->
                    val activity = controller.get()
                    val root = activity.findViewById<View>(android.R.id.content)
                    check(button(root, R.string.search_regular_search), AirTonalButtonTone.PRIMARY)
                    check(button(root, R.string.search_apply_current), AirTonalButtonTone.PRIMARY)
                    check(button(root, R.string.search_switch_target), AirTonalButtonTone.DEFAULT)
                    check(activity.sourceCapsule()!!, AirTonalButtonTone.DEFAULT)
                    assertTextOnly(button(root, R.string.search_more))
                    capture(root, "search-$dark-${style.preferenceValue}")

                    val spotify = SpotifyIdSearchDialog.create(null)
                    spotify.showNow(activity.supportFragmentManager, SpotifyIdSearchDialog.TAG)
                    check(spotify.requireDialog().findViewById(android.R.id.button1), AirTonalButtonTone.PRIMARY)
                    check(spotify.requireDialog().findViewById(android.R.id.button2), AirTonalButtonTone.DEFAULT)
                    capture(spotify.requireDialog().window!!.decorView, "spotify-$dark-${style.preferenceValue}")
                    spotify.dismissNow()

                    val word = WordLyricsSearchDialog.create(SongIdentity("Song", "Artist", durationMs = 0L))
                    word.showNow(activity.supportFragmentManager, WordLyricsSearchDialog.TAG)
                    check(word.requireDialog().findViewById(android.R.id.button1), AirTonalButtonTone.PRIMARY)
                    check(word.requireDialog().findViewById(android.R.id.button2), AirTonalButtonTone.DEFAULT)
                    assertTextOnly(button(word.requireDialog().window!!.decorView, R.string.search_match_options))
                    word.dismissNow()

                    val palette = AirLyricsTheme.palette(dark, ThemeSettingsStore.getAccent(app))
                    val missing = MissingSourcesDialog(activity, palette, dark, 0f)
                    check(missing.findViewById(android.R.id.button1), AirTonalButtonTone.PRIMARY)
                    missing.dismiss()

                    val sources = LyricsSourcesSheet.create(setOf(PlainLyricsSearchSource.NETEASE))
                    sources.showNow(activity.supportFragmentManager, LyricsSourcesSheet.TAG)
                    val decor = sources.requireDialog().window!!.decorView
                    val apply = button(decor, R.string.search_sources_use)
                    check(apply, AirTonalButtonTone.PRIMARY)
                    descendants(decor).filterIsInstance<LyricsSourceSelectionRow>().single { it.isSelected }.performClick()
                    assertFalse(apply.isEnabled)
                    assertTrue(Color.alpha(apply.currentTextColor) < Color.alpha(apply.textColors.defaultColor))
                    assertEquals(apply.currentTextColor, apply.iconTint!!.getColorForState(apply.drawableState, 0))
                    sources.dismissNow()
                }
            }
        }
    }

    private fun check(button: MaterialButton, tone: AirTonalButtonTone) {
        val expected = button.context.airTonalButtonStyle(tone)
        assertEquals(expected.fillColor, button.backgroundTintList!!.defaultColor)
        assertEquals(expected.textColor, button.textColors.defaultColor)
        assertEquals(expected.textColor, button.iconTint!!.defaultColor)
        assertEquals(expected.strokeColor, button.strokeColor!!.defaultColor)
        assertEquals(expected.strokeWidthDp, button.strokeWidth)
    }

    private fun assertTextOnly(button: MaterialButton) {
        assertEquals(Color.TRANSPARENT, button.backgroundTintList!!.defaultColor)
        assertEquals(0, button.strokeWidth)
        assertEquals(button.textColors.defaultColor, button.iconTint!!.defaultColor)
    }

    private fun button(root: View, label: Int) = descendants(root).filterIsInstance<MaterialButton>()
        .first { it.text.toString() == root.context.getString(label) }

    private fun capture(view: View, name: String) {
        val decor = view.rootView
        fun layout() {
            decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 400, 900)
        }
        layout()
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        layout()
        val bitmap = Bitmap.createBitmap(400, 900, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        File(System.getProperty("java.io.tmpdir"), "airlyrics-buttons-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
