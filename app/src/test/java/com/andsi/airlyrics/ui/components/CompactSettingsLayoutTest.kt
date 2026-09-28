package com.andsi.airlyrics.ui.components

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.design.tokens.AirUiTokens
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
import java.io.File
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
        val host = host()
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            for (width in listOf(240, 320, 360)) {
                val short = SettingsPanelHeader(host, "Color", "", {}, {})
                val long = SettingsPanelHeader(host, "Opacidad de la fuente de los subtítulos", "", {}, {})
                layout(short, host.dp(width))
                layout(long, host.dp(width))
                for (index in 1..2) {
                    assertEquals(short.getChildAt(index).left, long.getChildAt(index).left)
                    assertEquals(0, long.getChildAt(index).top)
                    assertEquals(host.dp(48), long.getChildAt(index).width)
                }
                val left = long.getChildAt(1).left
                long.updateResetAction(enabled = true, isUndo = true)
                layout(long, host.dp(width))
                assertEquals(left, long.getChildAt(1).left)
                assertEquals(host.getString(R.string.ui_undo), long.getChildAt(1).contentDescription)
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

    @Test fun renderLocalizedTwoColumnSettingsPreviews() {
        val host = host()
        val output = File("build/reports/long-text-previews").apply { mkdirs() }
        for (locale in listOf("en", "es", "zh")) {
            val spanish = locale == "es"
            val chinese = locale == "zh"
            RuntimeEnvironment.setQualifiers("${if (chinese) "zh-rCN" else locale}-w360dp-h800dp-mdpi")
            val titles = if (chinese) listOf("预设", "文字颜色", "字体大小", "字体", "字体不透明度", "阴影描边", "窗口布局")
                else if (spanish) listOf("Preajuste", "Color del texto", "Tamaño de fuente", "Fuente", "Opacidad de la fuente", "Trazo de sombra", "Diseño de ventana")
                else listOf("Preset", "Text color", "Font size", "Font", "Font opacity", "Shadow stroke", "Window layout")
            val summaries = listOf("Vinyl", "R255 G255 B255 A255", "18 sp", "System default", "100%", "8", "85%")
            val page = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(20), host.dp(20), host.dp(20), host.dp(20))
                addView(host.settingGrid(*titles.mapIndexed { index, title -> tile(title, summaries[index]) }.toTypedArray()))
                addView(host.settingGrid(
                    tile(if (chinese) "显示控制" else if (spanish) "Control de visualización" else "Display control", if (chinese) "需要悬浮窗权限 · 可拖动 · 可点击" else if (spanish) "Se requiere permiso de superposición · Arrastrable · Tocable" else "Overlay permission required · Draggable · Touchable"),
                    tile(if (chinese) "自动隐藏" else if (spanish) "Ocultar automáticamente" else "Auto hide", if (chinese) "关闭" else if (spanish) "Desactivado" else "Off"),
                    tile(if (chinese) "显示范围" else if (spanish) "Alcance de visualización" else "Display scope", if (chinese) "关闭" else if (spanish) "Desactivado" else "Off")))
            }
            render(page, host.dp(360), File(output, "settings-$locale.png"))
            for (color in listOf(false, true)) {
                val title = if (color) titles[1] else titles[4]
                val panel = host.floatingFocusBubble(title, "", {}, {}) {
                    if (color) addView(host.colorControl(if (chinese) "文字" else if (spanish) "Texto" else "Text", Color.WHITE, false) {})
                    else addView(host.sliderRow(title, 100, 0, 100, "%") {})
                }
                panel.updateResetAction(true, false)
                val parent = FrameLayout(host).apply { addView(panel.view) }
                render(parent, host.dp(360), File(output, "panel-$locale-${if (color) "color" else "opacity"}.png"))
            }
        }
    }

    private fun focusWindow(decor: View, focused: Boolean) {
        val root = ReflectionHelpers.callInstanceMethod<Any>(decor, "getViewRootImpl")
        Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(focused)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun render(view: View, width: Int, output: File) {
        layout(view, width)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(245, 246, 250))
        view.draw(canvas)
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
