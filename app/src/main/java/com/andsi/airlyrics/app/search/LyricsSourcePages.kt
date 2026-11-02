package com.andsi.airlyrics.app.search

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
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
    private val mountedPages = mutableSetOf<Page>()
    var blocksInteraction = false
        set(value) {
            if (field == value) return
            field = value
            mountedPages.forEach(Page::finish)
        }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        blocksInteraction || super.onInterceptTouchEvent(event)

    // This pane only absorbs input while another pane is taking over; it has no click action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        blocksInteraction || super.onTouchEvent(event)

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
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
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

    private enum class SourceVisual { RESULTS, LOADING, EMPTY, ERROR }

    // Motion Prompt #069 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#069
    // Motion Prompt #072 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#072
    // The status occupies the same first-row space; no shimmer or extra illustration is needed.
    private inner class Page(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        val list = RecyclerView(context).apply {
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(24))
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(context, VERTICAL).apply {
                setDrawable(GradientDrawable().apply {
                    setColor(palette.surfaceLight)
                    setSize(1, dp(1))
                })
            })
        }
        private val rows = Rows().also { list.adapter = it }
        private val loading = LyricsLoadingView(context, palette)
        private val messageTitle = TextView(context).apply {
            textSize = 19f
            setTextColor(palette.textStrong)
            setTypeface(typeface, Typeface.BOLD)
        }
        private val messageDetail = TextView(context).apply {
            textSize = 13f
            setTextColor(palette.textMuted)
        }
        private val message = LinearLayout(context).apply {
            orientation = VERTICAL
            minimumHeight = dp(80)
            setPadding(dp(4), dp(16), dp(4), dp(16))
            addView(messageTitle)
            addView(messageDetail, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val selectable = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            setBackgroundResource(selectable.resourceId)
            isFocusable = true
            setOnClickListener { if (visual != SourceVisual.RESULTS && visual != SourceVisual.LOADING)
                source?.let(session::retry) }
        }
        // The clickable status row must pass vertical drags up to the AppBarLayout.
        private val status = NestedScrollView(context).apply {
            minimumHeight = dp(80)
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(FrameLayout(context).apply {
                minimumHeight = dp(80)
                addView(loading, FrameLayout.LayoutParams(-1, dp(80)))
                addView(message, FrameLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -2))
            visibility = GONE
        }
        var source: PlainLyricsSearchSource? = null
        var generation = -1L
        private var shownResult: SourceResults? = null
        private var visual = SourceVisual.RESULTS
        private var resetScrollOnResult = false

        init {
            // Keep this scroll child visible even without rows so blank space can expand the AppBarLayout.
            frame.addView(list, FrameLayout.LayoutParams(-1, -1))
            frame.addView(status, FrameLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
            val sameSource = source == key
            val samePage = sameSource && generation == session.resultsGeneration
            if (!samePage) save()
            val previous = shownResult.takeIf { sameSource }
            source = key
            generation = session.resultsGeneration
            val result = results.getValue(key)
            shownResult = result
            if (!samePage && result.loading && result.candidates.isEmpty()) resetScrollOnResult = true
            val nextVisual = when {
                result.candidates.isNotEmpty() -> SourceVisual.RESULTS
                result.loading -> SourceVisual.LOADING
                result.error != null -> SourceVisual.ERROR
                else -> SourceVisual.EMPTY
            }
            val animate = sameSource && frame.isShown && !blocksInteraction &&
                ValueAnimator.areAnimatorsEnabled()
            val firstResults = animate && previous?.candidates?.isEmpty() == true &&
                result.candidates.isNotEmpty()
            val leavingResults = animate && previous?.candidates?.isNotEmpty() == true &&
                result.loading && result.candidates.isEmpty()

            list.animate().cancel()
            list.translationY = 0f
            rows.canOpen = result.candidates.isNotEmpty()
            if (leavingResults) {
                // Keep the old rows only for their 120 ms exit. They cannot open a stale preview.
                list.animate().alpha(0f).translationY(-dp(4).toFloat()).setDuration(120)
                    .withEndAction {
                        if (shownResult === result) {
                            rows.submit(key, emptyList())
                            list.scrollToPosition(0)
                            resetScrollOnResult = false
                        }
                        list.alpha = 1f
                        list.translationY = 0f
                    }.start()
            } else {
                if (result.loading && result.candidates.isEmpty()) session.pagePositions.remove(key)
                rows.submit(key, result.candidates)
                list.alpha = if (firstResults) 0f else 1f
                if (resetScrollOnResult && result.candidates.isNotEmpty()) {
                    list.scrollToPosition(0)
                    resetScrollOnResult = false
                }
                if (firstResults) revealFirstRows(result)
            }
            showStatus(nextVisual, result, animate)
            if (!samePage && !leavingResults) {
                session.pagePositions[key]?.let { list.layoutManager?.onRestoreInstanceState(it) }
                    ?: list.scrollToPosition(0)
            }
            if (blocksInteraction) finish()
        }

        private fun revealFirstRows(result: SourceResults) {
            list.doOnPreDraw {
                list.alpha = 1f
                if (shownResult !== result || !frame.isShown || blocksInteraction) return@doOnPreDraw
                val first = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION) return@doOnPreDraw
                for (index in 0 until 4) {
                    val row = list.findViewHolderForAdapterPosition(first + index)?.itemView ?: continue
                    row.animate().cancel()
                    row.alpha = 0f
                    row.translationY = dp(8).toFloat()
                    row.animate().alpha(1f).translationY(0f).setStartDelay(index * 34L)
                        .setDuration(190).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
                }
            }
        }

        private fun showStatus(next: SourceVisual, result: SourceResults, animate: Boolean) {
            val previous = visual
            visual = next
            if (next != SourceVisual.RESULTS && next != SourceVisual.LOADING) {
                messageTitle.text = if (next == SourceVisual.ERROR) errorText(result.error!!) else
                    context.getString(R.string.search_empty)
                messageDetail.text = context.getString(R.string.search_retry)
            }
            if (previous == next && status.isVisible) return
            status.animate().cancel()
            loading.animate().cancel()
            message.animate().cancel()
            if (next == SourceVisual.RESULTS) {
                status.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                if (animate && status.isVisible) {
                    status.animate().alpha(0f).translationY(-dp(4).toFloat()).setDuration(110)
                        .withEndAction { if (visual == SourceVisual.RESULTS) status.visibility = GONE }.start()
                } else status.visibility = GONE
                return
            }
            status.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
            status.visibility = VISIBLE
            val nextView = if (next == SourceVisual.LOADING) loading else message
            val previousView = if (previous == SourceVisual.LOADING) loading else message
            if (animate && previous != SourceVisual.RESULTS && previousView !== nextView) {
                nextView.visibility = VISIBLE
                nextView.alpha = 0f
                nextView.translationY = dp(8).toFloat()
                previousView.visibility = VISIBLE
                previousView.alpha = 1f
                previousView.animate().alpha(0f).setDuration(90).withEndAction {
                    if (visual == next) previousView.visibility = GONE
                }.start()
                nextView.animate().alpha(1f).translationY(0f).setStartDelay(25)
                    .setDuration(175).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            } else {
                loading.visibility = if (next == SourceVisual.LOADING) VISIBLE else GONE
                message.visibility = if (next == SourceVisual.LOADING) GONE else VISIBLE
                nextView.alpha = 1f
                nextView.translationY = 0f
            }
            if (previous == SourceVisual.RESULTS && animate) {
                status.alpha = 0f
                status.translationY = dp(8).toFloat()
                status.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            } else {
                status.alpha = 1f
                status.translationY = 0f
            }
        }

        fun finish() {
            list.animate().cancel()
            for (index in 0 until list.childCount) {
                list.getChildAt(index).apply {
                    animate().cancel()
                    alpha = 1f
                    translationY = 0f
                }
            }
            status.animate().cancel()
            loading.animate().cancel()
            message.animate().cancel()
            list.alpha = 1f
            list.translationY = 0f
            status.alpha = 1f
            status.translationY = 0f
            loading.alpha = 1f
            loading.translationY = 0f
            message.alpha = 1f
            message.translationY = 0f
            // The preview owns the loading indicator during the handoff.
            status.visibility = if (visual == SourceVisual.RESULTS ||
                (blocksInteraction && visual == SourceVisual.LOADING)) GONE else VISIBLE
            loading.visibility = if (visual == SourceVisual.LOADING) VISIBLE else GONE
            message.visibility = if (visual == SourceVisual.EMPTY || visual == SourceVisual.ERROR) VISIBLE else GONE
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<Page>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = sources[position].ordinal.toLong()
        override fun getItemCount() = sources.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Page(FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) })
                .also(mountedPages::add)
        override fun onBindViewHolder(holder: Page, position: Int) {
            mountedPages.add(holder)
            holder.bind(sources[position])
        }
        override fun onViewRecycled(holder: Page) {
            holder.save()
            holder.finish()
            holder.source = null
            mountedPages.remove(holder)
        }
    }

    private inner class Row(val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        val titleLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
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
            titleLine.addView(heading, LayoutParams(0, -2, 1f))
            titleLine.addView(duration, LayoutParams(-2, -2).apply { marginStart = dp(16) })
            box.addView(titleLine)
            box.addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val selectable = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            box.setBackgroundResource(selectable.resourceId)
            box.isFocusable = true
        }
    }

    private inner class Rows : RecyclerView.Adapter<Row>() {
        private var source: PlainLyricsSearchSource? = null
        private var candidates = emptyList<LyricsCandidate>()
        var canOpen = false
        init { setHasStableIds(true) }
        override fun getItemCount() = candidates.size
        override fun getItemId(position: Int): Long {
            val candidate = candidates[position]
            var hash = -3750763034362895579L
            candidate.id.forEach { hash = (hash xor it.code.toLong()) * 1099511628211L }
            return hash xor (candidate.source.ordinal.toLong() shl 56)
        }
        fun submit(key: PlainLyricsSearchSource, next: List<LyricsCandidate>) {
            val before = candidates
            val beforeSource = source
            if (beforeSource == key && before == next) return
            source = key
            candidates = next
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = before.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    beforeSource == key && before[oldItemPosition].id == next[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    before[oldItemPosition] == next[newItemPosition]
            }).dispatchUpdatesTo(this@Rows)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(
            LinearLayout(context).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }
        )
        override fun onBindViewHolder(holder: Row, position: Int) {
            val candidate = candidates[position]
            holder.duration.text = candidate.song.durationMs.takeIf { it > 0 }?.let {
                String.format(Locale.ROOT, "%d:%02d", it / 60000, it / 1000 % 60)
            }.orEmpty()
            holder.heading.text = candidate.song.title
            holder.detail.text = listOf(candidate.song.artist, candidate.song.album)
                .filter { it.isNotBlank() }.joinToString(" · ")
            holder.box.setOnClickListener { if (canOpen) session.preview(candidate) }
        }
        override fun onViewRecycled(holder: Row) {
            holder.box.animate().cancel()
            holder.box.alpha = 1f
            holder.box.translationY = 0f
        }
    }

    override fun onDetachedFromWindow() {
        mountedPages.forEach(Page::finish)
        super.onDetachedFromWindow()
    }

    fun dispose() {
        mountedPages.forEach(Page::finish)
        tabs.clearOnTabSelectedListeners()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
