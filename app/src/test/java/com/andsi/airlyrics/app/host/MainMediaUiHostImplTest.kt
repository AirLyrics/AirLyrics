package com.andsi.airlyrics.app.host

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorOnAccent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
    fun completedRefreshUsesContrastingForegroundInLightTheme() {
        val app = RuntimeEnvironment.getApplication()
        ThemeSettingsStore.setDark(app, false)
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
