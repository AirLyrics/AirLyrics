package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import android.view.ViewGroup
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsAppearanceNavigationStateTest {
    @Test
    fun openPickerIsRestoredWhenTheSettingsHomeViewIsRecreated() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val host = controller.get().graph.uiHost
            val first = createSettingsAppearanceHeader(host) as ViewGroup
            val label = host.getString(R.string.ui_choose_accent_color)
            first.descendants().single { it.contentDescription == label }.performClick()

            val returned = createSettingsAppearanceHeader(host) as ViewGroup
            assertEquals(View.VISIBLE, returned.getChildAt(1).visibility)

            returned.descendants().single { it.contentDescription == label }.performClick()
            val reopened = createSettingsAppearanceHeader(host) as ViewGroup
            assertEquals(View.GONE, reopened.getChildAt(1).visibility)
        }
    }
}

private fun View.descendants(): List<View> = listOf(this) +
    if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } else emptyList()
