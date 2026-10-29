package com.andsi.airlyrics.ui.pages.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceMenuStateTest {
    @Test
    fun togglingOpenMenuClosesIt() {
        val state = AppearanceMenuState()
        assertEquals(AppearanceMenu.NONE, state.openMenu)

        assertEquals(AppearanceMenu.ACCENT, state.toggle(AppearanceMenu.ACCENT))
        assertEquals(AppearanceMenu.NONE, state.toggle(AppearanceMenu.ACCENT))
        assertEquals(AppearanceMenu.BUTTON_STYLE, state.toggle(AppearanceMenu.BUTTON_STYLE))
        assertEquals(AppearanceMenu.NONE, state.toggle(AppearanceMenu.BUTTON_STYLE))
    }

    @Test
    fun rapidAlternationKeepsOnlyTheLastMenuOpen() {
        val state = AppearanceMenuState()
        val taps = listOf(
            AppearanceMenu.ACCENT,
            AppearanceMenu.BUTTON_STYLE,
            AppearanceMenu.ACCENT,
            AppearanceMenu.BUTTON_STYLE,
            AppearanceMenu.BUTTON_STYLE,
            AppearanceMenu.ACCENT
        )
        val results = taps.map(state::toggle)

        assertEquals(
            listOf(
                AppearanceMenu.ACCENT,
                AppearanceMenu.BUTTON_STYLE,
                AppearanceMenu.ACCENT,
                AppearanceMenu.BUTTON_STYLE,
                AppearanceMenu.NONE,
                AppearanceMenu.ACCENT
            ),
            results
        )
        assertEquals(AppearanceMenu.ACCENT, state.openMenu)
    }

    @Test
    fun crossfadeKeepsTheContainerContentVisibleThroughoutTheSwitch() {
        for (step in 0..10) {
            val progress = step / 10f
            val (accent, buttonStyle) = menuCrossfadeAlphas(
                1f, 0f, AppearanceMenu.BUTTON_STYLE, progress
            )
            assertEquals(1f, accent + buttonStyle, 0.0001f)
        }
        assertEquals(0f to 1f, menuCrossfadeAlphas(1f, 0f, AppearanceMenu.BUTTON_STYLE, 1f))
    }

    @Test
    fun interruptedCrossfadeStartsAtTheCurrentOpacityAndFollowsTheLastTap() {
        val interrupted = menuCrossfadeAlphas(1f, 0f, AppearanceMenu.BUTTON_STYLE, 0.35f)

        assertEquals(
            interrupted,
            menuCrossfadeAlphas(interrupted.first, interrupted.second, AppearanceMenu.ACCENT, 0f)
        )
        assertEquals(
            1f to 0f,
            menuCrossfadeAlphas(interrupted.first, interrupted.second, AppearanceMenu.ACCENT, 1f)
        )
    }
}
