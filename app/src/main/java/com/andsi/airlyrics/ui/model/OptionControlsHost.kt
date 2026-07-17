package com.andsi.airlyrics.ui.model

import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

internal interface OptionControlsHost {
    fun optionGrid(items: List<OptionItem>): LinearLayout
    fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout
    fun optionButton(item: OptionItem): TextView
    fun applyOptionButtonState(button: TextView, title: String, selected: Boolean)

    fun sliderRow(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ): LinearLayout

    fun colorControl(title: String, color: Int, onChanged: (Int) -> Unit): LinearLayout
    fun colorPreviewBackground(color: Int): GradientDrawable
}
