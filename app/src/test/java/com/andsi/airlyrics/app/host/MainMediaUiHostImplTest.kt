package com.andsi.airlyrics.app.host

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.airTonalButtonStyle
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorOnAccent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class MainMediaUiHostImplTest {
    private var activityController: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun refreshKeepsButtonStyleThroughLoadingAndCompletionInBothThemes() {
        val app = RuntimeEnvironment.getApplication()
        for (dark in listOf(false, true)) {
            for (style in ButtonStyle.entries) {
                ThemeSettingsStore.setDark(app, dark)
                ThemeSettingsStore.setButtonStyle(app, style)
                Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                    val activity = controller.get()
                    val host = activity.graph.uiHost
                    val button = host.refreshMediaButton() as LinearLayout
                    fun check(tone: AirTonalButtonTone, enabled: Boolean) {
                        val expected = host.airTonalButtonStyle(tone)
                        val label = (0 until button.childCount).map(button::getChildAt)
                            .filterIsInstance<TextView>().single()
                        assertEquals(expected.fillColor, (button.background as GradientDrawable).color!!.defaultColor)
                        assertEquals(expected.textColor, label.currentTextColor)
                        assertEquals(enabled, button.isEnabled)
                        assertTrue(ColorUtils.calculateContrast(label.currentTextColor, expected.fillColor) >= 4.5)
                    }
                    check(AirTonalButtonTone.PRIMARY, true)
                    button.performClick()
                    check(AirTonalButtonTone.DEFAULT, false)
                    assertEquals(2, button.childCount)
                    ShadowLooper.idleMainLooper(AirUiTokens.Layout.MediaRefreshingMs, TimeUnit.MILLISECONDS)
                    check(AirTonalButtonTone.SUCCESS, true)
                    assertEquals(1, button.childCount)
                    host.mediaRefreshHandler.removeCallbacksAndMessages(null)
                }
            }
        }
    }

    @Test
    fun completedRefreshUsesContrastingForegroundInLightTheme() {
        val app = RuntimeEnvironment.getApplication()
        ThemeSettingsStore.setDark(app, false)
        ThemeSettingsStore.setButtonStyle(app, ButtonStyle.SOLID)
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
        val host = activity.graph.uiHost
        activity.graph.viewModel.setMediaRefreshState(RefreshState.DONE)

        val button = host.refreshMediaButton() as LinearLayout
        val label = button.getChildAt(0) as TextView

        assertEquals(host.colorOnAccent, label.currentTextColor)
        assertTrue(
            ColorUtils.calculateContrast(label.currentTextColor, host.colorAccentSoft) >= 4.5
        )
    }
}
