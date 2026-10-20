package com.andsi.airlyrics.ui.pages.settings

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.LocalLyricsRowView
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.*
import kotlin.math.roundToInt

internal fun LocalLyricsUiItem.selectionKey() = indexKey.ifBlank { "root:$name" }

/** Views are bounded by the viewport; selection and expansion belong to song keys. */
internal class SavedLyricsListAdapter(
    private val host: MainUiHost,
    private val selecting: () -> Boolean,
    private val selected: (String) -> Boolean,
    private val toggle: (LocalLyricsUiItem) -> Unit,
    private val changed: () -> Unit
) : BaseAdapter() {
    var items: List<LocalLyricsUiItem> = emptyList()
        private set
    var keys: List<String> = emptyList()
        private set
    private val ids = mutableMapOf<String, Long>()
    private var nextId = 0L
    private val expanded = mutableSetOf<String>()
    var progress = 0f

    fun submit(items: List<LocalLyricsUiItem>) {
        if (this.items == items) return
        this.items = items
        keys = items.map { it.selectionKey() }
        keys.forEach { ids.getOrPut(it) { nextId++ } }
        notifyDataSetChanged()
    }
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = ids.getValue(items[position].selectionKey())
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder = convertView?.tag as? Holder ?: Holder(items[position])
        holder.bind(items[position])
        return holder.wrapper
    }

    fun refresh(list: ListView, animate: Boolean = true) {
        for (index in 0 until list.childCount) (list.getChildAt(index).tag as? Holder)?.update(animate)
    }

    fun attachedItems(list: ListView): List<SavedLyricsDragItem> = (0 until list.childCount).mapNotNull { index ->
        val view = list.getChildAt(index)
        val holder = view.tag as? Holder ?: return@mapNotNull null
        SavedLyricsDragItem(holder.item.selectionKey(), view)
    }

    private inner class Holder(initial: LocalLyricsUiItem) {
        var item = initial
        val row = host.localLyricsRow(initial, onLyricsChanged = { changed() }, onClickIntercept = {
            if (selecting()) { toggle(item); true } else false
        }) as LocalLyricsRowView
        val checkbox = CheckBox(host).apply {
            buttonTintList = ColorStateList.valueOf(host.colorAccent)
            minimumWidth = host.dp(AirUiTokens.Layout.IconTouchSize)
            minimumHeight = minimumWidth
            setOnClickListener { toggle(item) }
        }
        val slot = FrameLayout(host).apply {
            clipChildren = true
            addView(checkbox, FrameLayout.LayoutParams(host.dp(AirUiTokens.Layout.IconTouchSize),
                host.dp(AirUiTokens.Layout.IconTouchSize), Gravity.CENTER))
        }
        val wrapper = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Padding belongs to the wrapper so the card's bottom border stays inside its bounds.
            setPadding(0, host.dp(AirUiTokens.Space.Xxl), 0, 0)
            addView(slot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tag = this@Holder
        }
        val highlight = SavedLyricsSelectionMotion(row, 0f, 180L) { value ->
            (row.background as GradientDrawable).apply {
                setStroke(host.dp(AirUiTokens.Stroke.Hairline), ColorUtils.blendARGB(host.colorStroke, host.colorAccent, value))
                setColor(ColorUtils.blendARGB(host.colorSurfaceLight, host.colorAccent, 0.08f * value))
            }
        }

        fun bind(value: LocalLyricsUiItem) {
            val rebound = item != value
            item = value
            row.bind(value, expanded)
            checkbox.contentDescription = host.getString(R.string.ui_batch_select_item, value.displayTitle)
            if (rebound) { row.animate().cancel(); row.scaleX = 1f; row.scaleY = 1f }
            update(animate = false)
        }

        fun update(animate: Boolean) {
            val checked = selecting() && selected(item.selectionKey())
            checkbox.isChecked = checked
            row.isSelected = checked
            highlight.moveTo(if (checked) 1f else 0f, animate)
            checkbox.isEnabled = selecting()
            checkbox.isVisible = selecting() || progress > 0f
            checkbox.importantForAccessibility = if (selecting()) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            checkbox.alpha = progress
            checkbox.scaleX = 0.75f + 0.25f * progress
            checkbox.scaleY = checkbox.scaleX
            val width = (host.dp(AirUiTokens.Layout.IconTouchSize) * progress).roundToInt()
            if (slot.layoutParams.width != width) slot.layoutParams = slot.layoutParams.apply { this.width = width }
        }
    }
}
