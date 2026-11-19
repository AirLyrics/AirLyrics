package com.andsi.airlyrics.ui.components

import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.pages.floating.FloatingPageScope
import com.andsi.airlyrics.ui.state.FloatingPanelId
import com.andsi.airlyrics.ui.theme.colorBackground
import java.io.File
import org.robolectric.Shadows.shadowOf
import android.graphics.Rect
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.ui.layout.WindowLayoutSpec
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import android.content.Context
import android.view.accessibility.AccessibilityManager

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactSettingsLayoutTest {
    private var controller: ActivityController<MainActivity>? = null
    @After fun tearDown() {
        controller?.close()
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("en-rUS-w320dp-h470dp-mdpi")
    }
    private fun host(): MainUiHost = Robolectric.buildActivity(MainActivity::class.java).setup()
        .also { controller = it }.get().graph.uiHost
    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun tile(title: String, summary: String = "100%", enabled: Boolean = true, onClick: (View) -> Unit = {}) =
        FloatingSettingTile(title, summary, R.drawable.ic_air_visibility, enabled, onClick)

    @Test fun allGroupsKeepTwoEqualColumnsAndOddItemsNeverStretch() {
        val host = host()
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            for (width in listOf(320, 360, 600)) {
                val short = host.settingGrid(tile("Font"), tile("Color"), tile("Width")) as AdaptiveGridLayout
                val long = host.settingGrid(tile("Opacidad de la fuente ".repeat(5)), tile("文字颜色", "R255 G255 B255 A255"), tile("Diseño de ventana")) as AdaptiveGridLayout
                for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                    short.layoutDirection = direction
                    long.layoutDirection = direction
                    layout(short, host.dp(width - 40))
                    layout(long, host.dp(width - 40))
                    assertEquals(2, short.columns)
                    assertEquals(2, long.columns)
                    assertEquals(short.height, long.height)
                    assertEquals(1, (0 until long.childCount).map { long.getChildAt(it).height }.distinct().size)
                    val last = long.getChildAt(2) as LinearLayout
                    assertEquals(long.getChildAt(0).width, last.width)
                    assertEquals(LinearLayout.VERTICAL, last.orientation)
                    assertEquals(long.getChildAt(0).left, last.left)
                    assertTrue(descendants(long).filterIsInstance<TextView>().all { it.maxLines == 1 })
                    if (long.columns == 2) {
                        assertEquals(long.getChildAt(0).height, long.getChildAt(1).height)
                        assertEquals(LinearLayout.VERTICAL, (long.getChildAt(0) as LinearLayout).orientation)
                    }
                }
            }
        }
    }

    @Test fun singleItemGroupsAndLiveSummariesKeepTheSameTileGeometry() {
        val host = host()
        lateinit var summary: TextView
        val single = host.settingGrid(tile("Color").copy(onSubtitleViewCreated = { summary = it }))
        val triple = host.settingGrid(tile("控制显示"), tile("Ocultar automáticamente"), tile("Display scope"))
        layout(single, host.dp(320))
        layout(triple, host.dp(320))
        assertEquals(triple.getChildAt(0).width, single.getChildAt(0).width)
        assertEquals(triple.getChildAt(0).height, single.getChildAt(0).height)
        val height = single.height
        summary.text = "Se requiere permiso de superposición · Arrastrable · Tocable".repeat(6)
        layout(single, host.dp(320))
        assertEquals(height, single.height)
        assertTrue(summary.isTextTruncated())
    }

    @Test fun headerActionsKeepTouchTargetsAndPositionAcrossTitleAndUndoChanges() {
        RuntimeEnvironment.setQualifiers("es-w320dp-h640dp-mdpi")
        val host = host()
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            for (width in listOf(240, 320, 360)) {
                val header = SettingsPanelHeader(host, "Opacidad de la fuente de los subtítulos", "", {}, {})
                layout(header, host.dp(width))
                val undo = descendants(header).filterIsInstance<SettingsPanelUndoButton>().single()
                val slot = undo.parent as View
                val close = header.getChildAt(2)
                val stableBounds = listOf(header.getChildAt(0), slot, close).associateWith {
                    Rect(it.left, it.top, it.right, it.bottom)
                }
                assertFalse(undo.isEnabled)
                assertTrue(undo.width >= host.dp(48))
                assertEquals(host.dp(48), close.width)
                val height = header.height
                val description = "Restaurar la tipografía personalizada para los subtítulos"
                header.updateUndoAction(SettingsUndoAction(description,
                    SettingsUndoTarget("Tipografía", "Una tipografía personalizada para los subtítulos")))
                layout(header, host.dp(width))
                assertTrue(undo.isEnabled)
                assertEquals(description, undo.contentDescription)
                assertEquals(close.left, slot.left + undo.right)
                assertEquals(height, header.height)
                val longTarget = descendants(undo).filterIsInstance<TextView>()
                    .single { it.text.toString() == "Una tipografía personalizada para los subtítulos" }
                assertEquals(View.GONE, longTarget.visibility)
                stableBounds.forEach { (view, rect) ->
                    assertEquals(rect, Rect(view.left, view.top, view.right, view.bottom))
                    assertTrue(rect.left >= 0 && rect.right <= header.width)
                }
            }
        }
    }

    @Test fun swatchesHaveExplicitGapsAndUseWidthDrivenColumns() {
        val host = host()
        val colors = host.colorControl("Texto", Color.WHITE, false) {}
        layout(colors, host.dp(320))
        val grid = descendants(colors).filterIsInstance<AdaptiveGridLayout>().single()
        assertEquals(3, grid.columns)
        val preview = colors.getChildAt(0)
        assertEquals(host.dp(AirUiTokens.Layout.SettingGap), grid.top - preview.bottom)
        val height = grid.height
        (grid.getChildAt(0) as TextView).text = "A very long localized color name"
        layout(colors, host.dp(320))
        assertEquals(3, grid.columns)
        assertTrue(grid.height >= height)
        assertEquals(grid.getChildAt(0).height, grid.getChildAt(1).height)
    }

    @Test fun readerIsStaticByDefaultAndStopsOnSourceChangeFocusLossAndDetach() {
        val host = host()
        controller!!.visible()
        val source = TextView(host).apply { text = "Long filename / ".repeat(40) }
        val first = FullTextReader(host, source.text.toString(), source, allowScroll = true)
        val second = FullTextReader(host, source.text.toString(), allowScroll = true)
        val dialog = host.showAirDialog(title = null, body = { addView(first); addView(second) })
        shadowOf(Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        focusWindow(decor, true)
        layout(first, host.dp(280))
        layout(second, host.dp(280))
        assertFalse(first.reading)
        first.startReading()
        assertTrue("attached=${first.isAttachedToWindow}, focus=${first.hasWindowFocus()}, motion=${first.motionAllowed()}", first.reading)
        second.startReading()
        assertFalse(first.reading)
        assertTrue(second.reading)
        focusWindow(decor, false)
        assertFalse(second.reading)
        focusWindow(decor, true)
        first.startReading()
        source.text = "Updated filename"
        assertFalse(first.reading)
        assertTrue(descendants(first).filterIsInstance<TextView>().count { it.text.toString() == source.text.toString() } == 2)
        source.text = "Long filename / ".repeat(8)
        layout(first, host.dp(280))
        val staticHeight = first.height
        first.startReading()
        layout(first, host.dp(280))
        assertEquals(staticHeight, first.height)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertTrue(first.reading)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(120))
        assertFalse(first.reading)
        first.startReading()
        (first.parent as ViewGroup).removeView(first)
        assertFalse(first.reading)
        val accessibility = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        // Robolectric exposes a public setter but a protected getter, so property syntax cannot compile.
        @Suppress("UsePropertyAccessSyntax")
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        second.startReading()
        assertFalse(second.reading)
        dialog.dismiss()
    }

    @Test fun localizedSettingsKeepBoundSubtitlesReadableAndClickableAtLargeFontScale() {
        val titleIds = listOf(R.string.ui_font, R.string.ui_text_color, R.string.ui_font_size,
            R.string.ui_font_opacity, R.string.ui_display_control, R.string.ui_auto_hide, R.string.ui_display_scope)
        for (qualifier in listOf("en-rUS", "es", "zh-rCN", "zh-rTW")) {
            RuntimeEnvironment.setQualifiers("$qualifier-w360dp-h800dp-mdpi")
            RuntimeEnvironment.setFontScale(2f)
            val host = host()
            try {
                val subtitles = mutableListOf<TextView>()
                val clicked = mutableListOf<Int>()
                val grid = host.settingGrid(*titleIds.mapIndexed { index, title ->
                    tile(host.getString(title), "100%", onClick = { clicked += index })
                        .copy(onSubtitleViewCreated = subtitles::add)
                }.toTypedArray()) as AdaptiveGridLayout
                layout(grid, host.dp(320))
                assertEquals(qualifier, 2, grid.columns)
                assertEquals(titleIds.size, subtitles.size)
                for (index in titleIds.indices) {
                    val tile = grid.getChildAt(index) as ViewGroup
                    val subtitle = subtitles[index]
                    assertTrue(tile.measuredHeight >= host.dp(AirUiTokens.Layout.FloatingTileMinHeight))
                    assertTrue(tile.left >= 0 && tile.right <= grid.width)
                    assertTrue(subtitle.height >= subtitle.layout.height + subtitle.compoundPaddingTop + subtitle.compoundPaddingBottom)
                    val bounds = Rect()
                    subtitle.getDrawingRect(bounds)
                    tile.offsetDescendantRectToMyCoords(subtitle, bounds)
                    assertTrue("$qualifier subtitle is clipped by tile", bounds.bottom <= tile.height - tile.paddingBottom)
                    assertTrue(descendants(tile).filterIsInstance<TextView>().any { it.text.toString() == host.getString(titleIds[index]) })
                    tile.performClick()
                }
                assertEquals(titleIds.indices.toList(), clicked)
                val tiles = (0 until grid.childCount).map(grid::getChildAt)
                tiles.chunked(2).forEach { row -> assertEquals(1, row.map { it.height }.distinct().size) }
                assertEquals(tiles.first().width, tiles.last().width)
                subtitles.first().text = "Updated summary"
                layout(grid, host.dp(320))
                assertTrue(descendants(tiles.first()).filterIsInstance<TextView>().any { it.text.toString() == "Updated summary" })
            } finally {
                controller?.close()
                controller = null
            }
        }
    }

    @Test fun floatingPanelActionsStayVisibleWhenLongContentScrolls() {
        RuntimeEnvironment.setQualifiers("es-w320dp-h470dp-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        val host = host()
        val scope = FloatingPageScope(host)
        val page = scope.createView()
        layoutPage(page, 320, 470)
        scope.restoringPanel = true
        scope.panelOpeners.getValue(FloatingPanelId.SUMMARY).invoke()
        layoutPage(page, 320, 470)
        val panel = requireNotNull(scope.activeBubble)
        val header = descendants(panel).filterIsInstance<SettingsPanelHeader>().single()
        val scroll = descendants(panel).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.height > 0)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        val top = header.top
        scroll.fullScroll(View.FOCUS_DOWN)
        layoutPage(page, 320, 470)
        assertTrue(scroll.scrollY > 0)
        assertEquals(top, header.top)
        assertTrue(header.bottom <= scroll.top)
        val close = descendants(header).single { it.contentDescription == host.getString(R.string.ui_close) }
        assertEquals(host.dp(48), close.width)
        assertTrue(close.bottom <= header.height)
        capturePanel(page, "short-screen-summary")
        close.performClick()
        assertNull(host.interactions.panel)
    }

    @Test fun translucentColorsKeepPresetSelectionAndOpacityWhenChangingHue() {
        val host = host()
        val opacity = 90
        var changedColor = Color.TRANSPARENT
        val colors = host.colorControl("Color", Color.argb(opacity, 66, 165, 245), true) { changedColor = it }
        layout(colors, host.dp(280))
        val grid = descendants(colors).filterIsInstance<AdaptiveGridLayout>().single()
        fun swatch(label: Int) = descendants(grid).filterIsInstance<TextView>()
            .single { it.text.toString() == host.getString(label) }
        assertTrue(swatch(R.string.ui_blue).isSelected)
        swatch(R.string.ui_red).performClick()
        assertEquals(opacity, Color.alpha(changedColor))
        assertTrue(swatch(R.string.ui_red).isSelected)
        assertFalse(swatch(R.string.ui_blue).isSelected)
        swatch(R.string.ui_custom).performClick()
        layout(colors, host.dp(280))
        val sliders = descendants(colors).filterIsInstance<SettingsSliderRow>()
        assertEquals(4, sliders.size)
        assertTrue(sliders.all { it.parent.let { parent -> (parent as View).visibility == View.VISIBLE } })
        assertTrue(sliders.all { it.seekBar.height >= host.dp(48) })
    }

    @Test fun floatingSubSettingsRenderWithReadableControlsAcrossThemesAndTextSizes() {
        val app = RuntimeEnvironment.getApplication()
        try {
            for ((dark, locale, scale) in listOf(Triple(true, "zh-rCN", 1f), Triple(false, "es", 2f))) {
                RuntimeEnvironment.setQualifiers("$locale-w360dp-h680dp-mdpi")
                RuntimeEnvironment.setFontScale(scale)
                ThemeSettingsStore.setDark(app, dark)
                val host = host()
                try {
                    for (id in listOf(FloatingPanelId.TEXT_COLOR, FloatingPanelId.WINDOW_LAYOUT, FloatingPanelId.CONTENT, FloatingPanelId.AUTO_HIDE)) {
                        host.interactions.panel = null
                        val scope = FloatingPageScope(host)
                        val page = scope.createView()
                        layoutPage(page, 360, 680)
                        scope.restoringPanel = true
                        scope.panelOpeners.getValue(id).invoke()
                        layoutPage(page, 360, 680)
                        val panel = requireNotNull(scope.activeBubble)
                        assertTrue(panel.width <= host.dp(360))
                        assertTrue(panel.height <= host.dp(680))
                        descendants(panel).filterIsInstance<SettingsSliderRow>().forEach { row ->
                            val label = descendants(row).filterIsInstance<TextView>().single { it !== row.valueView }
                            val labelBounds = Rect(0, 0, label.width, label.height)
                            val valueBounds = Rect(0, 0, row.valueView.width, row.valueView.height)
                            row.offsetDescendantRectToMyCoords(label, labelBounds)
                            row.offsetDescendantRectToMyCoords(row.valueView, valueBounds)
                            assertTrue(labelBounds.bottom <= valueBounds.top || labelBounds.right <= valueBounds.left)
                            assertTrue(valueBounds.right <= row.width)
                        }
                        capturePanel(page, "$locale-${id.name.lowercase()}")
                    }
                } finally {
                    controller?.close()
                    controller = null
                }
            }
        } finally {
            app.getSharedPreferences("app_theme", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun floatingPreviewOverlaysSettingsAndFoldingKeepsScrollPosition() {
        for ((locale, size) in listOf("en-rUS" to (390 to 720), "zh-rCN" to (320 to 420))) {
            val (width, height) = size
            RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h${height}dp-mdpi")
            val app = RuntimeEnvironment.getApplication()
            ThemeSettingsStore.setDark(app, locale == "en-rUS")
            val host = host()
            host.windowLayout = WindowLayoutSpec(width.toFloat(), height.toFloat(), 1f)
            try {
                host.setFloatingPreviewExpanded(true)
                val scope = FloatingPageScope(host)
                val page = scope.createView() as ViewGroup
                layoutPage(page, width, height)
                val preview = requireNotNull(scope.previewHandle)
                if (host.windowLayout.compact) {
                    descendants(preview.cardView).single {
                        it.contentDescription == host.getString(R.string.ui_expand_preview)
                    }.performClick()
                    layoutPage(page, width, height)
                }
                val settings = floatingSettingsScroll(page)
                fun boundsOf(view: View) = Rect().apply {
                    view.getDrawingRect(this)
                    page.offsetDescendantRectToMyCoords(view, this)
                }

                val initialBounds = boundsOf(preview.cardView)
                val expandedInset = settings.paddingTop
                assertEquals(0, boundsOf(settings).top)
                assertTrue(initialBounds.top >= 0 && initialBounds.bottom <= expandedInset)
                settings.scrollTo(0, host.dp(400))
                val scrollY = settings.scrollY
                assertTrue(scrollY > 0)
                assertEquals(locale, initialBounds, boundsOf(preview.cardView))
                val lyric = preview.lyricTextView
                assertTrue(lyric.text.contains(host.getString(R.string.ui_this_is_a_lyric_preview_translation)))
                assertTrue(lyric.layout.height + lyric.compoundPaddingTop + lyric.compoundPaddingBottom <= lyric.height)

                val toggle = descendants(preview.cardView).single {
                    it.contentDescription == host.getString(R.string.ui_collapse_preview)
                }
                val toggleBounds = boundsOf(toggle)
                toggle.performClick()
                layoutPage(page, width, height)
                assertEquals(View.GONE, preview.bodyView.visibility)
                assertEquals(0, settings.paddingTop)
                assertEquals(0, settings.getChildAt(0).top)
                assertEquals(scrollY, settings.scrollY)
                assertEquals(toggleBounds, boundsOf(toggle))
                assertEquals(host.dp(48), toggle.width)
                assertEquals(host.getString(R.string.ui_expand_preview), toggle.contentDescription)

                toggle.performClick()
                layoutPage(page, width, height)
                assertEquals(expandedInset, settings.paddingTop)
                assertEquals(scrollY, settings.scrollY)
            } finally {
                controller?.close()
                controller = null
                app.getSharedPreferences("app_theme", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    @Test fun tallFloatingPreviewLeavesRoomForSettingsAndItsToggle() {
        RuntimeEnvironment.setQualifiers("zh-rCN-w320dp-h320dp-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        val host = host()
        host.windowLayout = WindowLayoutSpec(320f, 320f, 2f)
        host.setLyricsLineDisplayMode(LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT)
        FloatingLyricsStyleStore.setTextSize(host, 56f)
        val scope = FloatingPageScope(host)
        val page = scope.createView() as ViewGroup
        layoutPage(page, 320, 320)
        val preview = requireNotNull(scope.previewHandle)
        val toggle = descendants(preview.cardView).single {
            it.contentDescription == host.getString(R.string.ui_expand_preview)
        }

        toggle.performClick()
        layoutPage(page, 320, 320)
        val settings = floatingSettingsScroll(page)
        val previewScroll = preview.bodyView as ScrollView
        assertTrue(previewScroll.canScrollVertically(1))
        assertEquals(host.dp(48), toggle.height)
        assertTrue(toggle.bottom <= preview.cardView.height)
        assertTrue(settings.height - settings.paddingTop >= host.dp(120))

        previewScroll.scrollTo(0, preview.lyricTextView.height)
        assertTrue(previewScroll.scrollY > 0)
        assertEquals(0, settings.scrollY)
        toggle.performClick()
        layoutPage(page, 320, 320)
        assertEquals(0, settings.paddingTop)
    }

    private fun floatingSettingsScroll(page: View): ScrollView =
        descendants(page).filterIsInstance<ScrollView>().single { scroll ->
            descendants(scroll).any { it.getTag(R.id.interaction_anchor) == "floating.settings" }
        }

    private fun layoutPage(view: View, width: Int, height: Int) {
        // Detached renders need an explicit direction to resolve relative icon drawables.
        descendants(view).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_LTR }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun capturePanel(page: View, name: String) {
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor((page.context as MainUiHost).colorBackground)
            page.draw(this)
        }
        File("/tmp", "airlyrics-floating-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun focusWindow(decor: View, focused: Boolean) {
        val root = ReflectionHelpers.callInstanceMethod<Any>(decor, "getViewRootImpl")
        Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(focused)
        shadowOf(Looper.getMainLooper()).idle()
    }

}
