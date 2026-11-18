package com.andsi.airlyrics.ui.pages.floating

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingFontPanelStateTest {
    @Test
    fun deleteNeedsAReadableSecondClickAndCancellationStartsFresh() {
        val confirmation = FontDeleteConfirmation()
        assertEquals(FontDeleteConfirmation.Click.ARMED, confirmation.click(1000L))
        assertTrue(confirmation.isArmed)
        assertEquals(FontDeleteConfirmation.Click.WAIT, confirmation.click(1001L))
        assertEquals(FontDeleteConfirmation.Click.WAIT, confirmation.click(1449L))
        assertEquals(FontDeleteConfirmation.Click.DELETE, confirmation.click(1450L))
        assertFalse(confirmation.isArmed)

        confirmation.click(2000L)
        confirmation.cancel()
        assertEquals(FontDeleteConfirmation.Click.ARMED, confirmation.click(3000L))
        assertTrue(confirmation.isArmed)
    }

    @Test
    fun cadenceNeverInventsBytesOrCompletionAndHasPausesAndBursts() {
        val cadence = FontDownloadCadence(Random(42))
        repeat(20) { assertEquals(0, cadence.advance(0)) }
        var previous = 0
        val increments = mutableSetOf<Int>()
        repeat(200) {
            val shown = cadence.advance(75)
            assertTrue(shown in previous..75)
            increments += shown - previous
            previous = shown
        }
        assertEquals(75, previous)
        assertTrue(0 in increments)
        assertTrue(increments.any { it > 5 })
        repeat(200) { assertTrue(cadence.advance(100) <= 99) }
        assertEquals(99, cadence.displayed)
    }

    @Test
    fun differentDownloadsDoNotHaveAnIdenticalDeliveryRhythm() {
        fun rhythm(seed: Int): List<Pair<Int, Long>> {
            val cadence = FontDownloadCadence(Random(seed))
            return List(20) { cadence.advance(90) to cadence.delayMs }
        }
        assertFalse(rhythm(1) == rhythm(2))
    }
}
