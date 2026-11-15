package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsAppearanceUpdatesTest {
    @Test fun disabledAnimationsApplyFinalThemeAndPanelWithoutReplacingViews() {
        val durationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        durationScale.invoke(null, 0f)
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                val host = activity.graph.uiHost
                activity.graph.viewModel.selectPage(Page.SETTINGS)
                activity.graph.uiInvalidator.rebuildCurrentPage(false, false)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val panel = root.findViewById<View>(R.id.appearance_picker_panel)
                val trigger = root.nodes().single { it.contentDescription == host.getString(R.string.ui_choose_accent_color) }
                trigger.performClick()
                assertEquals(View.VISIBLE, panel.visibility)
                host.toggleThemeMode()
                host.toggleThemeMode()
                assertSame(root, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                assertSame(panel, activity.findViewById(R.id.appearance_picker_panel))
                assertEquals(AirLyricsTheme.palette(host.isDarkTheme(), host.themeAccent()), host.appearance.frame.palette)
                assertFalse(host.appearance.isAnimating)
                assertEquals(View.VISIBLE, panel.visibility)
                trigger.performClick()
                assertEquals(View.GONE, panel.visibility)
            }
        } finally {
            durationScale.invoke(null, 1f)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    @Test fun aNewlySelectedSwatchClosesOnItsNextClickWithoutRebuildingTheHeader() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val host = controller.get().graph.uiHost
            val header = createSettingsAppearanceHeader(host) as ViewGroup
            controller.get().setContentView(header)
            // Use the nonanimated initial state so this verifies the selection transaction itself.
            val trigger = header.nodes().single { it.contentDescription == host.getString(R.string.ui_choose_accent_color) }
            val durationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            durationScale.invoke(null, 0f)
            try {
                trigger.performClick()
                val blue = header.nodes().single {
                    it.contentDescription == host.getString(R.string.ui_accent_color_option, host.getString(R.string.ui_blue))
                }
                blue.performClick()
                assertEquals(ThemeAccent.BLUE, host.themeAccent())
                assertTrue(blue.isSelected)
                assertEquals(View.VISIBLE, header.getChildAt(1).visibility)
                blue.performClick()
                assertEquals(View.GONE, header.getChildAt(1).visibility)
            } finally { durationScale.invoke(null, 1f) }
        }
    }

    private fun View.nodes(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).nodes() } else emptyList()
}
