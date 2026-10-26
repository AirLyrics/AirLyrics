package com.andsi.airlyrics.app.search

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/** A draft selection; dismissal never changes the active search preferences. */
class LyricsSourcesSheet : BottomSheetDialogFragment() {
    private val selected = linkedSetOf<String>()
    private lateinit var sourceOrder: List<PlainLyricsSearchSource>
    private val palette by lazy {
        AirLyricsTheme.palette(ThemeSettingsStore.isDark(requireContext()), ThemeSettingsStore.getAccent(requireContext()))
    }
    private lateinit var content: LinearLayout
    private lateinit var done: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll((savedInstanceState ?: requireArguments()).getStringArrayList(KEYS).orEmpty())
        val ordered = requireArguments().getStringArrayList(ORDER).orEmpty()
            .mapNotNull(PlainLyricsSearchSource::fromKeyOrNull)
            .filter { it in PlainLyricsSearchSource.onlineSources }
        sourceOrder = (ordered + PlainLyricsSearchSource.onlineSources).distinct()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val sheet = BottomSheetDialog(context, R.style.ThemeOverlay_AirLyrics_LyricsSources)
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(palette.background)
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        content.addView(View(context).apply {
            background = rounded(palette.stroke, 2)
        }, LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })

        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(getString(R.string.search_sources), 24f).apply {
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_air_close)
            imageTintList = ColorStateList.valueOf(palette.textMuted)
            background = null
            contentDescription = getString(R.string.ui_cancel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sourceOrder.forEach { source ->
            rows.addView(sourceRow(source), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        content.addView(ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            addView(rows)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        done = MaterialButton(context).apply {
            isAllCaps = false
            letterSpacing = 0f
            cornerRadius = dp(18)
            minHeight = dp(52)
            elevation = 0f
            stateListAnimator = null
            backgroundTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(palette.accent, palette.stroke)
            )
            setTextColor(palette.onAccent)
            setOnClickListener {
                if (selected.isEmpty()) return@setOnClickListener
                parentFragmentManager.setFragmentResult(RESULT, Bundle().apply {
                    putStringArrayList(KEYS, ArrayList(sourceOrder.map { it.key }.filter { it in selected }))
                })
                dismiss()
            }
        }
        content.addView(done, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        updateFooter()
        sheet.setContentView(content)
        return sheet
    }

    override fun onStart() {
        super.onStart()
        val sheet = requireDialog() as BottomSheetDialog
        val maxHeight = (resources.displayMetrics.heightPixels * 0.88f).toInt()
        content.layoutParams = content.layoutParams.apply { height = minOf(dp(500), maxHeight) }
        sheet.behavior.apply {
            maxWidth = dp(640)
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        // The sheet owns the rounded fill. Material's tinted rectangular backing and
        // elevation must not remain visible outside those corners.
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            backgroundTintList = null
            background = null
            stateListAnimator = null
            elevation = 0f
            translationZ = 0f
            outlineProvider = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(KEYS, ArrayList(sourceOrder.map { it.key }.filter { it in selected }))
        super.onSaveInstanceState(outState)
    }

    private fun sourceRow(source: PlainLyricsSearchSource): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(70)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true
            isFocusable = true
        }
        val title = label(context.localizedPlainLyricsProviderName(source.key), 18f)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val mark = ImageView(context).apply {
            setImageResource(R.drawable.ic_air_check)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(16) })
        val shape = rounded(palette.surfaceLight, 18)
        row.background = shape
        var animator: ValueAnimator? = null
        var fill = palette.surfaceLight
        fun refresh(animate: Boolean) {
            val checked = source.key in selected
            row.isSelected = checked
            title.setTypeface(null, if (checked) Typeface.BOLD else Typeface.NORMAL)
            shape.setStroke(dp(1), if (checked) palette.accent else palette.stroke)
            mark.background = rounded(if (checked) palette.accent else palette.stroke, 14)
            mark.imageTintList = ColorStateList.valueOf(if (checked) palette.onAccent else Color.TRANSPARENT)
            val target = if (checked) palette.surface else palette.surfaceLight
            animator?.cancel()
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                animator = ValueAnimator.ofArgb(fill, target).apply {
                    duration = 180
                    addUpdateListener { fill = it.animatedValue as Int; shape.setColor(fill) }
                    start()
                }
                mark.scaleX = 0.8f
                mark.scaleY = 0.8f
                mark.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            } else {
                fill = target
                shape.setColor(fill)
            }
        }
        row.contentDescription = title.text
        ViewCompat.setAccessibilityDelegate(row, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.CheckBox"
                info.isCheckable = true
                info.checked = if (source.key in selected) AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                    else AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
            }
        })
        title.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.setOnClickListener {
            if (!selected.remove(source.key)) selected.add(source.key)
            refresh(true)
            updateFooter()
        }
        row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { animator?.cancel(); mark.animate().cancel() }
        })
        refresh(false)
        return row
    }

    private fun updateFooter() {
        done.text = getString(R.string.search_sources_use, selected.size)
        done.isEnabled = selected.isNotEmpty()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
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
