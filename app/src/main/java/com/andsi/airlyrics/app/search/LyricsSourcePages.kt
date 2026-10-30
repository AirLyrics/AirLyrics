package com.andsi.airlyrics.app.search

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.google.android.material.tabs.TabLayout
import java.util.Locale

/** Source keys own pages; positions are never used as request identities. */
@SuppressLint("ViewConstructor") // Created with session dependencies, never inflated from XML.
internal class LyricsSourcePages(
    context: Context,
    private val palette: AirLyricsPalette,
    private val session: LyricsSearchSession,
    private val errorText: (Throwable) -> String
) : LinearLayout(context) {
    private val tabs = object : TabLayout(context) {
        override fun selectTab(tab: Tab?, updateIndicator: Boolean) {
            // Clicks update the destination; the pager owns movement and visual selection.
            super.selectTab(tab, false)
        }
    }
    private val pager = ViewPager2(context)
    private var sources = emptyList<PlainLyricsSearchSource>()
    private var results = emptyMap<PlainLyricsSearchSource, SourceResults>()
    private val pages = PageAdapter()

    init {
        orientation = VERTICAL
        tabs.setBackgroundColor(palette.background)
        tabs.tabMode = TabLayout.MODE_SCROLLABLE
        tabs.setTabTextColors(palette.textMuted, palette.textStrong)
        tabs.setSelectedTabIndicatorColor(palette.accent)
        tabs.setSelectedTabIndicator(GradientDrawable().apply {
            setColor(palette.accent)
            cornerRadius = dp(3).toFloat()
            setSize(dp(24), dp(3))
        })
        tabs.isTabIndicatorFullWidth = false
        tabs.tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
        // A pressed ripple would highlight the destination before the indicator arrives.
        tabs.tabRippleColor = null
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (pager.currentItem != tab.position) {
                    pager.setCurrentItem(tab.position, ValueAnimator.areAnimatorsEnabled())
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = onTabSelected(tab)
        })
        addView(tabs, LayoutParams(-1, -2))
        pager.adapter = pages
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                sources.getOrNull(position)?.let { session.activeSource = it }
                if (pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) settleTabSelection()
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                tabs.setScrollPosition(position, positionOffset, false)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) settleTabSelection()
            }
        })
        addView(pager, LayoutParams(-1, 0, 1f))
    }

    fun render(next: Map<PlainLyricsSearchSource, SourceResults>) {
        val keys = next.keys.toList()
        val previous = results
        results = next
        if (keys != sources) {
            val selected = session.activeSource
            val previousKeys = sources
            sources = keys
            androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = previousKeys.size
                override fun getNewListSize() = keys.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previousKeys[oldItemPosition] == keys[newItemPosition]
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = false
            }).dispatchUpdatesTo(pages)
            tabs.removeAllTabs()
            sources.forEach { source ->
                tabs.addTab(tabs.newTab().apply {
                    customView = sourceTab(source)
                    contentDescription = tabDescription(source)
                }, false)
            }
            val index = sources.indexOf(selected).coerceAtLeast(0)
            if (sources.isNotEmpty()) {
                pager.setCurrentItem(index, false)
                settleTabSelection()
            }
        } else {
            sources.forEachIndexed { index, source ->
                if (previous[source] != next[source]) {
                    tabs.getTabAt(index)?.let { tab ->
                        ((tab.customView as? LinearLayout)?.getChildAt(0) as? TextView)?.text = label(source)
                        // Updating accessibility text also refreshes Material's selected state.
                        val wasSelected = tab.view.isSelected
                        tab.contentDescription = tabDescription(source)
                        tab.view.isSelected = wasSelected
                    }
                    pages.notifyItemChanged(index)
                }
            }
        }
    }

    private fun settleTabSelection() {
        val tab = tabs.getTabAt(pager.currentItem) ?: return
        tabs.selectTab(tab, false)
        tabs.setScrollPosition(tab.position, 0f, true)
    }

    private fun label(source: PlainLyricsSearchSource): String {
        val result = results[source]
        val suffix = when {
            result?.candidates?.isNotEmpty() == true -> result.candidates.size.toString()
            result?.loading == true -> ""
            result?.error != null -> context.getString(R.string.search_failed)
            else -> result?.candidates?.size?.toString().orEmpty()
        }
        val name = context.localizedPlainLyricsProviderName(source.key)
        return if (suffix.isEmpty()) name else "$name  ·  $suffix"
    }

    private fun tabDescription(source: PlainLyricsSearchSource): String =
        if (results[source]?.loading == true) "${label(source)} · ${context.getString(R.string.ui_loading)}" else label(source)

    private fun sourceTab(source: PlainLyricsSearchSource): View = LinearLayout(context).apply {
        gravity=Gravity.CENTER_VERTICAL
        minimumHeight=dp(48)
        setPadding(dp(4),0,dp(4),0)
        addView(TextView(context).apply {
            text=label(source)
            textSize=14f
            isDuplicateParentStateEnabled = true
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(palette.textStrong, palette.textMuted)
            ))
        })
    }

    private inner class Page(val list: RecyclerView) : RecyclerView.ViewHolder(list) {
        var source: PlainLyricsSearchSource? = null
        var generation = -1L
        init {
            list.layoutManager = LinearLayoutManager(context)
            list.itemAnimator = null
            list.addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(context, VERTICAL).apply {
                setDrawable(GradientDrawable().apply {
                    setColor(palette.surfaceLight)
                    setSize(1, dp(1))
                })
            })
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) save()
                }
            })
        }
        fun save() {
            if (generation != session.resultsGeneration) return
            source?.let { key ->
                list.layoutManager?.onSaveInstanceState()?.let { session.pagePositions[key] = it }
            }
        }
        fun bind(key: PlainLyricsSearchSource) {
            save()
            source = key
            generation = session.resultsGeneration
            val result = results.getValue(key)
            if (result.loading && result.candidates.isEmpty()) session.pagePositions.remove(key)
            list.adapter = Rows(key, result)
            session.pagePositions[key]?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<Page>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = sources[position].ordinal.toLong()
        override fun getItemCount() = sources.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Page(
            RecyclerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                clipToPadding = false
                setPadding(0, dp(12), 0, dp(24))
            }
        )
        override fun onBindViewHolder(holder: Page, position: Int) = holder.bind(sources[position])
        override fun onViewRecycled(holder: Page) { holder.save(); holder.source = null }
    }

    private inner class Row(val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        val loading = LyricsLoadingView(context,palette)
        val titleLine = LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        val heading = TextView(context).apply {
            textSize = 19f
            setTextColor(palette.textStrong)
            setTypeface(typeface, Typeface.BOLD)
        }
        val duration = TextView(context).apply { textSize = 12f; setTextColor(palette.textMuted) }
        val detail = TextView(context).apply { textSize = 13f; setTextColor(palette.textMuted) }
        init {
            box.orientation = VERTICAL
            box.minimumHeight = dp(80)
            box.setPadding(dp(4), dp(16), dp(4), dp(16))
            box.addView(loading,LayoutParams(-1,-2))
            titleLine.addView(heading, LayoutParams(0, -2, 1f))
            titleLine.addView(duration, LayoutParams(-2, -2).apply { marginStart = dp(16) })
            box.addView(titleLine)
            box.addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val background = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
            box.setBackgroundResource(background.resourceId)
            box.isFocusable = true
        }
    }

    private inner class Rows(
        val source: PlainLyricsSearchSource,
        val result: SourceResults
    ) : RecyclerView.Adapter<Row>() {
        override fun getItemCount() = result.candidates.size.coerceAtLeast(1)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(
            LinearLayout(context).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }
        )
        override fun onBindViewHolder(holder: Row, position: Int) {
            val candidate = result.candidates.getOrNull(position)
            val showLoading=result.loading && candidate==null
            holder.loading.visibility=if(showLoading) VISIBLE else GONE
            holder.titleLine.visibility=if(showLoading) GONE else VISIBLE
            holder.detail.visibility=if(showLoading) GONE else VISIBLE
            holder.duration.text = candidate?.song?.durationMs?.takeIf { it > 0 }?.let {
                String.format(Locale.ROOT, "%d:%02d", it / 60000, it / 1000 % 60)
            }.orEmpty()
            if (candidate != null) {
                holder.heading.text = candidate.song.title
                holder.detail.text = listOf(candidate.song.artist, candidate.song.album)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                holder.box.setOnClickListener { session.preview(candidate) }
            } else {
                holder.heading.text = when {
                    result.loading -> ""
                    result.error != null -> errorText(result.error)
                    else -> context.getString(R.string.search_empty)
                }
                holder.detail.text = if (result.loading) "" else context.getString(R.string.search_retry)
                holder.box.setOnClickListener(if (result.loading) null else OnClickListener { session.retry(source) })
            }
        }
    }

    fun dispose() { tabs.clearOnTabSelectedListeners() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
