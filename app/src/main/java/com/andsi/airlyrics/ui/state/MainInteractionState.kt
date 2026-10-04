package com.andsi.airlyrics.ui.state

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle

/** Small, data-only interaction snapshots. Updates do not invalidate the main page. */
internal class MainInteractionState(private val savedState: SavedStateHandle? = null) {
    private var entries = savedState?.get<Bundle>(KEY) ?: Bundle()

    fun read(key: String): Bundle? = entries.getBundle(key)?.let(::Bundle)

    fun write(key: String, change: Bundle.() -> Unit) {
        val next = Bundle(entries)
        next.putBundle(key, (read(key) ?: Bundle()).apply(change))
        entries = next
        savedState?.set(KEY, next)
    }

    fun remove(key: String) {
        entries = Bundle(entries).apply { remove(key) }
        savedState?.set(KEY, entries)
    }

    fun removePrefix(prefix: String) {
        entries = Bundle(entries).apply { keySet().filter { it.startsWith(prefix) }.forEach(::remove) }
        savedState?.set(KEY, entries)
    }

    var panel: FloatingPanelId?
        get() = read("panel")?.getString("id")?.let { value -> FloatingPanelId.entries.find { it.name == value } }
        set(value) {
            if (panel == value) return
            removePrefix("panel")
            if (value != null) write("panel") { putString("id", value.name) }
        }

    companion object { private const val KEY = "airlyrics.interactions.v1" }
}

internal enum class FloatingPanelId {
    PRESET, TEXT_COLOR, BACKGROUND, TEXT_SIZE, FONT, FONT_WEIGHT, TEXT_OPACITY,
    SHADOW, WINDOW_LAYOUT, CONTENT, LINE_RANGE, ALIGNMENT, OFFSET, ANIMATION,
    WORD_BY_WORD, HIGHLIGHT_COLOR, DISPLAY_CONTROL, AUTO_HIDE, DISPLAY_SCOPE, SUMMARY
}
