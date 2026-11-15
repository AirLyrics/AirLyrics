package com.andsi.airlyrics.ui.pages.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.LyricsGlowStyle
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.settings.store.FloatingColorSwatchStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.robolectric.util.ReflectionHelpers
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.SettingsPanelHeader
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.state.FloatingPanelId
import com.andsi.airlyrics.ui.theme.colorBackground
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingColorPanelTest {
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var host: MainUiHost
    private lateinit var scope: FloatingPageScope
    private lateinit var page: View
    private var width = 360
    private var height = 640

    @Before fun clearPreferences() {
        val app = RuntimeEnvironment.getApplication()
        listOf("floating_lyrics_style", "app_theme", "floating_color_swatches").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun close() {
        controller?.close()
        RuntimeEnvironment.setFontScale(1f)
        clearPreferences()
    }

    private fun open(id: FloatingPanelId, width: Int = 360, height: Int = 640, scale: Float = 1f, initialize: Boolean = true) {
        this.width = width
        this.height = height
        RuntimeEnvironment.setQualifiers("en-rUS-w${width}dp-h${height}dp-mdpi")
        RuntimeEnvironment.setFontScale(scale)
        host = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get().graph.uiHost
        if (initialize) {
            val style = host.floatingStyle().copy(
                textColor = Color.argb(173, 12, 34, 56),
                wordByWordHighlightColor = Color.argb(91, 71, 83, 109),
                glow = LyricsGlowStyle(enabled = false, strengthPercent = 130, spreadPercent = 150)
            )
            host.applyFloatingStyle(style)
        }
        scope = FloatingPageScope(host)
        page = scope.createView()
        layout()
        scope.restoringPanel = true
        scope.panelOpeners.getValue(id).invoke()
        layout()
    }

    private fun layout() {
        all(page).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_LTR }
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, width, height)
    }

    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun visible(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { visible(view.getChildAt(it)) } else emptyList()

    private fun panel() = requireNotNull(scope.activeBubble)
    private fun action(label: Int) = visible(panel()).single {
        it.isClickable && if (label == R.string.ui_custom_color) {
            it.getTag(R.id.interaction_anchor) == "floating.color.custom"
        } else it.contentDescription == host.getString(label)
    }
    private fun glow() = visible(panel()).filterIsInstance<SwitchCompat>().single()
    private fun rgb(name: String) = visible(panel()).filterIsInstance<SeekBar>().single {
        it.getTag(R.id.interaction_anchor) == "floating.color.rgb.$name"
    }
    private fun spread() = visible(panel()).filterIsInstance<SeekBar>().single {
        it.contentDescription == host.getString(R.string.ui_glow_spread_short)
    }

    /** Dispatch real SeekBar gestures so preview callbacks and release commits both run. */
    private fun drag(slider: SeekBar, fraction: Float) {
        val x = slider.paddingLeft + (slider.width - slider.paddingLeft - slider.paddingRight) * fraction
        val start = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to slider.width * 0.2f,
            MotionEvent.ACTION_MOVE to x, MotionEvent.ACTION_UP to x).forEachIndexed { index, (action, px) ->
            val event = MotionEvent.obtain(start, start + index * 20L, action, px, slider.height / 2f, 0)
            try { assertTrue(slider.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    @Test fun glowRgbBackAndSpreadRemainIndependentAndLastTrackIsFullyOperable() {
        open(FloatingPanelId.HIGHLIGHT_COLOR, width = 320, height = 420, scale = 1.8f)
        val originalText = host.floatingStyle().textColor
        glow().performClick()
        layout()
        assertTrue(host.floatingStyle().glow.enabled)
        action(R.string.ui_custom_color).performClick()
        layout()
        assertEquals(3, visible(panel()).filterIsInstance<SeekBar>().size)
        assertTrue(visible(panel()).none { it is SwitchCompat })
        drag(rgb("R"), 0.25f)
        drag(rgb("G"), 0.5f)
        drag(rgb("B"), 0.75f)
        val adjusted = host.floatingStyle().wordByWordHighlightColor
        assertEquals(91, Color.alpha(adjusted))
        capture("rgb-small")
        action(R.string.ui_back).performClick()
        layout()
        assertEquals(FloatingPanelId.HIGHLIGHT_COLOR, host.interactions.panel)
        assertEquals(LyricsGlowStyle(true, 130, 150), host.floatingStyle().glow)
        val grid = visible(panel()).filterIsInstance<AdaptiveGridLayout>().single()
        assertEquals(0, (0 until grid.childCount).count { grid.getChildAt(it).isSelected })
        val scroll = all(panel()).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        layout()
        val slider = spread()
        val bounds = Rect(0, 0, slider.width, slider.height)
        panel().offsetDescendantRectToMyCoords(slider, bounds)
        assertTrue("last slider top is clipped: $bounds", bounds.top >= scroll.top)
        assertTrue("last slider bottom is clipped: $bounds", bounds.bottom <= scroll.bottom)
        assertEquals(host.dp(48), slider.height)
        drag(slider, 0.95f)
        val finalGlow = host.floatingStyle().glow
        assertEquals(130, finalGlow.strengthPercent)
        assertTrue(finalGlow.spreadPercent > 150)
        assertEquals(0, finalGlow.spreadPercent % 10)
        assertEquals(adjusted, host.floatingStyle().wordByWordHighlightColor)
        assertEquals(originalText, host.floatingStyle().textColor)
        capture("glow-small-bottom")

        glow().performClick()
        layout()
        assertTrue(visible(panel()).filterIsInstance<SeekBar>().isEmpty())
        glow().performClick()
        layout()
        assertEquals(finalGlow, host.floatingStyle().glow)
        action(R.string.ui_custom_color).performClick()
        layout()
        assertTrue(host.floatingPanelBackHandler!!.invoke())
        layout()
        assertEquals(FloatingPanelId.HIGHLIGHT_COLOR, host.interactions.panel)
        assertEquals(finalGlow, host.floatingStyle().glow)
        assertTrue(host.floatingPanelBackHandler!!.invoke())
        assertNull(host.interactions.panel)
    }

    @Test fun textPresetsStayInTwoRowsAndRgbKeepsBothAlphaAndHighlightColor() {
        open(FloatingPanelId.TEXT_COLOR)
        val highlight = host.floatingStyle().wordByWordHighlightColor
        val grid = visible(panel()).filterIsInstance<AdaptiveGridLayout>().single()
        assertEquals(4, grid.columns)
        assertEquals(8, grid.childCount)
        val bounds = (0 until grid.childCount).map { grid.getChildAt(it) }.associateWith {
            Rect(it.left, it.top, it.right, it.bottom)
        }
        bounds.keys.forEach {
            assertTrue(it.width >= host.dp(48))
            assertTrue(it.height >= host.dp(48))
            assertFalse(it.contentDescription.isNullOrBlank())
            assertFalse(it is TextView)
        }
        action(R.string.ui_blue).performClick()
        layout()
        assertEquals(Color.argb(173, 66, 165, 245), host.floatingStyle().textColor)
        action(R.string.ui_red).performClick()
        layout()
        bounds.forEach { (view, rect) -> assertEquals(rect, Rect(view.left, view.top, view.right, view.bottom)) }
        assertEquals(1, bounds.keys.count { it.isSelected })
        assertEquals(173, Color.alpha(host.floatingStyle().textColor))
        capture("text-presets")
        action(R.string.ui_custom_color).performClick()
        layout()
        drag(rgb("B"), 0.4f)
        assertTrue(host.floatingPanelBackHandler!!.invoke())
        layout()
        assertEquals(0, bounds.keys.count { it.isSelected })
        assertEquals(173, Color.alpha(host.floatingStyle().textColor))
        assertEquals(highlight, host.floatingStyle().wordByWordHighlightColor)
        val texts = visible(panel()).filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(1, texts.count { it.matches(Regex("#[0-9A-F]{6}")) })
        assertFalse(texts.any { it.contains("A173") || it.contains("Opacity") || it.contains("RGB tune") })
    }

    @Test fun resetAndUndoInRgbEditorPreserveAlphaAndNestedNavigation() {
        open(FloatingPanelId.HIGHLIGHT_COLOR)
        val initial = host.floatingStyle()
        action(R.string.ui_custom_color).performClick()
        layout()
        drag(rgb("G"), 0.8f)
        val adjusted = host.floatingStyle()
        action(R.string.ui_reset).performClick()
        layout()
        val reset = host.floatingStyle()
        val defaults = host.floatingStyleDefaults(reset.presetName)
        assertEquals(initial.textColor, reset.textColor)
        assertEquals(91, Color.alpha(reset.wordByWordHighlightColor))
        assertEquals(defaults.wordByWordHighlightColor and 0xFFFFFF, reset.wordByWordHighlightColor and 0xFFFFFF)
        assertEquals(defaults.glow, reset.glow)
        assertEquals(3, visible(panel()).filterIsInstance<SeekBar>().size)
        action(R.string.ui_undo).performClick()
        layout()
        assertEquals(adjusted, host.floatingStyle())
        action(R.string.ui_back).performClick()
        layout()
        assertEquals(FloatingPanelId.HIGHLIGHT_COLOR, host.interactions.panel)
    }

    @Test fun normalHighlightLayoutAndLargeLightLayoutKeepOnlyContentScrollable() {
        val app = RuntimeEnvironment.getApplication()
        for (dark in listOf(true, false)) {
            ThemeSettingsStore.setDark(app, dark)
            open(FloatingPanelId.HIGHLIGHT_COLOR, height = if (dark) 640 else 420, scale = if (dark) 1f else 2f)
            glow().performClick()
            layout()
            val header = all(panel()).filterIsInstance<SettingsPanelHeader>().single()
            val scroll = all(panel()).filterIsInstance<ScrollView>().single()
            assertTrue(panel().height <= height)
            assertTrue(header.bottom <= scroll.top)
            assertTrue(scroll.height > 0)
            capture(if (dark) "highlight-dark" else "highlight-light-large")
            if (!dark) {
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                layout()
                capture("highlight-light-large-bottom")
            }
            controller?.close()
            controller = null
        }
    }

    private fun presetGrid() = visible(panel()).filterIsInstance<AdaptiveGridLayout>().single {
        it.getTag(R.id.interaction_anchor) == "floating.color.presets"
    }

    private fun savedGrid() = visible(panel()).filterIsInstance<AdaptiveGridLayout>().single {
        it.getTag(R.id.interaction_anchor) == "floating.color.saved"
    }

    private fun applySaved(code: String) = visible(savedGrid()).single {
        it.contentDescription == code && it.isClickable
    }.performClick()

    private fun editRgb(color: Int) {
        action(R.string.ui_custom_color).performClick()
        layout()
        // Exact channels use the native accessibility action: touch coordinates are pixel-quantized.
        listOf("R" to Color.red(color), "G" to Color.green(color), "B" to Color.blue(color)).forEach { (name, value) ->
            val arguments = android.os.Bundle().apply {
                putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat())
            }
            assertTrue(rgb(name).performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
        }
    }

    /** Flush Android's queued writes, drop its preference cache, and open without saved UI state. */
    private fun coldRestart(id: FloatingPanelId) {
        val app = RuntimeEnvironment.getApplication()
        controller?.close()
        controller = null
        ReflectionHelpers.callStaticMethod<Any?>(Class.forName("android.app.QueuedWork"), "waitToFinish")
        assertTrue(File(app.applicationInfo.dataDir, "shared_prefs/floating_lyrics_style.xml").isFile)
        ReflectionHelpers.getStaticField<MutableMap<*, *>?>(Class.forName("android.app.ContextImpl"),
            "sSharedPrefsCache")?.clear()
        open(id, initialize = false)
    }

    @Test fun appliedColorsAndSharedFavoritesSurviveColdPreferenceReloadIndependently() {
        ThemeSettingsStore.setDark(RuntimeEnvironment.getApplication(), true)
        open(FloatingPanelId.TEXT_COLOR)
        val unbookmarked = Color.rgb(18, 52, 86)
        editRgb(unbookmarked)
        assertEquals(unbookmarked and 0xFFFFFF, host.floatingStyle().textColor and 0xFFFFFF)
        assertTrue(host.floatingPanelBackHandler!!.invoke())
        assertTrue(FloatingColorSwatchStore.colors(host).isEmpty())
        coldRestart(FloatingPanelId.TEXT_COLOR)
        assertEquals(Color.argb(173, 18, 52, 86), host.floatingStyle().textColor)
        assertTrue(FloatingColorSwatchStore.colors(host).isEmpty())

        val favorite = Color.rgb(168, 83, 80)
        editRgb(favorite)
        action(R.string.ui_save_color_swatch).performClick()
        layout()
        assertFalse(action(R.string.ui_saved).isEnabled)
        action(R.string.ui_back).performClick()
        layout()
        assertEquals(1, savedGrid().childCount)
        action(R.string.ui_blue).performClick()
        layout()
        coldRestart(FloatingPanelId.TEXT_COLOR)
        val blue = Color.argb(173, 66, 165, 245)
        assertEquals(blue, host.floatingStyle().textColor)
        assertEquals(listOf(favorite), FloatingColorSwatchStore.colors(host))
        assertTrue(action(R.string.ui_custom_color).contentDescription.toString().endsWith("#42A5F5"))
        assertEquals(4, savedGrid().columns)
        assertEquals(presetGrid().getChildAt(0).width, savedGrid().getChildAt(0).width)
        applySaved("#A85350")
        layout()
        assertEquals(Color.argb(173, 168, 83, 80), host.floatingStyle().textColor)
        capture("text-favorites")
        action(R.string.ui_blue).performClick()
        layout()

        scope.panelOpeners.getValue(FloatingPanelId.HIGHLIGHT_COLOR).invoke()
        layout()
        applySaved("#A85350")
        layout()
        assertEquals(blue, host.floatingStyle().textColor)
        assertEquals(Color.argb(91, 168, 83, 80), host.floatingStyle().wordByWordHighlightColor)
        action(R.string.ui_custom_color).performClick()
        layout()
        assertFalse(action(R.string.ui_saved).isEnabled)
        action(R.string.ui_saved).performClick()
        assertEquals(listOf(favorite), FloatingColorSwatchStore.colors(host))
        action(R.string.ui_back).performClick()
        layout()
        glow().performClick()
        layout()
        capture("highlight-favorites")
        action(R.string.ui_reset).performClick()
        layout()
        assertEquals(listOf(favorite), FloatingColorSwatchStore.colors(host))
        assertEquals(blue, host.floatingStyle().textColor)
        coldRestart(FloatingPanelId.HIGHLIGHT_COLOR)
        assertEquals(listOf(favorite), FloatingColorSwatchStore.colors(host))
        assertEquals(blue, host.floatingStyle().textColor)
        assertEquals(91, Color.alpha(host.floatingStyle().wordByWordHighlightColor))
    }

    @Test fun manyFavoritesRemainOrderedAndLastControlsFitInLandscape() {
        val app = RuntimeEnvironment.getApplication()
        val colors = (0 until 17).map { Color.rgb(it * 11, 40 + it, 100 + it) }
        colors.forEach { FloatingColorSwatchStore.save(app, it) }
        open(FloatingPanelId.HIGHLIGHT_COLOR, width = 640, height = 320, scale = 1.5f)
        glow().performClick()
        layout()
        assertEquals(colors, FloatingColorSwatchStore.colors(host))
        assertEquals(4, presetGrid().columns)
        assertEquals(4, savedGrid().columns)
        val scroll = all(panel()).filterIsInstance<ScrollView>().single()
        val header = all(panel()).filterIsInstance<SettingsPanelHeader>().single()
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        layout()
        assertTrue(header.bottom <= scroll.top)
        val slider = spread()
        val bounds = Rect(0, 0, slider.width, slider.height)
        panel().offsetDescendantRectToMyCoords(slider, bounds)
        assertTrue(bounds.top >= scroll.top)
        assertTrue(bounds.bottom <= scroll.bottom)
        drag(slider, 1f)
        assertEquals(200, host.floatingStyle().glow.spreadPercent)
        capture("landscape-many-favorites")

        action(R.string.ui_custom_color).performClick()
        layout()
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        layout()
        val save = action(R.string.ui_save_color_swatch)
        val saveBounds = Rect(0, 0, save.width, save.height)
        panel().offsetDescendantRectToMyCoords(save, saveBounds)
        assertTrue(saveBounds.bottom <= scroll.bottom)
        save.performClick()
        assertEquals(colors + Color.rgb(71, 83, 109), FloatingColorSwatchStore.colors(host))
    }

    @Test fun existingArgbKeysAndFontOpacityRemainCompatibleWithRgbFavorites() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE).edit()
            .putInt("text_color", Color.argb(47, 10, 20, 30))
            .putInt("karaoke_highlight_color", Color.argb(89, 40, 50, 60)).commit()
        open(FloatingPanelId.TEXT_COLOR, initialize = false)
        assertEquals(Color.argb(47, 10, 20, 30), host.floatingStyle().textColor)
        assertEquals(Color.argb(89, 40, 50, 60), host.floatingStyle().wordByWordHighlightColor)
        FloatingLyricsStyleStore.setTextAlpha(host, 113)
        editRgb(Color.rgb(168, 83, 80))
        assertEquals(Color.argb(113, 168, 83, 80), host.floatingStyle().textColor)
        assertEquals(Color.argb(89, 40, 50, 60), host.floatingStyle().wordByWordHighlightColor)
        assertEquals("#A85350", compactColorSummary(host.floatingStyle().textColor))
        assertEquals("#28323C", scope.highlightEffectsSubtitle())
    }

    @Test fun glowTogglesKeepPreviewGeometryAndBackgroundViewportStable() {
        open(FloatingPanelId.HIGHLIGHT_COLOR)
        host.setLyricsSwitchAnimationMode(com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode.NONE)
        host.setWordByWordLyricsEnabled(false)
        scope.refreshFloatingPreview()
        layout()
        val background = all(page).filterIsInstance<ScrollView>().single {
            it.parent is com.andsi.airlyrics.ui.layout.ResponsivePage
        }
        background.scrollTo(0, 500)
        layout()
        val tile = all(page).single { it.getTag(R.id.interaction_anchor) == "panel:HIGHLIGHT_COLOR" }
        fun tileTop(): Int {
            val rect = Rect(0, 0, tile.width, tile.height)
            (page as ViewGroup).offsetDescendantRectToMyCoords(tile, rect)
            return rect.top
        }
        val originalTop = tileTop()
        val originalY = background.scrollY
        val originalPreviewHeight = scope.previewHandle!!.cardView.height
        val backgroundPage = background.parent as ViewGroup
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, backgroundPage.descendantFocusability)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, backgroundPage.importantForAccessibility)
        assertFalse("A modal must not refocus a background tile", tile.requestFocus())
        repeat(4) {
            glow().performClick()
            layout()
            assertEquals("Glow must not resize the background preview", originalPreviewHeight,
                scope.previewHandle!!.cardView.height)
            assertEquals(originalY, background.scrollY)
            assertEquals(originalTop, tileTop())
        }
        assertTrue(host.floatingPanelBackHandler!!.invoke())
        assertNotEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, backgroundPage.descendantFocusability)
        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, backgroundPage.importantForAccessibility)
    }

    @Test fun hexEditorValidatesInputAndPreservesAlphaOtherColorAndFavorites() {
        for (id in listOf(FloatingPanelId.TEXT_COLOR, FloatingPanelId.HIGHLIGHT_COLOR)) {
            open(id)
            val before = host.floatingStyle()
            action(R.string.ui_custom_color).performClick()
            layout()
            visible(panel()).single { it.getTag(R.id.interaction_anchor) == "floating.color.hex" }.performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            val input = all(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single()
            input.setText("#77A85350")
            dialog.findViewById<View>(android.R.id.button1).performClick()
            assertTrue(dialog.isShowing)
            assertEquals(before, host.floatingStyle())
            assertEquals(View.VISIBLE, all(dialog.window!!.decorView).single {
                it.getTag(R.id.interaction_anchor) == "floating.color.hex.error"
            }.visibility)
            input.setText("a85350")
            if (id == FloatingPanelId.HIGHLIGHT_COLOR) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(220))
                val decor = dialog.window!!.decorView
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
                captureView(decor, "hex-editor")
            }
            dialog.findViewById<View>(android.R.id.button1).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(250))
            val after = host.floatingStyle()
            if (id == FloatingPanelId.TEXT_COLOR) {
                assertEquals(Color.argb(173, 168, 83, 80), after.textColor)
                assertEquals(before.wordByWordHighlightColor, after.wordByWordHighlightColor)
            } else {
                assertEquals(Color.argb(91, 168, 83, 80), after.wordByWordHighlightColor)
                assertEquals(before.textColor, after.textColor)
            }
            assertTrue(FloatingColorSwatchStore.colors(host).isEmpty())
            assertNull(host.interactions.read("panel.color.hex"))
            layout()
            val hex = visible(panel()).filterIsInstance<TextView>().single {
                it.getTag(R.id.interaction_anchor) == "floating.color.hex"
            }
            assertEquals("#A85350", hex.text.toString())
            hex.performClick()
            val cancelDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            all(cancelDialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText("#112233")
            cancelDialog.findViewById<View>(android.R.id.button2).performClick()
            assertEquals(after, host.floatingStyle())
            assertNull(host.interactions.read("panel.color.hex"))
            controller?.close()
            controller = null
            org.robolectric.shadows.ShadowDialog.reset()
        }
    }

    @Test fun rapidPanelTransitionsAndDisabledAnimationsKeepTheActivePageUsable() {
        open(FloatingPanelId.HIGHLIGHT_COLOR)
        val bubble = panel()
        (bubble.parent as ViewGroup).removeView(bubble)
        val frame = android.widget.FrameLayout(host).apply {
            isFocusableInTouchMode = true
            addView(bubble)
        }
        controller!!.get().setContentView(frame)
        controller!!.visible()
        frame.requestFocus()
        fun settle() {
            frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, width, height)
            frame.viewTreeObserver.dispatchOnPreDraw()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(220))
        }
        settle()
        action(R.string.ui_custom_color).performClick()
        frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, width, height)
        frame.viewTreeObserver.dispatchOnPreDraw()
        captureView(frame, "transition-start")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(80))
        captureView(frame, "transition-middle")
        settle()
        captureView(frame, "transition-end")
        host.floatingPanelBackHandler!!.invoke()
        settle()
        repeat(3) {
            action(R.string.ui_custom_color).performClick()
            frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, width, height)
            frame.viewTreeObserver.dispatchOnPreDraw()
            val outgoing = all(bubble).single { it.getTag(R.id.interaction_anchor) == "floating.color.editor" }
            host.floatingPanelBackHandler!!.invoke()
            val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
            try { assertFalse(outgoing.dispatchTouchEvent(event)) } finally { event.recycle() }
            settle()
            assertEquals(FloatingPanelId.HIGHLIGHT_COLOR, host.interactions.panel)
            assertTrue(action(R.string.ui_custom_color).isEnabled)
        }
        val scale = ReflectionHelpers.callStaticMethod<Float>(android.animation.ValueAnimator::class.java, "getDurationScale")
        try {
            ReflectionHelpers.callStaticMethod<Any?>(android.animation.ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, 0f))
            action(R.string.ui_custom_color).performClick()
            settle()
            assertEquals(3, visible(bubble).filterIsInstance<SeekBar>().size)
            action(R.string.ui_back).performClick()
            settle()
            assertTrue(visible(bubble).filterIsInstance<SeekBar>().isEmpty())
            glow().performClick()
            settle()
            assertEquals(2, visible(bubble).filterIsInstance<SeekBar>().size)
            glow().performClick()
            settle()
            assertTrue(visible(bubble).filterIsInstance<SeekBar>().isEmpty())
        } finally {
            ReflectionHelpers.callStaticMethod<Any?>(android.animation.ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, scale))
        }
    }

    private fun captureView(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(host.colorBackground); view.draw(this) }
        File("/tmp/airlyrics-color-panel-v4-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun capture(name: String) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(host.colorBackground)
            page.draw(this)
        }
        File("/tmp/airlyrics-color-panel-v3-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
