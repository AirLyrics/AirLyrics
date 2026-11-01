package com.andsi.airlyrics.app.viewmodel

/** Keeps visible players in their first-seen order while replacing their latest controller data. */
internal class MediaPlayerOrder {
    private var orderedPackages: List<String> = emptyList()

    fun <T> reconcile(controllersByPackage: Map<String, T>): List<T> {
        val nextOrder = orderedPackages.filterTo(mutableListOf()) { it in controllersByPackage }
        val retainedPackages = nextOrder.toMutableSet()
        controllersByPackage.keys.forEach { packageName ->
            if (retainedPackages.add(packageName)) nextOrder += packageName
        }
        orderedPackages = nextOrder
        return nextOrder.map(controllersByPackage::getValue)
    }
}
