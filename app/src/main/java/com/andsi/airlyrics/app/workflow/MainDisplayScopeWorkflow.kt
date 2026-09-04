package com.andsi.airlyrics.app.workflow

import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.displayscope.DisplayScopeCapability
import com.andsi.airlyrics.settings.store.DisplayScopeStore
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.theme.applyAirThemeTint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.util.Locale

/** Owns app discovery and selection for the optional display-scope allowlist. */
internal class MainDisplayScopeWorkflow(
    private val graph: MainGraph
) {
    private data class AppChoice(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val searchText: String
    )

    private data class AppChoiceRowViews(
        val icon: ImageView,
        val label: TextView,
        val packageName: TextView,
        val toggle: SwitchCompat
    )

    private inner class AppPickerSession(
        private val dialog: Dialog,
        private val adapter: AppChoiceAdapter,
        private val selectedPackages: MutableSet<String>,
        private val emptyView: TextView
    ) {
        fun showChoices(choices: List<AppChoice>, pruneMissingSelections: Boolean) {
            if (!dialog.isShowing) return
            if (pruneMissingSelections) {
                selectedPackages.retainAll(choices.mapTo(hashSetOf(), AppChoice::packageName))
            }
            emptyView.setText(R.string.ui_no_apps_found)
            adapter.submitChoices(choices)
        }
    }

    private inner class AppPickerScrollShortcutController(
        private val list: ListView,
        private val jumpToTop: View,
        private val jumpToBottom: View
    ) : AbsListView.OnScrollListener {
        private val shortcutOffset = graph.uiHost.dp(APP_SCROLL_SHORTCUT_OFFSET_DP).toFloat()
        private val hideShortcuts = Runnable { hideAllShortcuts() }
        private var visibleShortcut: View? = null
        private var hasPreviousPosition = false
        private var previousFirstVisibleItem = 0
        private var previousFirstChildTop = 0

        init {
            list.setOnScrollListener(this)
            jumpToTop.setOnClickListener {
                list.setSelection(0)
                hideAllShortcuts()
            }
            jumpToBottom.setOnClickListener {
                if (list.count > 0) list.setSelection(list.count - 1)
                hideAllShortcuts()
            }
        }

        override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
            if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                scheduleHide()
            }
        }

        override fun onScroll(
            view: AbsListView,
            firstVisibleItem: Int,
            visibleItemCount: Int,
            totalItemCount: Int
        ) {
            val firstChildTop = view.getChildAt(0)?.top ?: 0
            if (!hasPreviousPosition) {
                hasPreviousPosition = true
                rememberPosition(firstVisibleItem, firstChildTop)
                return
            }

            val movingDown = firstVisibleItem > previousFirstVisibleItem ||
                firstVisibleItem == previousFirstVisibleItem && firstChildTop < previousFirstChildTop
            val movingUp = firstVisibleItem < previousFirstVisibleItem ||
                firstVisibleItem == previousFirstVisibleItem && firstChildTop > previousFirstChildTop
            rememberPosition(firstVisibleItem, firstChildTop)

            if (totalItemCount <= visibleItemCount) {
                hideAllShortcuts()
                return
            }

            when {
                movingDown && view.canScrollVertically(1) -> showShortcut(jumpToBottom)
                movingUp && view.canScrollVertically(-1) -> showShortcut(jumpToTop)
                movingDown || movingUp -> hideAllShortcuts()
            }
        }

        private fun rememberPosition(firstVisibleItem: Int, firstChildTop: Int) {
            previousFirstVisibleItem = firstVisibleItem
            previousFirstChildTop = firstChildTop
        }

        private fun showShortcut(shortcut: View) {
            if (visibleShortcut === shortcut) {
                scheduleHide()
                return
            }

            val previousShortcut = visibleShortcut
            visibleShortcut = shortcut
            previousShortcut?.let(::animateOut)
            list.removeCallbacks(hideShortcuts)

            shortcut.animate().cancel()
            if (shortcut.visibility != View.VISIBLE) {
                shortcut.visibility = View.VISIBLE
                shortcut.alpha = 0f
                shortcut.translationY = hiddenTranslation(shortcut)
            }
            shortcut.animate()
                .alpha(APP_SCROLL_SHORTCUT_ALPHA)
                .translationY(0f)
                .setDuration(APP_SCROLL_SHORTCUT_ENTER_MS)
                .setInterpolator(DecelerateInterpolator())
                .withLayer()
                .start()
            scheduleHide()
        }

        private fun scheduleHide() {
            if (visibleShortcut == null) return
            list.removeCallbacks(hideShortcuts)
            list.postDelayed(hideShortcuts, APP_SCROLL_SHORTCUT_HOLD_MS)
        }

        private fun hideAllShortcuts() {
            list.removeCallbacks(hideShortcuts)
            visibleShortcut = null
            animateOut(jumpToTop)
            animateOut(jumpToBottom)
        }

        private fun animateOut(shortcut: View) {
            if (shortcut.visibility != View.VISIBLE) return
            shortcut.animate().cancel()
            shortcut.animate()
                .alpha(0f)
                .translationY(hiddenTranslation(shortcut))
                .setDuration(APP_SCROLL_SHORTCUT_EXIT_MS)
                .setInterpolator(AccelerateInterpolator())
                .withLayer()
                .withEndAction {
                    if (visibleShortcut !== shortcut) shortcut.visibility = View.INVISIBLE
                }
                .start()
        }

        private fun hiddenTranslation(shortcut: View): Float {
            return if (shortcut === jumpToTop) -shortcutOffset else shortcutOffset
        }
    }

    @Volatile
    private var cachedChoices: List<AppChoice>? = null

    @Volatile
    private var loadingChoices = false

    fun showAppPicker() {
        if (!DisplayScopeCapability.isSupported()) return

        cachedChoices?.let { choices ->
            showAppPickerDialog(choices)
            return
        }
        if (loadingChoices) return

        loadingChoices = true
        val generation = graph.currentUiGeneration()
        val session = showAppPickerDialog(emptyList(), loading = true)
        graph.runOnAppIo {
            val loadedChoices = runCatching(::loadChoices)
            val choices = loadedChoices.getOrDefault(emptyList())
            if (loadedChoices.isSuccess) cachedChoices = choices
            loadingChoices = false
            graph.runOnStartedUi(generation) {
                session.showChoices(
                    choices = choices,
                    pruneMissingSelections = loadedChoices.isSuccess
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadChoices(): List<AppChoice> {
        val packageManager = graph.activity.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        )
        return intents
            .asSequence()
            .flatMap { intent -> packageManager.queryIntentActivities(intent, 0).asSequence() }
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                packageName to resolveInfo
            }
            .distinctBy { (packageName) -> packageName }
            .map { (packageName, resolveInfo) ->
                val label = resolveInfo.loadLabel(packageManager).toString().trim()
                    .takeIf(String::isNotBlank)
                    ?: packageName
                AppChoice(
                    packageName = packageName,
                    label = label,
                    icon = runCatching { resolveInfo.loadIcon(packageManager) }.getOrNull(),
                    searchText = "$label\n$packageName".lowercase(Locale.ROOT)
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AppChoice::label))
            .toList()
    }

    private fun showAppPickerDialog(
        choices: List<AppChoice>,
        loading: Boolean = false
    ): AppPickerSession = with(graph.uiHost) {
        val selected = DisplayScopeStore.selectedPackages(this).toMutableSet().apply {
            if (!loading) retainAll(choices.mapTo(hashSetOf(), AppChoice::packageName))
        }
        val adapter = AppChoiceAdapter(choices, selected)
        lateinit var empty: TextView
        lateinit var selectAll: TextView
        val dialog = showAirDialog(
            title = getString(R.string.ui_choose_apps),
            positiveText = getString(R.string.ui_save),
            negativeText = getString(R.string.ui_cancel),
            headerAction = {
                selectAll = appPickerHeaderButton(
                    text = getString(R.string.ui_select_all),
                    enabled = choices.isNotEmpty(),
                    onClick = adapter::toggleAll
                )
                adapter.onSelectionStateChanged = {
                    updateSelectAllButton(selectAll, adapter)
                }
                updateSelectAllButton(selectAll, adapter)
                addView(selectAll)
            },
            body = {
                val search = EditText(this@with).apply {
                    hint = getString(R.string.ui_search_apps)
                    inputType = InputType.TYPE_CLASS_TEXT
                    isSingleLine = true
                    setTextColor(colorTextStrong)
                    setHintTextColor(colorTextMuted)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Sm))
                    }
                }
                addView(search)

                empty = TextView(this@with).apply {
                    text = getString(if (loading) R.string.ui_loading else R.string.ui_no_apps_found)
                    textSize = AirUiTokens.TextSize.Body
                    setTextColor(colorTextMuted)
                    gravity = Gravity.CENTER
                }
                val list = ListView(this@with).apply {
                    this.adapter = adapter
                    divider = null
                    dividerHeight = 0
                    emptyView = empty
                    isVerticalScrollBarEnabled = true
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                }
                val jumpToTop = appPickerScrollShortcut(
                    rotationDegrees = -90f,
                    contentDescription = getString(R.string.ui_jump_to_top)
                )
                val jumpToBottom = appPickerScrollShortcut(
                    rotationDegrees = 90f,
                    contentDescription = getString(R.string.ui_jump_to_bottom)
                )
                addView(FrameLayout(this@with).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        minOf(
                            dp(APP_LIST_MAX_HEIGHT_DP),
                            (resources.displayMetrics.heightPixels * APP_LIST_SCREEN_HEIGHT_RATIO).toInt()
                        )
                    ).apply {
                        setMargins(0, dp(AirUiTokens.Space.Sm), 0, 0)
                    }
                    addView(
                        list,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    addView(
                        empty,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    addView(
                        jumpToTop,
                        FrameLayout.LayoutParams(
                            dp(APP_SCROLL_SHORTCUT_SIZE_DP),
                            dp(APP_SCROLL_SHORTCUT_SIZE_DP),
                            Gravity.TOP or Gravity.START
                        ).apply {
                            topMargin = dp(AirUiTokens.Space.Xl)
                            marginStart = dp(AirUiTokens.Space.Xl)
                        }
                    )
                    addView(
                        jumpToBottom,
                        FrameLayout.LayoutParams(
                            dp(APP_SCROLL_SHORTCUT_SIZE_DP),
                            dp(APP_SCROLL_SHORTCUT_SIZE_DP),
                            Gravity.BOTTOM or Gravity.START
                        ).apply {
                            bottomMargin = dp(AirUiTokens.Space.Xl)
                            marginStart = dp(AirUiTokens.Space.Xl)
                        }
                    )
                })
                AppPickerScrollShortcutController(list, jumpToTop, jumpToBottom)

                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        adapter.filter(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            },
            useOuterScroll = false,
            onPositive = {
                DisplayScopeStore.setSelectedPackages(this, selected)
                graph.onDisplayScopeSelectionChanged()
            }
        )
        AppPickerSession(dialog, adapter, selected, empty)
    }

    private fun updateSelectAllButton(
        button: TextView,
        adapter: AppChoiceAdapter
    ) {
        val hasChoices = adapter.hasChoices()
        button.setText(
            if (adapter.areAllChoicesSelected()) R.string.ui_deselect_all else R.string.ui_select_all
        )
        button.isEnabled = hasChoices
        button.alpha = if (hasChoices) 1f else APP_PICKER_DISABLED_ALPHA
    }

    private fun appPickerHeaderButton(
        text: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): TextView = with(graph.uiHost) {
        TextView(this).apply {
            this.text = text
            textSize = AirUiTokens.TextSize.BodySmall
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(colorTextStrong)
            setPadding(
                dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Sm),
                dp(AirUiTokens.Space.Xl),
                dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Sm),
                dp(AirUiTokens.Space.Xl)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(AirUiTokens.Space.Xl)
            }
            background = appPickerButtonBackground()
            isEnabled = enabled
            alpha = if (enabled) 1f else APP_PICKER_DISABLED_ALPHA
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            setOnClickListener { onClick() }
        }
    }

    private fun appPickerScrollShortcut(
        rotationDegrees: Float,
        contentDescription: String
    ): ImageView = with(graph.uiHost) {
        airIconView(
            iconRes = R.drawable.ic_air_arrow_back,
            tint = colorTextStrong,
            contentDescription = contentDescription
        ).apply {
            drawable?.isAutoMirrored = false
            rotation = rotationDegrees
            scaleType = ImageView.ScaleType.FIT_CENTER
            val iconPadding = dp(APP_SCROLL_SHORTCUT_ICON_PADDING_DP)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            background = appPickerButtonBackground()
            elevation = dp(APP_SCROLL_SHORTCUT_ELEVATION_DP).toFloat()
            visibility = View.INVISIBLE
            alpha = 0f
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        }
    }

    private fun appPickerButtonBackground(): Drawable = with(graph.uiHost) {
        GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
    }

    private inner class AppChoiceAdapter(
        private var choices: List<AppChoice>,
        private val selectedPackages: MutableSet<String>
    ) : BaseAdapter() {
        var onSelectionStateChanged: (() -> Unit)? = null

        private var normalizedQuery = ""
        private var visibleChoices = choices

        override fun getCount(): Int = visibleChoices.size

        override fun getItem(position: Int): AppChoice = visibleChoices[position]

        override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: createAppChoiceRow()
            bindAppChoiceRow(
                row = row,
                choice = getItem(position),
                selectedPackages = selectedPackages,
                onSelectionStateChanged = ::notifySelectionStateChanged
            )
            return row
        }

        fun filter(query: String) {
            normalizedQuery = query.trim().lowercase(Locale.ROOT)
            applyFilter()
        }

        fun submitChoices(choices: List<AppChoice>) {
            this.choices = choices
            applyFilter()
            notifySelectionStateChanged()
        }

        fun hasChoices(): Boolean = choices.isNotEmpty()

        fun areAllChoicesSelected(): Boolean {
            return choices.isNotEmpty() && choices.all { it.packageName in selectedPackages }
        }

        fun toggleAll() {
            if (areAllChoicesSelected()) {
                selectedPackages.clear()
            } else {
                selectedPackages.addAll(choices.map(AppChoice::packageName))
            }
            notifyDataSetChanged()
            notifySelectionStateChanged()
        }

        private fun notifySelectionStateChanged() = onSelectionStateChanged?.invoke()

        private fun applyFilter() {
            visibleChoices = if (normalizedQuery.isEmpty()) {
                choices
            } else {
                choices.filter { it.searchText.contains(normalizedQuery) }
            }
            notifyDataSetChanged()
        }
    }

    private fun createAppChoiceRow(): LinearLayout = with(graph.uiHost) {
        val icon = ImageView(this)
        val label = TextView(this)
        val packageName = TextView(this)
        val toggle = SwitchCompat(this).apply {
            applyAirThemeTint(this@with)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(AirUiTokens.Space.Lg), 0, dp(AirUiTokens.Space.Lg))
            enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)

            addView(icon.apply {
                contentDescription = null
                layoutParams = LinearLayout.LayoutParams(
                    dp(AirUiTokens.Layout.IconSize + AirUiTokens.Space.Xl),
                    dp(AirUiTokens.Layout.IconSize + AirUiTokens.Space.Xl)
                ).apply {
                    setMargins(0, 0, dp(AirUiTokens.Space.Xl), 0)
                }
            })
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(label.apply {
                    textSize = AirUiTokens.TextSize.Button
                    setTextColor(colorTextStrong)
                    maxLines = 1
                })
                addView(packageName.apply {
                    textSize = AirUiTokens.TextSize.Caption
                    setTextColor(colorTextMuted)
                    maxLines = 1
                })
            })
            addView(toggle)
            tag = AppChoiceRowViews(icon, label, packageName, toggle)
        }
    }

    private fun bindAppChoiceRow(
        row: LinearLayout,
        choice: AppChoice,
        selectedPackages: MutableSet<String>,
        onSelectionStateChanged: () -> Unit
    ) {
        val views = row.tag as AppChoiceRowViews
        views.icon.setImageDrawable(choice.icon)
        views.label.text = choice.label
        views.packageName.text = choice.packageName
        views.toggle.setOnCheckedChangeListener(null)
        views.toggle.isChecked = choice.packageName in selectedPackages
        views.toggle.contentDescription = choice.label
        views.toggle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selectedPackages += choice.packageName
            } else {
                selectedPackages -= choice.packageName
            }
            onSelectionStateChanged()
        }
        row.setOnClickListener { views.toggle.toggle() }
    }

    private companion object {
        const val APP_LIST_MAX_HEIGHT_DP = 320
        const val APP_LIST_SCREEN_HEIGHT_RATIO = 0.42f
        const val APP_PICKER_DISABLED_ALPHA = 0.45f
        const val APP_SCROLL_SHORTCUT_ALPHA = 0.62f
        const val APP_SCROLL_SHORTCUT_OFFSET_DP = 18
        const val APP_SCROLL_SHORTCUT_SIZE_DP = 42
        const val APP_SCROLL_SHORTCUT_ICON_PADDING_DP = 7
        const val APP_SCROLL_SHORTCUT_ELEVATION_DP = 4
        const val APP_SCROLL_SHORTCUT_ENTER_MS = 260L
        const val APP_SCROLL_SHORTCUT_EXIT_MS = 150L
        const val APP_SCROLL_SHORTCUT_HOLD_MS = 1_400L
    }
}
