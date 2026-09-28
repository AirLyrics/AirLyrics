package com.andsi.airlyrics.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineTextScrollTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var host: MainUiHost
    private lateinit var grid: LinearLayout
    private var clicks = 0

    @After fun tearDown() {
        setAnimatorScale(1f)
        if (::controller.isInitialized) controller.close()
    }

    private fun setup(enabled: Boolean = true, title: String = "Control de visualización", summary: String = "Se requiere permiso de superposición") {
        ShadowChoreographer.setPaused(true)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        host = controller.get().graph.uiHost
        grid = host.settingGrid(
            FloatingSettingTile(title, summary, R.drawable.ic_air_visibility, enabled, { clicks++ }),
            FloatingSettingTile("Otra configuración muy larga", summary, R.drawable.ic_air_visibility, onClick = { clicks++ })
        )
        controller.get().setContentView(grid)
        controller.visible()
        advance(100)
        focusWindow(true)
        setAnimatorScale(1f)
        layout()
    }

    private fun layout() {
        grid.measure(View.MeasureSpec.makeMeasureSpec(host.dp(320), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
    }

    private fun texts(view: View): List<InlineOverflowTextView> = when (view) {
        is InlineOverflowTextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun focusWindow(focused: Boolean) {
        val root = ReflectionHelpers.callInstanceMethod<Any>(controller.get().window.decorView, "getViewRootImpl")
        Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(focused)
        advance(50)
    }

    @Test fun longPressActuallyScrollsInPlaceThenReadsSummaryOnceWithoutOpeningSettingsOrDialog() {
        setup(title = "Control de visualización de subtítulos")
        val tile = grid.getChildAt(0)
        val lines = texts(tile)
        val dimensions = tile.width to tile.height
        val before = snapshot()
        val dialogBefore = ShadowDialog.getLatestDialog()
        val downTime = SystemClock.uptimeMillis()
        fun touch(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 20f, 20f, 0)
            tile.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN)
        advance(ViewConfiguration.getLongPressTimeout().toLong() + 50)
        touch(MotionEvent.ACTION_UP)
        assertTrue(lines[0].isReading)
        advance(2000)
        assertTrue("reading=${lines[0].isReading}, offset=${lines[0].readingOffset}, summaryReading=${lines[1].isReading}, size=${tile.width}x${tile.height}", lines[0].readingOffset > 0)
        assertTrue("offset=${lines[0].readingOffset}, full=${lines[0].overflowLayout()!!.width}, width=${lines[0].width}", lines[0].readingOffset < lines[0].overflowLayout()!!.width - lines[0].width)
        assertFalse(lines[1].isReading)
        assertEquals(dimensions, tile.width to tile.height)
        assertEquals(0, clicks)
        assertSame(dialogBefore, ShadowDialog.getLatestDialog())
        val during = snapshot()
        assertFalse(before.sameAs(during))
        val directory = File("build/reports/long-text-previews").apply { mkdirs() }
        File(directory, "inline-before.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "inline-scrolling.png").outputStream().use { during.compress(Bitmap.CompressFormat.PNG, 100, it) }
        before.recycle()
        during.recycle()
        // Wait for the title to finish, then verify the overflowing summary also moves.
        repeat(60) { if (!lines[1].isReading) advance(500) }
        assertTrue(lines[1].isReading)
        advance(2000)
        assertTrue(lines[1].readingOffset > 0)
        repeat(20) { advance(1000) }
        assertTrue(lines.none { it.isReading })
        assertTrue(lines.all { it.isTextTruncated() })
        tile.performClick()
        assertEquals(1, clicks)
    }

    @Test fun anotherCardFocusLossTextChangesAndPageScrollCancelReading() {
        setup()
        val first = grid.getChildAt(0)
        val second = grid.getChildAt(1)
        val firstLines = texts(first)
        val secondLines = texts(second)
        first.performLongClick()
        assertTrue(firstLines[0].isReading)
        second.performLongClick()
        assertTrue(firstLines.none { it.isReading })
        assertTrue(secondLines[0].isReading)
        focusWindow(false)
        assertTrue(secondLines.none { it.isReading })
        focusWindow(true)
        first.performLongClick()
        firstLines[1].text = "Updated"
        assertTrue(firstLines.none { it.isReading })
        first.performLongClick()
        grid.scrollTo(0, 20)
        ReflectionHelpers.callInstanceMethod<Unit>(grid.viewTreeObserver, "dispatchOnScrollChanged")
        assertTrue(firstLines.none { it.isReading })
        first.performLongClick()
        grid.removeView(first)
        assertTrue(firstLines.none { it.isReading })
    }

    @Test fun disabledSettingsStillScrollAndShortTextDoesNotAnimate() {
        setup(enabled = false)
        val tile = grid.getChildAt(0)
        tile.performLongClick()
        assertTrue(texts(tile)[0].isReading)
        advance(1800)
        assertTrue(texts(tile)[0].readingOffset > 0)
        tile.performClick()
        assertEquals(0, clicks)
        texts(tile).forEach { it.text = "短文本" }
        tile.performLongClick()
        assertTrue(texts(tile).none { it.isReading })
    }

    @Test fun reducedMotionAndTalkBackUseStaticFullTextInstead() {
        setup()
        val tile = grid.getChildAt(0)
        val before = ShadowDialog.getLatestDialog()
        setAnimatorScale(0f)
        tile.performLongClick()
        assertTrue(texts(tile).none { it.isReading })
        assertNotSame(before, ShadowDialog.getLatestDialog())
        ShadowDialog.getLatestDialog().dismiss()
        setAnimatorScale(1f)
        val accessibility = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        // Robolectric exposes a public setter but a protected getter, so property syntax cannot compile.
        @Suppress("UsePropertyAccessSyntax")
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        val previous = ShadowDialog.getLatestDialog()
        tile.performLongClick()
        assertTrue(texts(tile).none { it.isReading })
        assertNotSame(previous, ShadowDialog.getLatestDialog())
    }

    @Test fun rtlScrollStartsAtReadingEdgeAndNeverResizesTheTile() {
        setup(title = "إعدادات عرض كلمات الأغاني الطويلة", summary = "قصير")
        grid.layoutDirection = View.LAYOUT_DIRECTION_RTL
        layout()
        val tile = grid.getChildAt(0)
        val title = texts(tile)[0]
        val size = tile.width to tile.height
        tile.performLongClick()
        val start = title.readingOffset
        assertTrue(start > 0)
        advance(2200)
        assertTrue(title.readingOffset < start)
        assertTrue(title.readingOffset >= 0)
        assertEquals(size, tile.width to tile.height)
    }

    private fun snapshot(): Bitmap = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(245, 246, 250))
        grid.draw(canvas)
    }

    private fun setAnimatorScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale", ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, scale))
    }
}
