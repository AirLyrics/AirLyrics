package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.*

/** A reusable song card. Click callbacks always read the currently bound item. */
@SuppressLint("ViewConstructor")
internal class LocalLyricsRowView(
    private val host: MainUiHost,
    private val onOpen: (LocalLyricsUiItem) -> Unit,
    private val onClickIntercept: (() -> Boolean)? = null,
    badgeText: CharSequence? = null
) : LinearLayout(host) {
    private lateinit var item: LocalLyricsUiItem
    private val title = TextView(host)
    private val subtitle = TextView(host)
    private val meta = TextView(host)
    private val textLayouts = mutableListOf<ExpandableTextLayout>()

    init {
        orientation = VERTICAL
        val horizontal = host.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
        setPadding(horizontal, host.dp(AirUiTokens.Space.Xxl), horizontal, host.dp(AirUiTokens.Space.Xxl))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = host.dp(AirUiTokens.Space.Xxl)
        }
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Md).toFloat()
            setColor(host.colorSurfaceLight)
            setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
        }
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale + 0.01f)
        if (!badgeText.isNullOrBlank()) addView(TextView(host).apply {
            text = badgeText
            textSize = AirUiTokens.TextSize.Tiny
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(host.colorAccentText)
            setPadding(0, 0, 0, host.dp(AirUiTokens.Space.Sm))
        })
        title.textSize = AirUiTokens.TextSize.Body
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(host.colorTextStrong)
        subtitle.textSize = AirUiTokens.TextSize.Caption
        subtitle.typeface = Typeface.DEFAULT_BOLD
        subtitle.setTextColor(host.colorAccentText)
        subtitle.setPadding(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
        meta.textSize = AirUiTokens.TextSize.Caption
        meta.setTextColor(host.colorTextMuted)
        meta.setPadding(0, host.dp(AirUiTokens.Space.Xxs), 0, 0)
        listOf(title, subtitle, meta).forEach { text ->
            val layout = host.expandableText(text)
            textLayouts += layout
            addView(layout)
        }
        setOnClickListener { if (onClickIntercept?.invoke() != true) onOpen(item) }
    }

    fun bind(value: LocalLyricsUiItem, expanded: MutableSet<String>? = null) {
        item = value
        val key = value.indexKey.ifBlank { value.name }
        setTag(R.id.interaction_anchor, "lyrics:$key")
        title.text = value.displayTitle
        subtitle.text = host.getString(R.string.ui_local_lyrics_subtitle_type, value.subtitle, value.typeText)
        meta.text = value.metaText
        expanded?.let { state -> textLayouts.forEachIndexed { index, layout -> layout.bindExpansion(state, "$key/$index") } }
    }
}
