package com.andsi.airlyrics.app.render

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.ui.navigation.SettingsNavigation
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsPageTransitionTest {
    @Test
    fun rtlPushKeepsTheOldPageUntilCancellationAndSettlesTheNewPage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = FrameLayout(context).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val oldPage = View(context)
        val newPage = View(context)
        container.addView(oldPage)
        container.addView(newPage)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        )
        container.layout(0, 0, 300, 600)

        val transition = SettingsPageTransition()
        transition.start(
            container,
            oldPage,
            newPage,
            SettingsNavigation.between(SettingsSubPage.HOME, SettingsSubPage.LYRICS)!!,
            surfaceColor = Color.BLACK,
            dp = { it }
        )

        assertSame(container, oldPage.parent)
        assertTrue(newPage.translationX < 0f)
        assertEquals(1f, newPage.alpha)
        assertEquals(Color.BLACK, (newPage.background as ColorDrawable).color)
        assertEquals(0, newPage.clipBounds?.left)
        assertTrue(newPage.clipBounds!!.right < container.width)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, oldPage.importantForAccessibility)

        transition.cancel()

        assertNull(oldPage.parent)
        assertSame(container, newPage.parent)
        assertEquals(0f, newPage.translationX)
        assertEquals(1f, newPage.alpha)
        assertEquals(1f, newPage.scaleX)
        assertNull(newPage.background)
        assertNull(newPage.clipBounds)
        assertEquals(1, container.childCount)
    }

    @Test
    fun interruptedPopKeepsItsPoseButRemovesTheTemporarySurface() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = FrameLayout(context)
        val childPage = View(context)
        val parentPage = View(context)
        container.addView(childPage)
        container.addView(parentPage)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        )
        container.layout(0, 0, 300, 600)

        val transition = SettingsPageTransition()
        transition.start(
            container,
            childPage,
            parentPage,
            SettingsNavigation.between(SettingsSubPage.LYRICS, SettingsSubPage.HOME)!!,
            surfaceColor = Color.BLACK,
            dp = { it }
        )
        val startX = parentPage.translationX
        assertTrue(startX < 0f)
        assertEquals(0, parentPage.clipBounds?.left)
        assertTrue(parentPage.clipBounds!!.right < container.width)

        transition.cancel(settleIncoming = false)

        assertNull(childPage.parent)
        assertSame(container, parentPage.parent)
        assertEquals(startX, parentPage.translationX)
        assertNull(parentPage.background)
        assertNull(parentPage.clipBounds)
        assertEquals(1, container.childCount)
    }
}
