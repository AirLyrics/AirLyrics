package com.andsi.airlyrics.ui.pages.floating

internal enum class ColorSaveStatus { UNSAVED, SAVING, SAVED, FAILED }

internal class FloatingColorSaveState(initialColor: Int, saved: Boolean) {
    class Request internal constructor(val color: Int, internal val revision: Long)

    var color: Int = initialColor
        private set
    var status = if (saved) ColorSaveStatus.SAVED else ColorSaveStatus.UNSAVED
        private set
    private var revision = 0L
    private var edited = false
    private var request: Request? = null
    var isClosed = false
        private set

    val canSave: Boolean
        get() = !isClosed && request == null && status != ColorSaveStatus.SAVED

    fun edit(color: Int, saved: Boolean) {
        if (isClosed || color == this.color) return
        this.color = color
        revision++
        edited = true
        // An older write must not confirm a new draft, even if the user returns to its RGB.
        status = if (request == null && saved) ColorSaveStatus.SAVED else ColorSaveStatus.UNSAVED
    }

    fun begin(): Request? {
        if (!canSave) return null
        return Request(color, revision).also {
            request = it
            status = ColorSaveStatus.SAVING
        }
    }

    fun complete(completed: Request, success: Boolean): Boolean {
        if (isClosed || request !== completed) return false
        request = null
        status = if (completed.revision != revision || completed.color != color) {
            ColorSaveStatus.UNSAVED
        } else if (success) ColorSaveStatus.SAVED else ColorSaveStatus.FAILED
        return true
    }

    fun reconcile(saved: Boolean) {
        if (isClosed || request != null || status == ColorSaveStatus.FAILED) return
        if (!edited || (status == ColorSaveStatus.SAVED && !saved)) {
            status = if (saved) ColorSaveStatus.SAVED else ColorSaveStatus.UNSAVED
        }
    }

    fun close() {
        isClosed = true
        request = null
    }
}
