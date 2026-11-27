package com.andsi.airlyrics.ui.model

import android.widget.LinearLayout
import android.widget.TextView

internal interface OptionControlsHost {
    fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout
    fun optionButton(item: OptionItem): TextView
    fun applyOptionButtonState(button: TextView, title: String, selected: Boolean)
}
