package com.andsi.airlyrics.app.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScopeAppSelectionTest {
    @Test
    fun filter_matchesTrimmedLabelOrPackageNameWithoutChangingSelection() {
        val selected = mutableSetOf("com.example.music")
        val selection = selection(selected)

        selection.filter("  PLAYER  ")

        assertEquals(listOf("com.example.video"), selection.visiblePackageNames())
        assertEquals(setOf("com.example.music"), selected)

        selection.filter("example.music")
        assertEquals(listOf("com.example.music"), selection.visiblePackageNames())
    }

    @Test
    fun submitChoices_keepsTheActiveFilterAndCanPruneRemovedPackages() {
        val selected = mutableSetOf("com.example.music", "removed.app")
        val selection = selection(selected)
        selection.filter("music")

        selection.submitChoices(
            choices = listOf(
                choice("com.example.music", "Music"),
                choice("com.example.podcast", "Music podcasts")
            ),
            pruneMissingSelections = true
        )

        assertEquals(
            listOf("com.example.music", "com.example.podcast"),
            selection.visiblePackageNames()
        )
        assertEquals(setOf("com.example.music"), selected)
    }

    @Test
    fun failedRefresh_canKeepSelectionsThatAreTemporarilyMissing() {
        val selected = mutableSetOf("com.example.music", "temporarily.missing")
        val selection = selection(selected)

        selection.submitChoices(emptyList(), pruneMissingSelections = false)

        assertTrue(selection.visibleChoices.isEmpty())
        assertEquals(setOf("com.example.music", "temporarily.missing"), selected)
    }

    @Test
    fun toggleAll_appliesToTheFullChoiceListEvenWhileFiltered() {
        val selected = mutableSetOf<String>()
        val selection = selection(selected)
        selection.filter("music")

        selection.toggleAll()

        assertEquals(setOf("com.example.music", "com.example.video"), selected)
        assertTrue(selection.areAllChoicesSelected())

        selection.toggleAll()
        assertTrue(selected.isEmpty())
        assertFalse(selection.areAllChoicesSelected())
    }

    @Test
    fun individualSelectionAndEmptyChoiceState_areReportedConsistently() {
        val selected = mutableSetOf<String>()
        val selection = selection(selected)

        selection.setSelected("com.example.music", true)
        assertTrue(selection.isSelected("com.example.music"))
        assertFalse(selection.areAllChoicesSelected())

        selection.setSelected("com.example.video", true)
        assertTrue(selection.areAllChoicesSelected())

        selection.submitChoices(emptyList(), pruneMissingSelections = true)
        assertFalse(selection.hasChoices())
        assertFalse(selection.areAllChoicesSelected())
        assertTrue(selected.isEmpty())
    }

    private fun selection(selected: MutableSet<String>): DisplayScopeAppSelection {
        return DisplayScopeAppSelection(
            choices = listOf(
                choice("com.example.music", "Music Box"),
                choice("com.example.video", "Video Player")
            ),
            selectedPackages = selected
        )
    }

    private fun choice(packageName: String, label: String): DisplayScopeAppChoice {
        return DisplayScopeAppChoice(packageName, label, icon = null)
    }

    private fun DisplayScopeAppSelection.visiblePackageNames(): List<String> {
        return visibleChoices.map(DisplayScopeAppChoice::packageName)
    }
}
