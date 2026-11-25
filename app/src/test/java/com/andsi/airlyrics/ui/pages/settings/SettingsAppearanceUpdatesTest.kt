package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer

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
                activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
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

    @Test fun choosingAccentAndStyleRetargetsTheMountedAppearance() {
        val durationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        val originalScale = ValueAnimator.getDurationScale()
        durationScale.invoke(null, 1f)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                val host = activity.graph.uiHost
                activity.graph.viewModel.selectPage(Page.SETTINGS)
                activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
                controller.visible()
                fun frames(count: Int) {
                    repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
                }
                frames(3)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val old = host.appearance.frame
                val nextAccent = if (host.themeAccent() == ThemeAccent.BLUE) ThemeAccent.ORANGE else ThemeAccent.BLUE
                val nextStyle = ButtonStyle.entries.first { it != host.buttonStyle() }

                host.selectThemeAccent(nextAccent)
                assertTrue(
                    "Appearance starts with the settings window attached: content=${host.contentContainer?.isAttachedToWindow}, " +
                        "decor=${activity.window.decorView.isAttachedToWindow}, scale=${ValueAnimator.getDurationScale()}",
                    host.appearance.isAnimating,
                )
                assertEquals(old.palette, host.appearance.frame.palette)
                frames(4)
                val intermediate = host.appearance.frame
                assertTrue(intermediate.accentSelectionFraction(nextAccent) in 0.01f..0.99f)
                assertNotEquals(old.palette.accent, intermediate.palette.accent)

                host.selectButtonStyle(nextStyle)
                assertEquals(intermediate.palette, host.appearance.frame.palette)
                frames(4)
                assertTrue(host.appearance.frame.solidButtonFraction in 0.01f..0.99f)
                frames(15)
                assertFalse(host.appearance.isAnimating)
                assertEquals(AirLyricsTheme.palette(host.isDarkTheme(), nextAccent), host.appearance.frame.palette)
                assertEquals(1f, host.appearance.frame.accentSelectionFraction(nextAccent), 0.001f)
                assertEquals(if (nextStyle == ButtonStyle.SOLID) 1f else 0f, host.appearance.frame.solidButtonFraction, 0.001f)
                assertSame(root, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            }
        } finally {
            ShadowChoreographer.setPaused(false)
            durationScale.invoke(null, originalScale)
        }
    }

    private fun View.nodes(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).nodes() } else emptyList()
}
