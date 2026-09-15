package com.andsi.airlyrics.displayscope

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class DisplayScopePolicyTest(
    private val caseName: String,
    private val enabled: Boolean,
    private val usageAccessGranted: Boolean,
    private val visibilitySnapshotAvailable: Boolean,
    private val selectedPackages: Set<String>,
    private val visiblePackages: Set<String>,
    private val expected: DisplayScopeDecision
) {
    @Test
    fun decide_returnsExpectedDecision() {
        assertEquals(
            caseName,
            expected,
            DisplayScopePolicy.decide(
                enabled = enabled,
                usageAccessGranted = usageAccessGranted,
                visibilitySnapshotAvailable = visibilitySnapshotAvailable,
                selectedPackages = selectedPackages,
                visiblePackages = visiblePackages
            )
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf(
                "disabled filter allows display",
                false,
                false,
                false,
                emptySet<String>(),
                emptySet<String>(),
                DisplayScopeDecision(allowsDisplay = true)
            ),
            arrayOf(
                "missing usage access fails closed",
                true,
                false,
                false,
                setOf("player.app"),
                setOf("player.app"),
                DisplayScopeDecision(
                    allowsDisplay = false,
                    blockReason = DisplayScopeBlockReason.USAGE_ACCESS_REQUIRED
                )
            ),
            arrayOf(
                "first visibility snapshot is required",
                true,
                true,
                false,
                setOf("player.app"),
                emptySet<String>(),
                DisplayScopeDecision(
                    allowsDisplay = false,
                    blockReason = DisplayScopeBlockReason.CHECKING_SELECTED_APPS
                )
            ),
            arrayOf(
                "any visible selected app allows display",
                true,
                true,
                true,
                setOf("lyrics.app", "player.app"),
                setOf("launcher.app", "player.app"),
                DisplayScopeDecision(allowsDisplay = true)
            ),
            arrayOf(
                "known visibility waits for a selected app",
                true,
                true,
                true,
                setOf("player.app"),
                setOf("launcher.app"),
                DisplayScopeDecision(
                    allowsDisplay = false,
                    blockReason = DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP
                )
            )
        )
    }
}
