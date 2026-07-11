package com.andsi.airlyrics.ui.model

import android.view.View

internal data class OptionItem(
    val title: String,
    val selected: Boolean,
    val action: () -> Unit
)

internal data class KeyedOptionItem(
    val key: String,
    val title: String,
    val selected: Boolean,
    val action: () -> Unit
)

internal data class FloatingSettingTile(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val onClick: (View) -> Unit
)

internal object FloatingUiTags {
    const val LOCK_BUTTON = "floating_control_lock_button"
    const val CLICK_THROUGH_BUTTON = "floating_control_click_through_button"

    fun tile(title: String): String = "floating_tile:$title"
    fun tileSubtitle(title: String): String = "floating_tile_subtitle:$title"
}
