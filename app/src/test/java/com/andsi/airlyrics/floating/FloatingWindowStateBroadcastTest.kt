package com.andsi.airlyrics.floating

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingWindowStateBroadcastTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun windowVisibilityChangedIntent_roundTripsState() {
        val state = FloatingWindowStateBroadcast.State(
            visible = true,
            locked = false,
            clickThrough = true
        )

        val decoded = FloatingWindowStateBroadcast.readState(
            FloatingWindowStateBroadcast.windowVisibilityChangedIntent(context, state)
        )

        assertEquals(state, decoded)
    }

    @Test
    fun quickControlChangedIntent_roundTripsState() {
        val state = FloatingWindowStateBroadcast.State(
            visible = false,
            locked = true,
            clickThrough = false
        )

        val decoded = FloatingWindowStateBroadcast.readState(
            FloatingWindowStateBroadcast.quickControlChangedIntent(context, state)
        )

        assertEquals(state, decoded)
    }

    @Test
    fun readState_ignoresWrongActionOrMissingPayload() {
        val validIntent = FloatingWindowStateBroadcast.windowVisibilityChangedIntent(
            context,
            FloatingWindowStateBroadcast.State(
                visible = true,
                locked = true,
                clickThrough = false
            )
        )
        val missingPayload = Intent(validIntent.action)

        assertNull(FloatingWindowStateBroadcast.readState(Intent("com.example.UNRELATED")))
        assertNull(FloatingWindowStateBroadcast.readState(missingPayload))
    }
}
