package com.andsi.airlyrics.settings.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.andsi.airlyrics.core.color.AirColorUtils
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Shared RGB favorites, independent of both applied colors and style resets. */
internal object FloatingColorSwatchStore {
    private const val PREFS_NAME = "floating_color_swatches"
    private const val KEY_COLORS = "rgb_colors"
    private val mutations = Any()
    private val version = MutableStateFlow(0L)
    val changes = version.asStateFlow()

    private class WriteSnapshot(
        val preferences: SharedPreferences, val previous: List<Int>, val pending: Boolean = true
    )
    @Volatile private var writeSnapshot: WriteSnapshot? = null

    private fun preferences(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun colors(context: Context): List<Int> {
        val preferences = preferences(context)
        // commit() publishes to SharedPreferences memory before the disk write finishes.
        // Readers see the last confirmed list and never block the UI on that disk write.
        val serialized: String?
        while (true) {
            val before = writeSnapshot
            val value = preferences.getString(KEY_COLORS, null)
            if (before !== writeSnapshot) continue
            if (before?.pending == true && before.preferences === preferences) return before.previous
            serialized = value
            break
        }
        if (serialized == null) return emptyList()
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

    /** Run off the main thread. Returns after disk confirmation; duplicate saves are idempotent. */
    fun save(context: Context, color: Int): Boolean = synchronized(mutations) {
        val rgb = AirColorUtils.opaqueRgb(color)
        val previous = colors(context)
        if (rgb in previous) return@synchronized false
        val preferences = preferences(context)
        val oldValue = preferences.getString(KEY_COLORS, null)
        writeSnapshot = WriteSnapshot(preferences, previous)
        try {
            // KTX edit discards the commit result needed to detect a failed write.
            @SuppressLint("UseKtx")
            val committed = preferences.edit().putString(KEY_COLORS, serialize(previous + rgb)).commit()
            if (!committed) throw IOException("Could not persist color swatch")
        } catch (failure: Exception) {
            // A failed commit still changed the in-memory map. Restore it so reopening
            // the editor cannot mistake the failed write for a saved favorite.
            preferences.edit {
                if (oldValue == null) remove(KEY_COLORS) else putString(KEY_COLORS, oldValue)
            }
            throw failure
        } finally {
            writeSnapshot = WriteSnapshot(preferences, previous, pending = false)
            version.value += 1L
        }
        true
    }

    fun remove(context: Context, color: Int): Boolean = synchronized(mutations) {
        val rgb = AirColorUtils.opaqueRgb(color)
        val previous = colors(context)
        if (rgb !in previous) return@synchronized false
        // SharedPreferences applies the deletion in memory before writing it to disk.
        preferences(context).edit {
            putString(KEY_COLORS, serialize(previous.filterNot { it == rgb }))
        }
        version.value += 1L
        true
    }

    private fun serialize(colors: List<Int>): String = JSONArray().apply {
        colors.forEach { put(it and 0xFFFFFF) }
    }.toString()
}
