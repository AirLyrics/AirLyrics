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
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.lyrics.search.LyricsCandidate
import com.andsi.airlyrics.lyrics.search.ManualWordSource
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.google.android.material.tabs.TabLayout
import java.util.Locale

/** Source keys own pages; positions are never used as request identities. */
@SuppressLint("ViewConstructor") // Created with session dependencies, never inflated from XML.
internal class LyricsSourcePages(
    context: Context,
    private val palette: AirLyricsPalette,
    private val session: LyricsSearchSession,
    private val errorText: (Throwable) -> String,
    private val onSpotifyIdSearch: () -> Unit,
    private val onWordLyricsSearch: () -> Unit = {}
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
    private var disposed = false
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
                    // A payload keeps the existing page and its scroll snapshot during loading updates.
                    pages.notifyItemChanged(index, source)
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
            result?.loading == true || result?.searched == false -> ""
            result?.error != null -> context.getString(R.string.search_failed)
            else -> result?.candidates?.size?.toString().orEmpty()
        }
        val name = if(result?.wordQuery!=null) context.getString(R.string.search_word_results)
            else context.localizedPlainLyricsProviderName(source.key)
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

    private enum class SourceVisual { RESULTS, LOADING, EMPTY, ERROR, IDLE }

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
        private val rows = Rows()
        private val footer = FooterRows { body -> shownResult?.let { renderWordFooter(body,it) } }
        private val idSearch = com.google.android.material.button.MaterialButton(context).apply {
            text = context.getString(R.string.search_try_spotify_id)
            isAllCaps = false
            letterSpacing = 0f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(4), 0, dp(12), 0)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(palette.accent)
            setIconResource(R.drawable.ic_air_search)
            iconTint = ColorStateList.valueOf(palette.accent)
            stateListAnimator = null
            setOnClickListener { if (!blocksInteraction) onSpotifyIdSearch() }
        }
        private val byTitle = com.google.android.material.button.MaterialButton(context).apply {
            text = context.getString(R.string.search_by_title)
            isAllCaps = false
            letterSpacing = 0f
            minimumHeight = dp(48)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(12), 0)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(palette.accent)
            stateListAnimator = null
            setOnClickListener {
                if(!blocksInteraction) {
                    if(source==PlainLyricsSearchSource.NETEASE) {
                        if(shownResult?.wordDetail!=null) session.backToWordRecordings() else session.searchNeteaseByTitle()
                    }
                    else session.searchSpicyByTitle()
                }
            }
        }
        private val wordSearch = com.google.android.material.button.MaterialButton(context).apply {
            text=context.getString(R.string.search_try_word_search)
            isAllCaps=false
            letterSpacing=0f
            gravity=Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight=dp(48)
            setPadding(dp(4),0,dp(12),0)
            backgroundTintList=ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(palette.accent)
            setIconResource(R.drawable.ic_air_search)
            iconTint=ColorStateList.valueOf(palette.accent)
            stateListAnimator=null
            contentDescription=context.getString(R.string.search_try_word_search)
            setOnClickListener { if(!blocksInteraction) onWordLyricsSearch() }
        }
        private val recordingHeading=TextView(context).apply {
            textSize=14f
            setTextColor(palette.textStrong)
            setPadding(dp(4),dp(4),dp(4),dp(12))
            visibility=GONE
        }
        private fun renderWordFooter(body:LinearLayout,result:SourceResults) {
            body.removeAllViews()
            body.setPadding(dp(4),dp(12),dp(4),dp(16))
            fun row(message:CharSequence,action:(()->Unit)?=null) {
                body.addView(object:androidx.appcompat.widget.AppCompatTextView(context) {
                    override fun getAccessibilityClassName():CharSequence =
                        if(isClickable) android.widget.Button::class.java.name else super.getAccessibilityClassName()
                }.apply {
                    text=message
                    textSize=14f
                    setTextColor(if(action==null) palette.textMuted else palette.accent)
                    setPadding(dp(4),dp(12),dp(4),dp(12))
                    minimumHeight=dp(48)
                    if(action!=null) {
                        isFocusable=true
                        val selectable=android.util.TypedValue()
                        context.theme.resolveAttribute(android.R.attr.selectableItemBackground,selectable,true)
                        setBackgroundResource(selectable.resourceId)
                        setOnClickListener { if(!blocksInteraction) action() }
                    }
                },LayoutParams(-1,-2))
            }
            val detail=result.wordDetail
            if(result.candidates.isEmpty()) {
                when {
                    result.loading -> row(context.getString(if(detail!=null) R.string.search_lyrics_loading else R.string.search_songs_loading))
                    detail!=null && detail.error==null -> row(context.getString(R.string.search_no_lyrics))
                    detail==null && result.wordResults.values.none { it.error!=null } -> row(context.getString(R.string.search_empty))
                }
            }
            if(detail!=null) {
                if(detail.error!=null) row(context.getString(R.string.search_source_retry,
                    context.localizedPlainLyricsProviderName(detail.recording.source.key),
                    context.getString(R.string.search_retry))) { session.openWordRecording(detail.recording,true) }
            } else {
                if(result.error!=null) row(context.getString(R.string.search_failed)) { session.retryWordFailures() }
                ManualWordSource.entries.forEach { provider ->
                    val state=result.wordResults[provider] ?: return@forEach
                    if(state.error!=null && !state.loading) row(context.getString(R.string.search_source_retry,
                        context.localizedPlainLyricsProviderName(provider.key),context.getString(R.string.search_retry))) {
                        session.retryWordFailure(provider)
                    }
                }
                val loading=result.loading || appendLoading
                if(loading && result.candidates.isNotEmpty()) {
                    body.addView(LyricsLoadingView(context,palette).apply {
                        gravity=Gravity.START or Gravity.CENTER_VERTICAL
                        setPadding(dp(4),dp(12),dp(4),dp(12))
                    },LayoutParams(-1,-2))
                }
                if(!loading && result.wordResults.values.any { it.hasMore }) row(context.getString(R.string.search_more_songs)) {
                    ManualWordSource.entries.forEach(session::moreWordRecordings)
                } else if(!result.loading && result.candidates.isNotEmpty() && result.error==null &&
                    result.wordResults.values.none { it.error!=null }) {
                    row(context.getString(R.string.search_no_more_results))
                }
            }
        }
        private val tools = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(idSearch, LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                orientation=HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                addView(wordSearch,LayoutParams(0,-2,1f))
                addView(byTitle,LayoutParams(-2,-2))
            },LayoutParams(-1,-2))
            addView(recordingHeading,LayoutParams(-1,-2))
            visibility = GONE
        }
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
            setOnClickListener { if (visual == SourceVisual.EMPTY || visual == SourceVisual.ERROR)
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
        private var cataloguePosition:android.os.Parcelable?=null
        private var appendLoading=false
        private var restorePageOnCommit=false
        private var restoreCatalogueOnCommit=false

        init {
            // Keep this scroll child visible even without rows so blank space can expand the AppBarLayout.
            val content = FrameLayout(context).apply {
                addView(list, FrameLayout.LayoutParams(-1, -1))
                addView(status, FrameLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            list.adapter=ConcatAdapter(ConcatAdapter.Config.Builder()
                .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build(),rows,footer)
            frame.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(tools, LayoutParams(-1, -2))
                addView(content, LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) save()
                }
            })
        }

        fun save() {
            if (generation != session.resultsGeneration || list.visibility != VISIBLE) return
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
            val openingDetail=result.wordDetail!=null && previous?.wordDetail?.recording?.key!=result.wordDetail.recording.key
            val returningToSongs=previous?.wordDetail!=null && result.wordDetail==null && result.wordQuery!=null &&
                result.wordQuery==previous.wordQuery && result.wordResults===previous.wordResults
            if(!samePage || (result.loading && result.candidates.isEmpty() && !returningToSongs)) {
                cataloguePosition=null
                restoreCatalogueOnCommit=false
            }
            if(openingDetail && previous?.wordDetail==null) {
                cataloguePosition=list.layoutManager?.onSaveInstanceState()
            }
            if(!samePage) restorePageOnCommit=true
            if(openingDetail || (result.loading && result.candidates.isEmpty())) resetScrollOnResult=true
            if(returningToSongs) restoreCatalogueOnCommit=true
            tools.visibility = if (key==PlainLyricsSearchSource.NETEASE || key==PlainLyricsSearchSource.SPICY) VISIBLE else GONE
            idSearch.visibility=if(key==PlainLyricsSearchSource.SPICY) VISIBLE else GONE
            wordSearch.visibility=if(key==PlainLyricsSearchSource.NETEASE) VISIBLE else GONE
            wordSearch.text=result.wordQuery?.title
                ?: context.getString(R.string.search_try_word_search)
            wordSearch.maxLines=1
            wordSearch.ellipsize=android.text.TextUtils.TruncateAt.END
            wordSearch.contentDescription=if(result.wordQuery!=null) context.getString(R.string.search_edit_query)
                else context.getString(R.string.search_try_word_search)
            recordingHeading.visibility=if(result.wordDetail!=null) VISIBLE else GONE
            recordingHeading.text=result.wordDetail?.recording?.song?.let { song ->
                listOf(song.title,listOf(song.artist,song.album).filter(String::isNotBlank).joinToString(" · "))
                    .filter(String::isNotBlank).joinToString("\n")
            }
            val showFooter=result.wordQuery!=null &&
                (result.wordDetail==null || result.candidates.isEmpty() || result.wordDetail.error!=null)
            byTitle.visibility = if (result.spotifyId!=null || result.wordQuery!=null) VISIBLE else GONE
            byTitle.setText(if(result.wordDetail!=null) R.string.search_back_recordings else if(key==PlainLyricsSearchSource.NETEASE) R.string.search_regular_search else R.string.search_by_title)
            idSearch.text = result.spotifyId?.let { context.getString(R.string.search_spotify_id_active, it) }
                ?: context.getString(R.string.search_try_spotify_id)
            idSearch.contentDescription = context.getString(R.string.search_try_spotify_id)
            val keepCatalogue=samePage && visual==SourceVisual.RESULTS &&
                rows.itemCount>0 && result.candidates.isNotEmpty() &&
                result.wordQuery!=null && result.wordQuery==previous?.wordQuery &&
                result.wordDetail==null && previous.wordDetail==null
            val nextVisual = when {
                result.loading -> SourceVisual.LOADING
                result.candidates.isNotEmpty() -> SourceVisual.RESULTS
                result.error != null -> SourceVisual.ERROR
                !result.searched -> SourceVisual.IDLE
                else -> SourceVisual.EMPTY
            }
            val animate = sameSource && frame.isShown && !blocksInteraction &&
                ValueAnimator.areAnimatorsEnabled()
            val firstResults = animate && previous?.candidates?.isEmpty() == true &&
                result.candidates.isNotEmpty()
            val pageGeneration=generation
            list.animate().cancel()
            list.translationY=0f
            rows.canOpen=keepCatalogue

            val preparing=result.loading || (result.candidates.isNotEmpty() && !rows.isCurrentList(key,result.candidates))
            if(keepCatalogue) {
                // Keep the mounted songs visible while the footer covers fetching, sorting, and diffing.
                if(preparing && !appendLoading) {
                    appendLoading=true
                    footer.show(true)
                }
            } else {
                appendLoading=false
                if(preparing) {
                    list.visibility=INVISIBLE
                    showStatus(SourceVisual.LOADING,result,animate)
                }
            }
            if(result.loading) {
                if(result.candidates.isEmpty()) session.pagePositions.remove(key)
                if(blocksInteraction) finish()
                return
            }
            rows.submit(key,result.candidates) {
                // A recycled page or a superseded search must not reveal an older submission.
                if(disposed || source!=key || generation!=pageGeneration || shownResult!==result || results[key]!==result) return@submit
                // Update the footer with the new rows. Removing it before the diff commits would
                // temporarily shorten the old list and force a scroll correction at its bottom.
                appendLoading=false
                footer.show(showFooter)
                if(restoreCatalogueOnCommit || restorePageOnCommit) {
                    // Reappearing rows must not steal focus and scroll a partially visible song into view.
                    list.descendantFocusability=FOCUS_BLOCK_DESCENDANTS
                    list.doOnPreDraw { list.descendantFocusability=FOCUS_AFTER_DESCENDANTS }
                }
                rows.canOpen=result.candidates.isNotEmpty()
                if(previous?.wordDetail?.options!=result.wordDetail?.options && rows.itemCount>0)
                    rows.notifyItemRangeChanged(0,rows.itemCount)
                list.alpha=if(firstResults) 0f else 1f
                if(resetScrollOnResult && result.candidates.isNotEmpty()) {
                    list.scrollToPosition(0)
                    resetScrollOnResult=false
                    restorePageOnCommit=false
                } else if(restorePageOnCommit) {
                    session.pagePositions[key]?.let { list.layoutManager?.onRestoreInstanceState(it) }
                        ?: list.scrollToPosition(0)
                }
                restorePageOnCommit=false
                if(restoreCatalogueOnCommit) {
                    cataloguePosition?.let { list.layoutManager?.onRestoreInstanceState(it) }
                    restoreCatalogueOnCommit=false
                }
                list.visibility=VISIBLE
                if(firstResults) revealFirstRows(result)
                showStatus(if(result.wordQuery!=null) SourceVisual.RESULTS else nextVisual,result,animate)
                if(blocksInteraction) finish()
            }
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
            if (next == SourceVisual.IDLE) {
                status.animate().cancel()
                loading.animate().cancel()
                message.animate().cancel()
                status.visibility = GONE
                loading.visibility = GONE
                message.visibility = GONE
                status.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                return
            }
            if (next != SourceVisual.RESULTS && next != SourceVisual.LOADING) {
                messageTitle.text = when {
                    next == SourceVisual.ERROR -> errorText(result.error!!)
                    result.wordDetail != null -> context.getString(R.string.search_no_lyrics)
                    result.spotifyId != null -> context.getString(R.string.search_spotify_id_empty)
                    else -> context.getString(R.string.search_empty)
                }
                messageDetail.text = if (result.wordQuery!=null && next!=SourceVisual.ERROR) "" else context.getString(R.string.search_retry)
                messageDetail.visibility = VISIBLE
                message.isClickable = result.wordQuery==null || next==SourceVisual.ERROR
                message.isFocusable = true
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
            if (animate && previous != SourceVisual.RESULTS && previous != SourceVisual.IDLE && previousView !== nextView) {
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
            list.descendantFocusability=FOCUS_AFTER_DESCENDANTS
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
            list.visibility=if(visual==SourceVisual.LOADING) INVISIBLE else VISIBLE
            status.alpha = 1f
            status.translationY = 0f
            loading.alpha = 1f
            loading.translationY = 0f
            message.alpha = 1f
            message.translationY = 0f
            // The preview owns the loading indicator during the handoff.
            status.visibility = if (visual == SourceVisual.RESULTS || visual == SourceVisual.IDLE ||
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

    private class FooterView(val body:LinearLayout):RecyclerView.ViewHolder(body)
    private inner class FooterRows(private val bindBody:(LinearLayout)->Unit):RecyclerView.Adapter<FooterView>() {
        private var visible=false
        init { setHasStableIds(true) }
        override fun getItemId(position:Int)=0L
        override fun getItemCount()=if(visible) 1 else 0
        fun show(next:Boolean) {
            val before=visible
            visible=next
            when {
                !before && next -> notifyItemInserted(0)
                before && !next -> notifyItemRemoved(0)
                next -> notifyItemChanged(0)
            }
        }
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=FooterView(LinearLayout(context).apply {
            orientation=VERTICAL
            layoutParams=RecyclerView.LayoutParams(-1,-2)
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        override fun onBindViewHolder(holder:FooterView,position:Int)=bindBody(holder.body)
    }

    private inner class Row(val box: LinearLayout) : RecyclerView.ViewHolder(box) {
        val titleLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = TextView(context).apply {
            textSize = 19f
            setTextColor(palette.textStrong)
            setTypeface(typeface, Typeface.BOLD)
        }
        val idBadge = TextView(context).apply {
            text = context.getString(R.string.search_id_badge)
            textSize = 12f
            setTextColor(palette.accent)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(palette.accent, 20))
            }
            val check = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_air_check)?.mutate()
            check?.setTint(palette.accent)
            check?.setBounds(0, 0, dp(14), dp(14))
            setCompoundDrawablesRelative(check, null, null, null)
            compoundDrawablePadding = dp(3)
            contentDescription = context.getString(R.string.search_id_match)
            visibility = GONE
        }
        val duration = TextView(context).apply { textSize = 12f; setTextColor(palette.textMuted) }
        val detail = TextView(context).apply { textSize = 13f; setTextColor(palette.textMuted) }
        init {
            box.orientation = VERTICAL
            box.minimumHeight = dp(80)
            box.setPadding(dp(4), dp(16), dp(4), dp(16))
            titleLine.addView(heading, LayoutParams(0, -2, 1f))
            titleLine.addView(idBadge, LayoutParams(-2, -2).apply { marginStart = dp(8) })
            titleLine.addView(duration, LayoutParams(-2, -2).apply { marginStart = dp(12) })
            box.addView(titleLine)
            box.addView(detail, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val selectable = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            box.setBackgroundResource(selectable.resourceId)
            box.isFocusable = true
        }
    }

    private inner class Rows : RecyclerView.Adapter<Row>() {
        private val differ=AsyncListDiffer(this,object:DiffUtil.ItemCallback<LyricsCandidate>() {
            override fun areItemsTheSame(oldItem:LyricsCandidate,newItem:LyricsCandidate)=
                oldItem.source==newItem.source && oldItem.id==newItem.id
            override fun areContentsTheSame(oldItem:LyricsCandidate,newItem:LyricsCandidate)=oldItem==newItem
        })
        private var committedSource:PlainLyricsSearchSource?=null
        private var committedList:List<LyricsCandidate>?=null
        var canOpen = false
        init { setHasStableIds(true) }
        override fun getItemCount() = differ.currentList.size
        override fun getItemId(position: Int): Long {
            val candidate = differ.currentList[position]
            var hash = -3750763034362895579L
            candidate.id.forEach { hash = (hash xor it.code.toLong()) * 1099511628211L }
            return hash xor (candidate.source.ordinal.toLong() shl 56)
        }
        fun isCurrentList(key:PlainLyricsSearchSource,next:List<LyricsCandidate>)=
            committedSource==key && committedList===next

        fun submit(key:PlainLyricsSearchSource,next:List<LyricsCandidate>,onCommitted:()->Unit) {
            // Null keeps an empty adapter cheap and invalidates any obsolete in-flight diff.
            differ.submitList(next.takeIf { it.isNotEmpty() }) {
                committedSource=key
                committedList=next
                onCommitted()
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(
            LinearLayout(context).apply { layoutParams = RecyclerView.LayoutParams(-1, -2) }
        )
        override fun onBindViewHolder(holder: Row, position: Int) {
            val candidate = differ.currentList[position]
            holder.duration.text = candidate.song.durationMs.takeIf { it > 0 }?.let {
                String.format(Locale.ROOT, "%d:%02d", it / 60000, it / 1000 % 60)
            }.orEmpty()
            holder.heading.text = candidate.song.title.ifBlank { context.getString(R.string.search_spotify_track) }
            holder.idBadge.visibility = if (candidate.spotifyIdMatch) VISIBLE else GONE
            val parent=results[candidate.source]?.wordDetail
            val wordMode=candidate.recording!=null || candidate.wordOption!=null || candidate.wordVersion!=null
            val provider=context.localizedPlainLyricsProviderName(candidate.providerKey)
            val option=candidate.wordOption
            val optionState=option?.let { results[candidate.source]?.wordDetail?.options?.get(it.key) }
            val version=candidate.wordVersion
            if(version!=null) holder.heading.text=context.getString(R.string.search_version,provider,
                context.getString(if(version.isWordTimed) R.string.search_version_word else R.string.search_version_line))
            else if(option!=null) holder.heading.text=if(option.song.title!=parent?.recording?.song?.title)
                option.song.title else context.getString(R.string.search_version,provider,
                    context.getString(R.string.search_lyric_number,position+1))
            val metadata=when {
                candidate.recording!=null -> listOf(provider,candidate.song.artist,candidate.song.album)
                option!=null -> listOf(option.creator,option.rating.takeIf(String::isNotBlank)?.let {
                    context.getString(R.string.search_lyric_rating,it) }.orEmpty(),
                    option.song.artist.takeIf { it!=parent?.recording?.song?.artist }.orEmpty())
                version!=null && parent!=null -> emptyList()
                else -> listOf(candidate.song.artist,candidate.song.album,
                    candidate.id.takeIf { candidate.spotifyIdMatch }.orEmpty())
            }.filter(String::isNotBlank).joinToString(" · ")
            val status=when {
                optionState?.loading==true -> context.getString(R.string.search_lyrics_loading)
                optionState?.error!=null -> context.getString(R.string.search_source_retry,provider,context.getString(R.string.search_retry))
                optionState?.plainTextOnly==true -> context.getString(R.string.search_only_plain)
                else -> null
            }
            holder.detail.text=listOfNotNull(metadata.takeIf(String::isNotBlank),status).joinToString("\n")
            holder.detail.visibility=if(holder.detail.text.isEmpty()) GONE else VISIBLE
            holder.detail.maxLines=if(wordMode && status==null) 2 else Int.MAX_VALUE
            holder.detail.ellipsize=if(wordMode) android.text.TextUtils.TruncateAt.END else null
            holder.box.setOnClickListener {
                val position=holder.bindingAdapterPosition
                if(canOpen && position!=RecyclerView.NO_POSITION)
                    differ.currentList.getOrNull(position)?.let(session::preview)
            }
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
        disposed=true
        mountedPages.forEach(Page::finish)
        tabs.clearOnTabSelectedListeners()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
