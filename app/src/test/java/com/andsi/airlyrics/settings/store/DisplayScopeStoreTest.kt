package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DisplayScopeStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reset()
    }

    @After
    fun tearDown() = reset()

    @Test
    fun defaultsToAllAppsAndNoSelection() {
        assertFalse(DisplayScopeStore.isEnabled(context))
        assertTrue(DisplayScopeStore.selectedPackages(context).isEmpty())
    }

    @Test
    fun persistsEnabledStateAndNormalizedPackageSet() {
        DisplayScopeStore.setSelectedPackages(context, setOf("player.app", "", "lyrics.app"))
        DisplayScopeStore.setEnabled(context, true)

        assertTrue(DisplayScopeStore.isEnabled(context))
        assertEquals(setOf("player.app", "lyrics.app"), DisplayScopeStore.selectedPackages(context))
    }

    private fun reset() {
        DisplayScopeStore.setEnabled(context, false)
        DisplayScopeStore.setSelectedPackages(context, emptySet())
    }
}
