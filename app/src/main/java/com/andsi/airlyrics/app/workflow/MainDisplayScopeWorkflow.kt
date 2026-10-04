package com.andsi.airlyrics.app.workflow

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.app.Dialog
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
import com.andsi.airlyrics.ui.components.ExpandableTextLayout
import com.andsi.airlyrics.ui.components.expandableText
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.theme.applyAirThemeTint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

/** Owns app discovery and selection for the optional display-scope allowlist. */
internal class MainDisplayScopeWorkflow(
    private val graph: MainGraph
) {
    private fun findList(view: View?): ListView? {
        if (view is ListView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
        return null
    }

    private data class AppChoiceRowViews(
        val icon: ImageView,
        val label: TextView,
        val packageName: TextView,
        val toggle: SwitchCompat
    )

    private inner class AppPickerSession(
        private val dialog: Dialog,
        private val adapter: AppChoiceAdapter,
        private val emptyView: TextView
    ) {
        fun showChoices(
            choices: List<DisplayScopeAppChoice>,
            pruneMissingSelections: Boolean
        ) {
            if (!dialog.isShowing) return
            emptyView.setText(R.string.ui_no_apps_found)
            adapter.submitChoices(choices, pruneMissingSelections)
            val list = findList(dialog.window?.decorView)
            val draft = graph.viewModel.interactions.read("appPicker")
            list?.post {
                val key = draft?.getString("anchor")
                val index = (0 until adapter.count).firstOrNull { adapter.getItem(it).packageName == key } ?: 0
                list.setSelectionFromTop(index, draft?.getInt("offset") ?: 0)
            }
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

    private var currentDialog: Dialog? = null

    fun showAppPicker() {
        if (!DisplayScopeCapability.isSupported() || currentDialog?.isShowing == true) return
        val state = graph.viewModel.interactions
        val newSession = state.read("appPicker") == null
        if (newSession) state.write("appPicker") {
            putStringArrayList("selected", ArrayList(DisplayScopeStore.selectedPackages(graph.activity)))
        }
        val session = showAppPickerDialog()
        graph.activity.lifecycleScope.launch {
            val loaded = graph.viewModel.loadDisplayScopeChoices(graph.activity.applicationContext, newSession).await()
            if (currentDialog?.isShowing == true) session.showChoices(
                loaded.getOrDefault(emptyList()), pruneMissingSelections = loaded.isSuccess
            )
        }
    }

    private fun showAppPickerDialog(): AppPickerSession = with(graph.uiHost) {
        val draft = interactions.read("appPicker")
        val selected = (draft?.getStringArrayList("selected")?.toSet()
            ?: DisplayScopeStore.selectedPackages(this)).toMutableSet()
        val adapter = AppChoiceAdapter(emptyList(), selected)
        lateinit var empty: TextView
        lateinit var selectAll: TextView
        val dialog = showAirDialog(
            title = getString(R.string.ui_choose_apps),
            positiveText = getString(R.string.ui_save),
            negativeText = getString(R.string.ui_cancel),
            headerAction = {
                selectAll = appPickerHeaderButton(
                    text = getString(R.string.ui_select_all),
                    enabled = false,
                    onClick = adapter::toggleAll
                )
                adapter.onSelectionStateChanged = {
                    if (interactions.read("appPicker") != null) interactions.write("appPicker") {
                        putStringArrayList("selected", ArrayList(selected))
                    }
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
                    setText(draft?.getString("query").orEmpty())
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
                    text = getString(R.string.ui_loading)
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
                    isNestedScrollingEnabled = true
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
                        ),
                        1f
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

                adapter.filter(search.text.toString())
                list.post {
                    val key = draft?.getString("anchor")
                    val index = (0 until adapter.count).firstOrNull { adapter.getItem(it).packageName == key } ?: 0
                    list.setSelectionFromTop(index, draft?.getInt("offset") ?: 0)
                }
                interactionUi.snapshot(list) {
                    if (list.isAttachedToWindow && adapter.count > 0 && interactions.read("appPicker") != null) {
                        interactions.write("appPicker") {
                            putString("anchor", adapter.getItem(list.firstVisiblePosition).packageName)
                            putInt("offset", list.getChildAt(0)?.top ?: 0)
                        }
                    }
                }
                list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) { interactionUi.forgetSnapshot(list) }
                })
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s?.toString().orEmpty()
                        adapter.filter(query)
                        interactions.write("appPicker") { putString("query", query) }
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            },
            useOuterScroll = false,
            onUserDismiss = { interactions.remove("appPicker") },
            onPositive = {
                DisplayScopeStore.setSelectedPackages(this, selected)
                graph.onDisplayScopeSelectionChanged()
            }
        )
        currentDialog = dialog
        AppPickerSession(dialog, adapter, empty)
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
        choices: List<DisplayScopeAppChoice>,
        selectedPackages: MutableSet<String>
    ) : BaseAdapter() {
        var onSelectionStateChanged: (() -> Unit)? = null

        private val selection = DisplayScopeAppSelection(choices, selectedPackages)
        private val expandedTexts = mutableSetOf<String>()

        override fun getCount(): Int = selection.visibleChoices.size

        override fun getItem(position: Int): DisplayScopeAppChoice = selection.visibleChoices[position]

        override fun getItemId(position: Int): Long = getItem(position).packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: createAppChoiceRow()
            val choice = getItem(position)
            bindAppChoiceRow(
                row = row,
                choice = choice,
                selected = selection.isSelected(choice.packageName),
                onSelectedChanged = { selected ->
                    selection.setSelected(choice.packageName, selected)
                },
                onSelectionStateChanged = ::notifySelectionStateChanged,
                expandedTexts = expandedTexts
            )
            return row
        }

        fun filter(query: String) {
            selection.filter(query)
            notifyDataSetChanged()
        }

        fun submitChoices(
            choices: List<DisplayScopeAppChoice>,
            pruneMissingSelections: Boolean
        ) {
            expandedTexts.retainAll(choices.flatMap { listOf(it.packageName + ":label:" + it.label, it.packageName + ":package") }.toSet())
            selection.submitChoices(choices, pruneMissingSelections)
            notifyDataSetChanged()
            notifySelectionStateChanged()
        }

        fun hasChoices(): Boolean = selection.hasChoices()

        fun areAllChoicesSelected(): Boolean = selection.areAllChoicesSelected()

        fun toggleAll() {
            selection.toggleAll()
            notifyDataSetChanged()
            notifySelectionStateChanged()
        }

        private fun notifySelectionStateChanged() = onSelectionStateChanged?.invoke()
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
                addView(expandableText(label.apply {
                    textSize = AirUiTokens.TextSize.Button
                    setTextColor(colorTextStrong)
                }))
                addView(expandableText(packageName.apply {
                    textSize = AirUiTokens.TextSize.Caption
                    setTextColor(colorTextMuted)
                }))
            })
            addView(toggle)
            tag = AppChoiceRowViews(icon, label, packageName, toggle)
        }
    }

    private fun bindAppChoiceRow(
        row: LinearLayout,
        choice: DisplayScopeAppChoice,
        selected: Boolean,
        onSelectedChanged: (Boolean) -> Unit,
        onSelectionStateChanged: () -> Unit,
        expandedTexts: MutableSet<String>
    ) {
        val views = row.tag as AppChoiceRowViews
        views.icon.setImageDrawable(choice.icon)
        views.label.text = choice.label
        views.packageName.text = choice.packageName
        (views.label.parent as ExpandableTextLayout).bindExpansion(expandedTexts, choice.packageName + ":label:" + choice.label)
        (views.packageName.parent as ExpandableTextLayout).bindExpansion(expandedTexts, choice.packageName + ":package")
        views.toggle.setOnCheckedChangeListener(null)
        views.toggle.isChecked = selected
        views.toggle.contentDescription = choice.label
        views.toggle.setOnCheckedChangeListener { _, checked ->
            onSelectedChanged(checked)
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
