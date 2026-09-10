package com.andsi.airlyrics.floating

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingServiceNotificationTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun summaryDistinguishesShownHiddenAndBlockedStates() {
        val cases = listOf(
            state(visible = true, desiredVisible = true) to
                "${application.getString(R.string.ui_shown)} · " +
                application.getString(R.string.ui_adjustment_mode),
            state(visible = true, desiredVisible = false) to
                "${application.getString(R.string.ui_shown)} · " +
                application.getString(R.string.ui_adjustment_mode),
            state(visible = false, desiredVisible = false) to
                application.getString(R.string.ui_hidden),
            state(visible = false, desiredVisible = true) to
                application.getString(R.string.ui_display_blocked)
        )

        cases.forEach { (state, expectedSummary) ->
            assertEquals(expectedSummary, contentText(state))
        }
    }

    @Test
    fun actionsAreHiddenWhileDisplayIsBlocked() {
        val shownNotification = FloatingServiceNotification.create(
            application,
            state(visible = true, desiredVisible = true)
        )
        val hiddenNotification = FloatingServiceNotification.create(
            application,
            state(visible = false, desiredVisible = false)
        )
        val blockedNotification = FloatingServiceNotification.create(
            application,
            state(visible = false, desiredVisible = true)
        )

        assertEquals(
            listOf(
                application.getText(R.string.ui_hide),
                application.getText(R.string.ui_adjustment_mode)
            ),
            shownNotification.actions.map(Notification.Action::title)
        )
        assertEquals(
            listOf(application.getText(R.string.ui_show)),
            hiddenNotification.actions.map(Notification.Action::title)
        )
        assertEquals(0, blockedNotification.actions?.size ?: 0)
        assertNotNull(blockedNotification.contentIntent)
    }

    private fun contentText(state: FloatingServiceNotification.QuickControlState): String {
        return FloatingServiceNotification.create(application, state)
            .extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            .orEmpty()
    }

    private fun state(
        visible: Boolean,
        desiredVisible: Boolean
    ) = FloatingServiceNotification.QuickControlState(
        visible = visible,
        desiredVisible = desiredVisible,
        locked = false,
        clickThrough = false
    )
}
