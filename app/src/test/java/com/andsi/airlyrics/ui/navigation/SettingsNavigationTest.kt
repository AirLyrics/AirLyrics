package com.andsi.airlyrics.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun adjacentSettingsRoutesHaveMatchingPushAndPopDepths() {
        val routes = listOf(
            SettingsSubPage.HOME to SettingsSubPage.LYRICS,
            SettingsSubPage.HOME to SettingsSubPage.SYSTEM,
            SettingsSubPage.HOME to SettingsSubPage.ABOUT,
            SettingsSubPage.LYRICS to SettingsSubPage.SAVED_LYRICS,
            SettingsSubPage.LYRICS to SettingsSubPage.APPLE_ACCOUNT
        )

        routes.forEach { (parent, child) ->
            val expectedDepth = if (parent == SettingsSubPage.HOME) 1 else 2
            assertEquals(
                SettingsNavigation(parent, child, SettingsNavigationDirection.PUSH, expectedDepth),
                SettingsNavigation.between(parent, child)
            )
            assertEquals(
                SettingsNavigation(child, parent, SettingsNavigationDirection.POP, expectedDepth),
                SettingsNavigation.between(child, parent)
            )
        }
    }

    @Test
    fun samePageAndNonAdjacentRoutesDoNotRequestPageMotion() {
        assertNull(SettingsNavigation.between(SettingsSubPage.HOME, SettingsSubPage.HOME))
        assertNull(SettingsNavigation.between(SettingsSubPage.HOME, SettingsSubPage.SAVED_LYRICS))
        assertNull(SettingsNavigation.between(SettingsSubPage.SYSTEM, SettingsSubPage.ABOUT))
    }
}
