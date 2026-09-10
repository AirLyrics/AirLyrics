package com.andsi.airlyrics.floating

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.displayscope.DisplayScopeBlockReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingServiceNotificationTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun summaryCoversEveryVisibilityStateWithActualVisibilityFirst() {
        val cases = listOf(
            state(visible = true, desiredVisible = true) to R.string.ui_shown,
            state(
                visible = true,
                desiredVisible = true,
                blockReason = DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP
            ) to R.string.ui_shown,
            state(
                visible = false,
                desiredVisible = true,
                blockReason = DisplayScopeBlockReason.USAGE_ACCESS_REQUIRED
            ) to R.string.ui_usage_access_required,
            state(
                visible = false,
                desiredVisible = true,
                blockReason = DisplayScopeBlockReason.CHECKING_SELECTED_APPS
            ) to R.string.ui_checking_selected_apps,
            state(
                visible = false,
                desiredVisible = true,
                blockReason = DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP
            ) to R.string.ui_waiting_for_selected_app,
            state(
                visible = false,
                desiredVisible = false,
                blockReason = DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP
            ) to R.string.ui_hidden,
            state(visible = false, desiredVisible = true) to R.string.ui_hidden
        )

        cases.forEach { (state, expectedStatusRes) ->
            val notification = FloatingServiceNotification.create(application, state)
            val contentText = notification.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                .orEmpty()

            assertTrue(contentText.startsWith(application.getString(expectedStatusRes)))
        }
    }

    @Test
    fun visibilityActionFollowsUserIntentWhileScopeTemporarilyHidesWindow() {
        val waitingNotification = FloatingServiceNotification.create(
            application,
            state(
                visible = false,
                desiredVisible = true,
                blockReason = DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP
            )
        )
        val hiddenNotification = FloatingServiceNotification.create(
            application,
            state(visible = false, desiredVisible = false)
        )

        assertEquals(application.getText(R.string.ui_hide), waitingNotification.actions.first().title)
        assertEquals(application.getText(R.string.ui_show), hiddenNotification.actions.first().title)
    }

    private fun state(
        visible: Boolean,
        desiredVisible: Boolean,
        blockReason: DisplayScopeBlockReason? = null
    ) = FloatingServiceNotification.QuickControlState(
        visible = visible,
        desiredVisible = desiredVisible,
        locked = false,
        clickThrough = false,
        displayScopeBlockReason = blockReason
    )
}
