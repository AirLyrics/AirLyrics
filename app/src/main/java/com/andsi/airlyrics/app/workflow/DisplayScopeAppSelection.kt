package com.andsi.airlyrics.app.workflow

import android.graphics.drawable.Drawable
import java.util.Locale

internal data class DisplayScopeAppChoice(
    val packageName: String,
    val label: String,
    val icon: Drawable?
) {
    val searchText: String = "$label\n$packageName".lowercase(Locale.ROOT)
}

/** Search and selection state for the asynchronously populated display-scope picker. */
internal class DisplayScopeAppSelection(
    private var choices: List<DisplayScopeAppChoice>,
    private val selectedPackages: MutableSet<String>
) {
    private var normalizedQuery = ""

    var visibleChoices: List<DisplayScopeAppChoice> = choices
        private set

    fun filter(query: String) {
        normalizedQuery = query.trim().lowercase(Locale.ROOT)
        applyFilter()
    }

    fun submitChoices(
        choices: List<DisplayScopeAppChoice>,
        pruneMissingSelections: Boolean
    ) {
        this.choices = choices
        if (pruneMissingSelections) {
            selectedPackages.retainAll(choices.mapTo(hashSetOf(), DisplayScopeAppChoice::packageName))
        }
        applyFilter()
    }

    fun hasChoices(): Boolean = choices.isNotEmpty()

    fun areAllChoicesSelected(): Boolean {
        return choices.isNotEmpty() && choices.all { it.packageName in selectedPackages }
    }

    fun toggleAll() {
        if (areAllChoicesSelected()) {
            selectedPackages.clear()
        } else {
            selectedPackages.addAll(choices.map(DisplayScopeAppChoice::packageName))
        }
    }

    fun isSelected(packageName: String): Boolean = packageName in selectedPackages

    fun setSelected(packageName: String, selected: Boolean) {
        if (selected) {
            selectedPackages += packageName
        } else {
            selectedPackages -= packageName
        }
    }

    private fun applyFilter() {
        visibleChoices = if (normalizedQuery.isEmpty()) {
            choices
        } else {
            choices.filter { it.searchText.contains(normalizedQuery) }
        }
    }
}
