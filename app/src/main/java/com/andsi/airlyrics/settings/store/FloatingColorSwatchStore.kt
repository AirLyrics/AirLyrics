package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.prefs.prefs
import org.json.JSONArray

/** Shared RGB favorites, independent of both applied colors and style resets. */
internal object FloatingColorSwatchStore {
    private const val PREFS_NAME = "floating_color_swatches"
    private const val KEY_COLORS = "rgb_colors"

    fun colors(context: Context): List<Int> {
        val serialized = prefs(context, PREFS_NAME).getString(KEY_COLORS) ?: return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val rgb = array.optLong(index, -1L)
                    if (rgb in 0L..0xFFFFFFL) add(AirColorUtils.opaqueRgb(rgb.toInt()))
                }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(context: Context, color: Int): Boolean {
        val rgb = AirColorUtils.opaqueRgb(color)
        val previous = colors(context)
        if (rgb in previous) return false
        writeColors(context, previous + rgb)
        return true
    }

    @Synchronized
    fun remove(context: Context, color: Int): Boolean {
        val rgb = AirColorUtils.opaqueRgb(color)
        val previous = colors(context)
        if (rgb !in previous) return false
        writeColors(context, previous.filterNot { it == rgb })
        return true
    }

    private fun writeColors(context: Context, colors: List<Int>) {
        val array = JSONArray()
        colors.forEach { array.put(it and 0xFFFFFF) }
        // Favorite edits never change the applied style.
        prefs(context, PREFS_NAME).setString(KEY_COLORS, array.toString())
    }
}
