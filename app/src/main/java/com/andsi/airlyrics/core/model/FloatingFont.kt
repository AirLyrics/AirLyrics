package com.andsi.airlyrics.core.model

/** A stable identity shared by the font catalogue, downloads, imports and selection. */
data class FloatingFont(
    val id: String,
    val family: FloatingLyricsFontFamily,
    val displayName: String?,
    val source: FloatingFontSource,
    val available: Boolean,
    /** Full downloadable or imported file size; excludes lightweight preview resources. */
    val sizeBytes: Long? = null,
    val collectionId: String? = null
)

/** A single stored TTC shared by its independently selectable members. */
data class FloatingFontCollection(
    val id: String,
    val displayName: String,
    val sizeBytes: Long
)

enum class FloatingFontSource {
    SYSTEM, PRESET, DOWNLOAD, IMPORTED
}
