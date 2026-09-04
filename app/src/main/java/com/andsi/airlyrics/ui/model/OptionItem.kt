package com.andsi.airlyrics.ui.model

import android.view.View
import android.widget.TextView

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
    val enabled: Boolean = true,
    val onClick: (View) -> Unit,
    val onSubtitleViewCreated: ((TextView) -> Unit)? = null
)
