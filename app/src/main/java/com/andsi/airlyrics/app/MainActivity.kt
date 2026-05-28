package com.andsi.airlyrics.app

import com.andsi.airlyrics.lyrics.storage.LyricsStorage

import android.animation.LayoutTransition
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.app.AppNavigator
import com.andsi.airlyrics.app.PermissionController
import com.andsi.airlyrics.app.controller.AppMediaController
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.animateChildrenCascade
import com.andsi.airlyrics.ui.components.animatePageEnter
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingStatusPreviewCard
import com.andsi.airlyrics.ui.components.horizontalButtons
import com.andsi.airlyrics.ui.components.label
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.pageContainer
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.components.sectionTitle
import com.andsi.airlyrics.ui.components.settingRow
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.components.softLayoutTransition
import com.andsi.airlyrics.ui.components.spacer
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.navigation.createBottomTabs
import com.andsi.airlyrics.ui.navigation.updateTabs
import com.andsi.airlyrics.ui.pages.createFloatingPage
import com.andsi.airlyrics.ui.pages.createMediaPage
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.ui.pages.createSettingsPage
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorBackground
import com.andsi.airlyrics.ui.theme.colorBubble
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

class MainActivity : AppCompatActivity() {
    private val activityState = MainActivityState()
    private val viewRefs = MainActivityViewRefs()

    internal var locked by activityState::locked
    internal var clickThrough by activityState::clickThrough
    internal var currentPage by activityState::currentPage
    internal var settingsSubPage by activityState::settingsSubPage
    internal var quickFloatingVisible by activityState::quickFloatingVisible
    internal val pageScrollY by activityState::pageScrollY
    internal var renderedPage by activityState::renderedPage
    internal var renderedSettingsSubPage by activityState::renderedSettingsSubPage
    internal var mediaRefreshState by activityState::mediaRefreshState
    internal var mediaPageRefreshScheduled by activityState::mediaPageRefreshScheduled

    internal var contentContainer by viewRefs::contentContainer
    internal val tabViews by viewRefs::tabViews
    internal var tabRow by viewRefs::tabRow
    internal var tabHighlight by viewRefs::tabHighlight
    internal var floatingPanelBackHandler by viewRefs::floatingPanelBackHandler

    internal val uiActions: MainUiActions by lazy { createMainUiActions() }
    private val floatingController: FloatingController by lazy { FloatingController(this) }
    internal val appMediaController: AppMediaController by lazy { AppMediaController(this) }
    private val lyricsController: LyricsController by lazy { LyricsController(this) }

    internal val mediaRefreshHandler = Handler(Looper.getMainLooper())

    internal val mediaStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BroadcastActions.MEDIA_UPDATE) return
            scheduleMediaPageRefresh()
        }
    }

    internal val importLyricsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult

            val media = getCurrentMediaSnapshot()
            if (media == null || media.title.isBlank()) {
                Toast.makeText(this, "请先播放并选择一首歌，再为当前音乐导入歌词", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val exists = LyricsStorage.hasLocalLyrics(
                context = this,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )

            if (exists) {
                AlertDialog.Builder(this)
                    .setTitle("当前音乐已有歌词")
                    .setMessage("${media.displayText}\n\n要覆盖已有本地歌词吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("覆盖") { _, _ ->
                        importLyricsForCurrentMedia(uri = uri, media = media, overwrite = true)
                    }
                    .show()
            } else {
                importLyricsForCurrentMedia(uri = uri, media = media, overwrite = false)
            }
        }

    internal val selectLyricsDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult

            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            LyricsStorage.saveLyricsDirUri(this, uri)
            Toast.makeText(this, "已设置歌词保存目录", Toast.LENGTH_LONG).show()
            renderCurrentPage(animateContent = false, animateTabs = false)
        }

    internal val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                "通知权限已开启"
            } else {
                "通知权限未开启，前台服务通知可能无法显示"
            }

            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            renderCurrentPage()
        }

    internal val floatingWindowStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BroadcastActions.WINDOW_VISIBILITY_CHANGED) return

            val visible = intent.getBooleanExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, false)
            updateQuickFloatingVisible(visible)
            updateTabs(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        quickFloatingVisible = isQuickFloatingVisible()
        applySystemBarsTheme()
        setContentView(createMainView())
        registerFloatingWindowStateReceiver()
        registerMediaStatusReceiver()
        autoSelectMediaSourceOnceIfNeeded()
        renderCurrentPage()
    }

    override fun onResume() {
        super.onResume()
        locked = FloatingLyricsStyleStore.isLocked(this)
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
        quickFloatingVisible = isQuickFloatingVisible()
        renderCurrentPage()
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (handleBackNavigation()) return
        super.onBackPressed()
    }

    internal fun handleBackNavigation(): Boolean {
        return AppNavigator.handleBackNavigation(this)
    }

    internal fun reloadFloatingLyrics() {
        floatingController.reloadLyrics()
    }

    internal fun reloadFloatingLyricsFromOnline() {
        floatingController.reloadLyricsFromOnline()
    }

    internal fun importLyricsForCurrentMedia(uri: Uri, media: CurrentMediaInfo, overwrite: Boolean) {
        lyricsController.importLyricsForCurrentMedia(uri, media, overwrite)
    }

    internal fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo) {
        lyricsController.deleteLyricsForCurrentMedia(media)
    }

    internal fun getCurrentMediaSnapshot(): CurrentMediaInfo? {
        return lyricsController.getCurrentMediaSnapshot()
    }

    internal fun floatingPreviewSummary(style: FloatingLyricsStyle): String {
        return "当前：${FloatingLyricsStyleStore.getPresetTitle(style.presetName)} · ${style.textSizeSp.toInt()}sp · ${FloatingLyricsStyleStore.getGravityTitle(style.gravity)} · 宽度 ${style.maxWidthPercent}%"
    }

    internal fun floatingDisplaySummary(): String {
        val lockedText = if (FloatingLyricsStyleStore.isLocked(this)) "锁定" else "可拖动"
        val clickThroughText = if (FloatingLyricsStyleStore.isClickThrough(this)) "穿透" else "可点击"
        return "$lockedText · $clickThroughText"
    }

    internal fun floatingLockButtonText(): String {
        return if (FloatingLyricsStyleStore.isLocked(this)) "拖动锁定：开启" else "拖动锁定：关闭"
    }

    internal fun floatingClickThroughButtonText(): String {
        return if (FloatingLyricsStyleStore.isClickThrough(this)) "点击穿透：开启" else "点击穿透：关闭"
    }

    internal fun floatingPreviewText(text: String, style: FloatingLyricsStyle): TextView {
        return TextView(this).apply {
            this.text = text
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(10), 0, dp(4))
            layoutParams = params
            applyFloatingPreviewStyle(style)
        }
    }

    internal fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle) {
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = style.gravity
        setTextColor(style.textColor)
        setShadowLayer(style.shadowRadius, 0f, 0f, style.shadowColor)
        setPadding(
            dp(style.paddingHorizontalDp.coerceAtMost(24)),
            dp(style.paddingVerticalDp.coerceAtMost(12)),
            dp(style.paddingHorizontalDp.coerceAtMost(24)),
            dp(style.paddingVerticalDp.coerceAtMost(12))
        )
        background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp.coerceAtMost(24)).toFloat()
                setColor(withAlpha(style.backgroundColor, style.backgroundAlpha))
            }
        } else {
            null
        }
    }

    internal fun optionGrid(items: List<OptionItem>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            items.chunked(2).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, item ->
                        addView(optionButton(item).apply {
                            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            params.setMargins(
                                if (index == 0) 0 else dp(6),
                                dp(10),
                                if (index == rowItems.lastIndex) 0 else dp(6),
                                0
                            )
                            layoutParams = params
                        })
                    }
                    if (rowItems.size == 1) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(6), 0, 0, 0)
                            }
                        })
                    }
                })
            }
        }
    }

    internal fun liveOptionGrid(items: List<KeyedOptionItem>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val buttons = mutableListOf<Pair<KeyedOptionItem, TextView>>()

            fun refreshSelection(selectedKey: String) {
                buttons.forEach { (item, button) ->
                    applyOptionButtonState(button, item.title, item.key == selectedKey)
                }
            }

            items.chunked(2).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, item ->
                        val button = TextView(this@MainActivity).apply {
                            gravity = Gravity.CENTER
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(dp(12), dp(11), dp(12), dp(11))
                            applyOptionButtonState(this, item.title, item.selected)
                            enableSoftPressFeedback(0.96f)
                            setOnClickListener {
                                item.action()
                                refreshSelection(item.key)
                                playTinyPulse(this)
                            }
                        }
                        buttons.add(item to button)
                        addView(button.apply {
                            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            params.setMargins(
                                if (index == 0) 0 else dp(6),
                                dp(10),
                                if (index == rowItems.lastIndex) 0 else dp(6),
                                0
                            )
                            layoutParams = params
                        })
                    }
                    if (rowItems.size == 1) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(6), 0, 0, 0)
                            }
                        })
                    }
                })
            }
        }
    }

    internal fun optionButton(item: OptionItem): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(11), dp(12), dp(11))
            applyOptionButtonState(this, item.title, item.selected)
            enableSoftPressFeedback(0.96f)
            setOnClickListener {
                item.action()
                playTinyPulse(this)
            }
        }
    }

    internal fun applyOptionButtonState(button: TextView, title: String, selected: Boolean) {
        button.text = if (selected) "✓ $title" else title
        button.setTextColor(if (selected) Color.WHITE else colorText)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (selected) colorAccent else colorSurfaceLight)
            setStroke(dp(1), if (selected) colorAccentLight else colorStroke)
        }
    }

    internal fun sliderRow(
        title: String,
        value: Int,
        min: Int,
        max: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        val safeValue = value.coerceIn(min, max)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))

            val valueText = TextView(this@MainActivity).apply {
                text = "$title：$safeValue$suffix"
                textSize = 14f
                setTextColor(colorText)
                setPadding(0, 0, 0, dp(6))
            }
            addView(valueText)

            addView(SeekBar(this@MainActivity).apply {
                this.max = max - min
                progress = safeValue - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val newValue = min + progress
                        valueText.text = "$title：$newValue$suffix"
                        if (fromUser) onChanged(newValue)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            })
        }
    }

    internal fun colorControl(
        title: String,
        color: Int,
        onChanged: (Int) -> Unit
    ): LinearLayout {
        var red = Color.red(color)
        var green = Color.green(color)
        var blue = Color.blue(color)
        var alpha = Color.alpha(color)
        var rgbExpanded = false

        val standardColors = listOf(
            "蓝" to Color.rgb(66, 165, 245),
            "紫" to Color.rgb(126, 87, 194),
            "粉" to Color.rgb(236, 64, 122),
            "青" to Color.rgb(38, 198, 218),
            "绿" to Color.rgb(102, 187, 106),
            "橙" to Color.rgb(255, 167, 38),
            "红" to Color.rgb(239, 83, 80),
            "白" to Color.WHITE
        )

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)

            val preview = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(colorText)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(preview)
            addView(smallHint(this@MainActivity, "点击标准色会立即应用；改动 RGB 后会进入自定义颜色状态。"))

            val swatchViews = mutableListOf<Pair<Int?, TextView>>()
            val swatchGrid = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(swatchGrid)

            val fineTuneButton = actionButton(this@MainActivity, "展开 RGB 细调") { }
            val rgbPanel = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(6), 0, 0)
            }
            addView(fineTuneButton)
            addView(rgbPanel)

            fun currentColor(): Int = Color.argb(alpha, red, green, blue)

            fun selectedStandardColor(): Int? {
                val current = currentColor()
                return standardColors.firstOrNull { (_, swatchColor) ->
                    Color.red(current) == Color.red(swatchColor) &&
                        Color.green(current) == Color.green(swatchColor) &&
                        Color.blue(current) == Color.blue(swatchColor) &&
                        Color.alpha(current) == Color.alpha(swatchColor)
                }?.second
            }

            fun swatchBackground(swatchColor: Int, selected: Boolean): GradientDrawable {
                return GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(swatchColor)
                    setStroke(dp(if (selected) 3 else 1), if (selected) colorAccent else colorStroke)
                }
            }

            fun refreshSwatches() {
                val current = currentColor()
                val selectedPreset = selectedStandardColor()
                swatchViews.forEach { (presetColor, view) ->
                    val selected = if (presetColor == null) {
                        selectedPreset == null
                    } else {
                        selectedPreset != null &&
                            Color.red(selectedPreset) == Color.red(presetColor) &&
                            Color.green(selectedPreset) == Color.green(presetColor) &&
                            Color.blue(selectedPreset) == Color.blue(presetColor)
                    }
                    val displayColor = presetColor ?: current
                    view.background = swatchBackground(displayColor, selected)
                    view.setTextColor(if (isDarkColor(displayColor)) Color.WHITE else Color.rgb(28, 34, 46))
                }
            }

            fun refreshPreview(dispatch: Boolean) {
                val newColor = currentColor()
                preview.text = "$title：${FloatingLyricsStyleStore.colorSummary(newColor)}"
                preview.background = colorPreviewBackground(newColor)
                refreshSwatches()
                if (dispatch) onChanged(newColor)
            }

            fun colorSliderRow(
                sliderTitle: String,
                initialValue: Int,
                onValueChanged: (Int) -> Unit
            ): Pair<SeekBar, TextView> {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(4))
                }
                val valueText = TextView(this@MainActivity).apply {
                    text = "$sliderTitle：$initialValue"
                    textSize = 14f
                    setTextColor(colorText)
                    setPadding(0, 0, 0, dp(6))
                }
                row.addView(valueText)
                val seekBar = SeekBar(this@MainActivity).apply {
                    max = 255
                    progress = initialValue.coerceIn(0, 255)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            valueText.text = "$sliderTitle：$progress"
                            if (fromUser) {
                                onValueChanged(progress)
                                refreshPreview(dispatch = true)
                            }
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                row.addView(seekBar)
                rgbPanel.addView(row)
                return seekBar to valueText
            }

            val redSlider = colorSliderRow("R", red) { red = it }
            val greenSlider = colorSliderRow("G", green) { green = it }
            val blueSlider = colorSliderRow("B", blue) { blue = it }
            val alphaSlider = colorSliderRow("不透明度", alpha) { alpha = it }

            fun setSlider(pair: Pair<SeekBar, TextView>, titleText: String, value: Int) {
                pair.first.progress = value.coerceIn(0, 255)
                pair.second.text = "$titleText：$value"
            }

            fun syncSliders() {
                setSlider(redSlider, "R", red)
                setSlider(greenSlider, "G", green)
                setSlider(blueSlider, "B", blue)
                setSlider(alphaSlider, "不透明度", alpha)
            }

            fun makeSwatch(label: String, presetColor: Int?, onClick: () -> Unit): TextView {
                return TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(6), 0, dp(6), 0)
                    enableSoftPressFeedback(0.9f)
                    setOnClickListener {
                        onClick()
                        playTinyPulse(this)
                    }
                    swatchViews.add(presetColor to this)
                }
            }

            val swatches = standardColors.map { (label, swatchColor) ->
                Pair(label, swatchColor as Int?)
            } + listOf("自定义" to null)

            swatches.chunked(3).forEach { rowItems ->
                swatchGrid.addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, (label, presetColor) ->
                        val button = makeSwatch(label, presetColor) {
                            if (presetColor == null) {
                                rgbExpanded = true
                                rgbPanel.visibility = View.VISIBLE
                                fineTuneButton.text = "收起 RGB 细调"
                            } else {
                                red = Color.red(presetColor)
                                green = Color.green(presetColor)
                                blue = Color.blue(presetColor)
                                syncSliders()
                                refreshPreview(dispatch = true)
                            }
                        }
                        addView(button.apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                                setMargins(
                                    if (index == 0) 0 else dp(5),
                                    dp(8),
                                    if (index == rowItems.lastIndex) 0 else dp(5),
                                    0
                                )
                            }
                        })
                    }
                    repeat(3 - rowItems.size) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(5), 0, 0, 0)
                            }
                        })
                    }
                })
            }

            fineTuneButton.setOnClickListener {
                rgbExpanded = !rgbExpanded
                rgbPanel.visibility = if (rgbExpanded) View.VISIBLE else View.GONE
                fineTuneButton.text = if (rgbExpanded) "收起 RGB 细调" else "展开 RGB 细调"
                playTinyPulse(fineTuneButton)
            }

            syncSliders()
            refreshPreview(dispatch = false)
        }
    }

    internal fun colorPreviewBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(withAlpha(color, 42))
            setStroke(dp(1), withAlpha(color, 190))
        }
    }

    internal fun isDarkColor(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
        return luminance < 150
    }

    internal fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    internal fun mediaSourceCard(controller: MediaController, selected: Boolean): View {
        return card(this) {
            val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                .orEmpty()
                .ifBlank { "未知歌曲" }
            val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "未知艺术家"
            val appName = getAppName(controller.packageName)
            val state = getPlaybackStateText(controller.playbackState?.state)

            addView(label(this@MainActivity, if (selected) "已连接" else "可选择", if (selected) colorAccentLight else colorTextMuted).apply {
                tag = "media_source_status:${controller.packageName}"
            })
            addView(bigText(this@MainActivity, appName))
            addView(normalText(this@MainActivity, "$title - $artist"))
            addView(smallHint(this@MainActivity, state))
            enableSoftPressFeedback(0.985f)
            setOnClickListener {
                uiActions.selectMediaSource(controller.packageName, this)
            }
        }
    }

    internal fun settingGrid(vararg items: FloatingSettingTile): LinearLayout {
        val tileItems = items.toList()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tileItems.chunked(2).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowItems.forEachIndexed { index, item ->
                        addView(floatingTile(item).apply {
                            val params = LinearLayout.LayoutParams(0, dp(132), 1f)
                            params.setMargins(
                                if (index == 0) 0 else dp(6),
                                0,
                                if (index == rowItems.lastIndex) 0 else dp(6),
                                dp(12)
                            )
                            layoutParams = params
                        })
                    }
                    if (rowItems.size == 1) {
                        addView(View(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(dp(6), 0, 0, 0)
                            }
                        })
                    }
                })
            }
        }
    }

    internal fun floatingTile(item: FloatingSettingTile): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(colorCard)
                setStroke(dp(1), colorStroke)
            }

            addView(TextView(this@MainActivity).apply {
                text = item.mark
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    setMargins(0, 0, 0, dp(10))
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorAccent)
                }
            })

            addView(TextView(this@MainActivity).apply {
                text = item.title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })

            addView(TextView(this@MainActivity).apply {
                text = item.subtitle
                textSize = 12f
                setTextColor(colorTextMuted)
                maxLines = 2
                setPadding(0, dp(4), 0, 0)
            })

            enableSoftPressFeedback(0.975f)
            setOnClickListener { item.onClick(this) }
        }
    }

    internal fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(colorBubble)
                setStroke(dp(1), colorAccentSoft)
            }
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels - dp(72)).coerceAtMost(dp(360)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(dp(18), dp(18), dp(18), dp(18))
            }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 20f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(colorTextStrong)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        textSize = 13f
                        setTextColor(colorTextMuted)
                        setPadding(0, dp(4), 0, 0)
                    })
                })
                addView(TextView(this@MainActivity).apply {
                    text = "×"
                    gravity = Gravity.CENTER
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextMuted)
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        setMargins(dp(10), 0, 0, 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorSurfaceLight)
                    }
                    enableSoftPressFeedback(0.9f)
                    setOnClickListener { onClose() }
                })
            })
            addView(spacer(this@MainActivity, 8))
            content()
        }
    }

    internal fun showFloatingSettingPanel(
        title: String,
        subtitle: String,
        content: LinearLayout.() -> Unit
    ) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(28).toFloat(), dp(28).toFloat(),
                    dp(28).toFloat(), dp(28).toFloat(),
                    0f, 0f,
                    0f, 0f
                )
                setColor(colorSurface)
                setStroke(dp(1), colorStroke)
            }

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, dp(8))
            })
            content()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(panel) })
            .create()

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.setDimAmount(0.08f)
                window.setGravity(Gravity.BOTTOM)
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
    }

    internal fun refreshMediaButton(): View {
        lateinit var row: LinearLayout
        lateinit var labelView: TextView
        var progressView: ProgressBar? = null

        fun applyButtonState(animateDone: Boolean = false) {
            val buttonText = when (mediaRefreshState) {
                RefreshState.IDLE -> "刷新媒体状态"
                RefreshState.REFRESHING -> "刷新中"
                RefreshState.DONE -> "已刷新"
            }
            val buttonColor = when (mediaRefreshState) {
                RefreshState.IDLE -> colorAccent
                RefreshState.REFRESHING -> colorSurfaceLight
                RefreshState.DONE -> colorAccentSoft
            }

            row.isEnabled = mediaRefreshState != RefreshState.REFRESHING
            row.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(buttonColor)
            }
            labelView.text = buttonText
            labelView.setTextColor(if (mediaRefreshState == RefreshState.REFRESHING) colorText else Color.WHITE)

            if (mediaRefreshState == RefreshState.REFRESHING && progressView == null) {
                progressView = ProgressBar(this@MainActivity).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        setMargins(0, 0, dp(10), 0)
                    }
                }
                row.addView(progressView, 0)
            } else if (mediaRefreshState != RefreshState.REFRESHING && progressView != null) {
                row.removeView(progressView)
                progressView = null
            }

            if (animateDone && mediaRefreshState == RefreshState.DONE) {
                row.rotation = -2f
                row.animate()
                    .rotation(0f)
                    .scaleX(1.018f)
                    .scaleY(1.018f)
                    .setDuration(150L)
                    .setInterpolator(OvershootInterpolator(0.65f))
                    .withEndAction {
                        row.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(130L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }
        }

        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(10), 0, 0)
            layoutParams = params
            enableSoftPressFeedback(0.97f)
        }

        labelView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(labelView)

        applyButtonState()
        row.setOnClickListener {
            if (mediaRefreshState == RefreshState.REFRESHING) return@setOnClickListener
            playTinyPulse(row)
            startMediaRefreshFeedback { applyButtonState(animateDone = true) }
            applyButtonState()
        }
        return row
    }

    internal fun startMediaRefreshFeedback(onStateChanged: () -> Unit) {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        mediaRefreshState = RefreshState.REFRESHING
        onStateChanged()

        mediaRefreshHandler.postDelayed({
            mediaRefreshState = RefreshState.DONE
            onStateChanged()
            mediaRefreshHandler.postDelayed({
                if (currentPage == Page.MEDIA) {
                    renderCurrentPage(animateContent = false, animateTabs = false)
                }
            }, 260L)
        }, 650L)
    }

    internal fun updateMediaSourceSelectionVisuals(selectedPackage: String) {
        val root = contentContainer ?: return
        fun visit(view: View) {
            if (view is TextView) {
                val tagText = view.tag as? String
                if (tagText?.startsWith("media_source_status:") == true) {
                    val packageName = tagText.removePrefix("media_source_status:")
                    val selected = packageName == selectedPackage
                    view.text = if (selected) "已连接" else "可选择"
                    view.setTextColor(if (selected) colorAccentLight else colorTextMuted)
                    if (selected) playTinyPulse(view)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }

    internal fun showFloatingLyrics(): Boolean {
        return floatingController.showLyrics()
    }

    internal fun hideFloatingLyrics(): Boolean {
        return floatingController.hideLyrics()
    }

    internal fun toggleFloatingFromNav() {
        floatingController.toggleFromNav()
    }

    internal fun isQuickFloatingVisible(): Boolean {
        return floatingController.isQuickFloatingVisible()
    }

    internal fun updateQuickFloatingVisible(visible: Boolean) {
        floatingController.updateQuickFloatingVisible(visible)
    }

    internal fun toggleLock() {
        floatingController.toggleLock()
    }

    internal fun toggleClickThrough() {
        floatingController.toggleClickThrough()
    }

    internal fun applyFloatingPreset(preset: String) {
        floatingController.applyPreset(preset)
    }

    internal fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true) {
        floatingController.applyTextSize(textSizeSp, refreshPage)
    }

    internal fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true) {
        floatingController.applyTextColor(color, refreshPage)
    }

    internal fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true) {
        floatingController.applyBackgroundColor(color, refreshPage)
    }

    internal fun applyFloatingGravity(gravity: Int) {
        floatingController.applyGravity(gravity)
    }

    internal fun notifyFloatingStyleChanged() {
        floatingController.notifyStyleChanged()
    }

    internal fun isDarkTheme(): Boolean {
        return ThemeSettingsStore.isDark(this)
    }

    internal fun setDarkTheme(enabled: Boolean) {
        ThemeSettingsStore.setDark(this, enabled)
    }

    internal fun toggleThemeMode() {
        val nextDark = !isDarkTheme()
        setDarkTheme(nextDark)
        applySystemBarsTheme()
        val oldContainer = contentContainer
        oldContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(90L)
            ?.withEndAction {
                setContentView(createMainView())
                renderCurrentPage()
                contentContainer?.alpha = 0f
                contentContainer?.animate()
                    ?.alpha(1f)
                    ?.setDuration(180L)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
            }
            ?.start()
            ?: run {
                setContentView(createMainView())
                renderCurrentPage()
            }
    }

    internal fun applySystemBarsTheme() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = colorSurface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val lightFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (isDarkTheme()) {
                window.decorView.systemUiVisibility and lightFlag.inv()
            } else {
                window.decorView.systemUiVisibility or lightFlag
            }
        }
    }

    internal fun getPlaybackStateText(state: Int?): String {
        return appMediaController.getPlaybackStateText(state)
    }

    internal fun autoSelectMediaSourceOnceIfNeeded() {
        appMediaController.autoSelectSourceOnceIfNeeded()
    }

    internal fun getActiveMediaControllers(): List<MediaController> {
        return appMediaController.getActiveControllers()
    }

    internal fun notifyFloatingServiceSourceChangedIfVisible(packageName: String?) {
        floatingController.notifySourceChangedIfVisible(packageName)
    }

    internal fun getAppName(packageName: String): String {
        return appMediaController.getAppName(packageName)
    }

    internal fun showLyricsDir() {
        lyricsController.showLyricsDir()
    }

    internal fun requestOverlayPermission() {
        PermissionController.requestOverlayPermission(this)
    }

    internal fun hasNotificationPermission(): Boolean {
        return PermissionController.hasNotificationPermission(this)
    }


    internal fun hasNotificationListenerAccess(): Boolean {
        return PermissionController.hasNotificationListenerAccess(this)
    }

    internal fun requestNotificationPermissionIfNeeded() {
        PermissionController.requestNotificationPermissionIfNeeded(this)
    }


    internal fun scheduleMediaPageRefresh() {
        if (currentPage != Page.MEDIA) return
        if (mediaPageRefreshScheduled) return

        mediaPageRefreshScheduled = true
        mediaRefreshHandler.postDelayed({
            mediaPageRefreshScheduled = false
            if (currentPage == Page.MEDIA) {
                renderCurrentPage(animateContent = false, animateTabs = false)
            }
        }, 120L)
    }

    internal fun registerFloatingWindowStateReceiver() {
        val filter = IntentFilter(BroadcastActions.WINDOW_VISIBILITY_CHANGED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(floatingWindowStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(floatingWindowStateReceiver, filter)
        }
    }

    internal fun registerMediaStatusReceiver() {
        val filter = IntentFilter(BroadcastActions.MEDIA_UPDATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaStatusReceiver, filter)
        }
    }

    internal fun startLyricsService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    internal fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(floatingWindowStateReceiver) }
        runCatching { unregisterReceiver(mediaStatusReceiver) }
        super.onDestroy()
    }


}
