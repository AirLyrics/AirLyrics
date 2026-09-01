package com.andsi.airlyrics.app.controller

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.state.MainFloatingState
import com.andsi.airlyrics.floating.FloatingServiceCommand
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
class FloatingControllerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        QuickFloatingStore.setDesiredVisible(context, false)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @After
    fun tearDown() {
        QuickFloatingStore.setDesiredVisible(context, false)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun showWithoutPermission_recordsIntentButDoesNotStartService() {
        val state = FakeFloatingState(overlayPermissionGranted = true)
        val commands = mutableListOf<Intent>()
        val controller = controller(state, commands)

        val outcome = controller.showLyrics()

        assertEquals(FloatingVisibilityOutcome.PERMISSION_REQUIRED, outcome)
        assertFalse(state.overlayPermissionGranted)
        assertFalse(state.quickFloatingVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(context))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun showWithPermission_updatesActualStateAfterCommandSucceeds() {
        ShadowSettings.setCanDrawOverlays(true)
        val state = FakeFloatingState()
        val commands = mutableListOf<Intent>()
        val controller = controller(state, commands)

        val outcome = controller.showLyrics()

        assertEquals(FloatingVisibilityOutcome.SUCCESS, outcome)
        assertTrue(state.overlayPermissionGranted)
        assertTrue(state.quickFloatingVisible)
        assertEquals(
            FloatingServiceCommand.Show,
            FloatingServiceCommand.fromIntent(commands.single())
        )
    }

    @Test
    fun failedShowCommand_doesNotClaimThatWindowIsVisible() {
        ShadowSettings.setCanDrawOverlays(true)
        val state = FakeFloatingState()
        val controller = FloatingController(
            context = context,
            state = state,
            serviceStarter = { false }
        )

        val outcome = controller.showLyrics()

        assertEquals(FloatingVisibilityOutcome.COMMAND_FAILED, outcome)
        assertFalse(state.quickFloatingVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(context))
    }

    @Test
    fun toggleWithRevokedPermission_preservesShowIntentDespiteStaleVisibleState() {
        val state = FakeFloatingState(
            quickFloatingVisible = true,
            overlayPermissionGranted = true
        )
        val commands = mutableListOf<Intent>()
        val controller = controller(state, commands)

        val outcome = controller.toggleLyrics()

        assertEquals(FloatingVisibilityOutcome.PERMISSION_REQUIRED, outcome)
        assertFalse(state.overlayPermissionGranted)
        assertTrue(QuickFloatingStore.isDesiredVisible(context))
        assertTrue(commands.isEmpty())
    }

    private fun controller(
        state: FakeFloatingState,
        commands: MutableList<Intent>
    ): FloatingController {
        return FloatingController(
            context = context,
            state = state,
            serviceStarter = { intent ->
                commands += intent
                true
            }
        )
    }

    private class FakeFloatingState(
        override var locked: Boolean = false,
        override var clickThrough: Boolean = false,
        override var quickFloatingVisible: Boolean = false,
        override var overlayPermissionGranted: Boolean = false
    ) : MainFloatingState {
        override fun updateFloatingState(
            visible: Boolean?,
            overlayGranted: Boolean?,
            locked: Boolean?,
            clickThrough: Boolean?
        ) {
            visible?.let { quickFloatingVisible = it }
            overlayGranted?.let { overlayPermissionGranted = it }
            locked?.let { this.locked = it }
            clickThrough?.let { this.clickThrough = it }
        }
    }
}
