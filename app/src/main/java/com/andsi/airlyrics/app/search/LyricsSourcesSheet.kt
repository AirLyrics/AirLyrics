package com.andsi.airlyrics.app.search

import android.app.Dialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.button.MaterialButton

/**
 * The fragment identity is retained for saved-state compatibility. Presentation is now an
 * anchored container transform, not a bottom sheet. Only Apply publishes the draft.
 */
class LyricsSourcesSheet : DialogFragment() {
    private val selected = linkedSetOf<String>()
    private val committed = linkedSetOf<String>()
    private lateinit var sourceOrder: List<PlainLyricsSearchSource>
    private val palette by lazy {
        AirLyricsTheme.palette(ThemeSettingsStore.isDark(requireContext()), ThemeSettingsStore.getAccent(requireContext()))
    }
    private lateinit var morph: LyricsSourcesMorphView
    private lateinit var done: MaterialButton
    private lateinit var count: TextView
    private lateinit var hint: TextView
    private lateinit var capsule: TextView
    private val rows = mutableListOf<LyricsSourceSelectionRow>()
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll((savedInstanceState ?: requireArguments()).getStringArrayList(KEYS).orEmpty())
        committed.addAll(savedInstanceState?.getStringArrayList(COMMITTED)
            ?: requireArguments().getStringArrayList(KEYS).orEmpty())
        closing = savedInstanceState?.getBoolean(CLOSING) == true
        val ordered = requireArguments().getStringArrayList(ORDER).orEmpty()
            .mapNotNull(PlainLyricsSearchSource::fromKeyOrNull)
            .filter { it in PlainLyricsSearchSource.onlineSources }
        sourceOrder = (ordered + PlainLyricsSearchSource.onlineSources).distinct()
        selected.retainAll(sourceOrder.map { it.key }.toSet())
    }

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val windowDialog = object : ComponentDialog(context, android.R.style.Theme_Translucent_NoTitleBar) {
            override fun cancel() = requestClose()
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            isClickable = true
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = label(getString(R.string.search_sources), 22f).apply {
            setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val close = AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_air_close)
            imageTintList = ColorStateList.valueOf(palette.textMuted)
            contentDescription = getString(R.string.ui_cancel)
            background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                .let { attributes -> attributes.getDrawable(0).also { attributes.recycle() } }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { requestClose() }
        }
        heading.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.clear()
        sourceOrder.forEach { source ->
            val row = LyricsSourceSelectionRow(context, palette,
                context.localizedPlainLyricsProviderName(source.key), source.key in selected) {
                if (closing) return@LyricsSourceSelectionRow
                if (!selected.remove(source.key)) selected.add(source.key)
                updateFooter()
            }
            rows.add(row)
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        val scroller = ScrollView(context).apply {
            isFillViewport = false
            addView(list)
        }
        content.addView(scroller, LinearLayout.LayoutParams(-1, -2, 1f))

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        count = label("", 14f)
        actions.addView(count, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        footer.addView(actions, LinearLayout.LayoutParams(-1, -2))
        // Reserve this line so the panel and Apply never jump when the last source is unchecked.
        hint = label(getString(R.string.search_sources_choose_one), 13f).apply {
            setTextColor(palette.text)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        footer.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        done = MaterialButton(context).apply {
            setText(R.string.search_sources_use)
            isAllCaps = false
            letterSpacing = 0f
            cornerRadius = dp(24)
            minHeight = dp(48)
            stateListAnimator = null
            elevation = 0f
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(palette.accent, palette.stroke)
            )
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(palette.onAccent, palette.textMuted)
            ))
            setOnClickListener {
                if (selected.isEmpty() || closing) return@setOnClickListener
                committed.clear()
                committed.addAll(sourceOrder.map { it.key }.filter { it in selected })
                parentFragmentManager.setFragmentResult(RESULT, Bundle().apply {
                    putStringArrayList(KEYS, ArrayList(committed))
                })
                capsule.text = getString(R.string.search_sources_count, committed.size)
                requestClose()
            }
        }
        actions.addView(done, LinearLayout.LayoutParams(-2, -2))
        content.addView(footer)

        val trigger = (activity as? LyricsSearchActivity)?.sourceCapsule()
        capsule = label(getString(R.string.search_sources_count, committed.size), 14f).apply {
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            typeface = trigger?.typeface ?: typeface
            includeFontPadding = trigger?.includeFontPadding ?: true
            trigger?.let { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize) }
            letterSpacing = trigger?.letterSpacing ?: 0f
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        morph = LyricsSourcesMorphView(context, palette, content, capsule,
            anchor = { (activity as? LyricsSearchActivity)?.sourceCapsule() },
            onContentProgress = { p ->
                // The surface leads; capped 30ms row offsets overlap rather than queue.
                fun reveal(view: View, startMs: Float, durationMs: Float) {
                    val alpha = LyricsSourcesMorphView.segment(p, startMs / 450f, (startMs + durationMs) / 450f)
                    view.alpha = alpha
                    view.translationY = dp(6) * (1f - alpha)
                }
                reveal(heading, 310f, 100f)
                reveal(footer, 320f, 100f)
                rows.forEachIndexed { index, row -> reveal(row, 300f + minOf(index, 2) * 30f, 90f) }
            },
            onDim = { p -> windowDialog.window?.setDimAmount(0.6f * p) },
            onCancel = ::requestClose,
            onReopen = {
                closing = false
                selected.clear()
                selected.addAll(committed)
                rows.forEachIndexed { index, row -> row.setChecked(sourceOrder[index].key in selected, false) }
                updateFooter()
                morph.setOpen(true)
            },
            onClosed = ::finishDismiss,
            onOpened = {
                close.requestFocus()
            }
        )
        ViewCompat.setAccessibilityPaneTitle(morph, getString(R.string.search_sources))
        updateFooter()
        windowDialog.setContentView(morph)
        windowDialog.setCanceledOnTouchOutside(false)
        windowDialog.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestClose()
        })
        windowDialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            val dark = ThemeSettingsStore.isDark(context)
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setDimAmount(0f)
        }
        (activity as? LyricsSearchActivity)?.interactionUi?.register(windowDialog) {
            morph.dispose()
            windowDialog.dismiss()
        }
        return windowDialog
    }

    override fun onStart() {
        super.onStart()
        requireDialog().window?.setLayout(-1, -1)
        ViewCompat.requestApplyInsets(morph)
        if (closing) morph.setOpen(false)
    }

    private fun requestClose() {
        if (closing) return
        closing = true
        if (::morph.isInitialized) morph.setOpen(false) else finishDismiss()
    }

    override fun dismiss() = requestClose()

    private fun finishDismiss() {
        if (::morph.isInitialized) morph.dispose()
        if (parentFragmentManager.isStateSaved) super.dismissAllowingStateLoss() else super.dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        (activity as? LyricsSearchActivity)?.let { host ->
            if (dialog is Dialog) host.interactionUi.forget(dialog)
            host.sourceCapsule()?.apply {
                alpha = 1f
                requestFocus()
            }
            host.onSourcesPickerDismissed()
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        if (::morph.isInitialized) morph.dispose()
        rows.forEach { it.dispose() }
        rows.clear()
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(KEYS, ArrayList(selected))
        outState.putStringArrayList(COMMITTED, ArrayList(committed))
        outState.putBoolean(CLOSING, closing)
        super.onSaveInstanceState(outState)
    }

    private fun updateFooter() {
        count.text = getString(R.string.search_sources_selection_count, selected.size)
        hint.visibility = if (selected.isEmpty()) View.VISIBLE else View.INVISIBLE
        done.isEnabled = selected.isNotEmpty() && !closing
    }

    private fun label(value: String, size: Float) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(palette.textStrong)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal const val TAG = "lyrics-search-sources"
        internal const val RESULT = "lyrics-search-sources-result"
        internal const val KEYS = "sources"
        private const val ORDER = "source_order"
        private const val COMMITTED = "committed_sources"
        private const val CLOSING = "closing"
        internal fun create(
            sources: Set<PlainLyricsSearchSource>,
            order: List<PlainLyricsSearchSource> = (sources.toList() + PlainLyricsSearchSource.onlineSources).distinct()
        ) = LyricsSourcesSheet().apply {
            arguments = Bundle().apply {
                putStringArrayList(KEYS, ArrayList(sources.map { it.key }))
                putStringArrayList(ORDER, ArrayList(order.map { it.key }))
            }
        }
    }
}
