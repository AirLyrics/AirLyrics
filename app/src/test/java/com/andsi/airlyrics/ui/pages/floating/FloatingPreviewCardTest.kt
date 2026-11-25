package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.layout.WindowLayoutSpec
import com.andsi.airlyrics.ui.state.findInteractionScroll
import com.andsi.airlyrics.ui.theme.colorTextMuted
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class FloatingPreviewCardTest {
    private var activityController: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun shortWindowCollapseRetainsStyledTextAndDoesNotChangeSavedExpansion() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
            .also { activityController = it }.get()
        val host = activity.graph.uiHost
        host.windowLayout = WindowLayoutSpec(640f, 300f, 1f)
        var writes = 0
        val full = android.text.SpannableString("Original\nTranslation").apply {
            setSpan(android.text.style.ForegroundColorSpan(Color.RED), 0, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val handle = host.createFloatingPreviewCard(
            isExpanded = { true }, setExpanded = { writes++ }, style = { host.floatingStyle() },
            isWordByWordLyricsEnabled = { false }, plainPreviewText = { full }, wordByWordPreviewText = { full }
        )
        fun measure() {
            handle.cardView.measure(
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
        measure()
        assertFalse(handle.isExpanded())
        assertEquals(View.GONE, handle.bodyView.visibility)
        val toggle = (handle.cardView as LinearLayout).getChildAt(1)
        toggle.performClick()
        measure()
        assertEquals(View.VISIBLE, handle.bodyView.visibility)
        assertEquals(full.toString(), handle.lyricTextView.text.toString())
        assertEquals(1, (handle.lyricTextView.text as android.text.Spanned).getSpans(0, 8, android.text.style.ForegroundColorSpan::class.java).size)
        toggle.performClick()
        handle.updateText("Updated\nNew translation")
        measure()
        assertEquals(View.GONE, handle.bodyView.visibility)
        host.windowLayout = host.windowLayout.copy(heightDp = 700f)
        measure()
        assertEquals(View.VISIBLE, handle.bodyView.visibility)
        assertEquals("Updated\nNew translation", handle.lyricTextView.text.toString())
        assertEquals(0, writes)
    }

    @Test
    fun toggleUsesAppChromeColorWhenPreviewTextIsWhiteInLightTheme() {
        val app = RuntimeEnvironment.getApplication()
        ThemeSettingsStore.setDark(app, false)
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
        val host = activity.graph.uiHost
        val previewStyle = host.floatingStyle().copy(textColor = Color.WHITE)

        val handle = host.createFloatingPreviewCard(
            isExpanded = { true },
            setExpanded = {},
            style = { previewStyle },
            isWordByWordLyricsEnabled = { false },
            plainPreviewText = { "Preview" },
            wordByWordPreviewText = { "Preview" }
        )
        val toggle = (handle.cardView as LinearLayout).getChildAt(1) as ImageButton

        assertEquals(Color.WHITE, handle.lyricTextView.currentTextColor)
        assertEquals(host.colorTextMuted, toggle.imageTintList?.defaultColor)
        assertNotEquals(previewStyle.textColor, toggle.imageTintList?.defaultColor)
    }

    @Test
    fun expandedPreviewKeepsTranslationVisibleAfterMultilineOriginal() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
            .also { activityController = it }.get()
        val host = activity.graph.uiHost
        host.windowLayout = WindowLayoutSpec(640f, 700f, 1f)
        val full = "これは歌詞プレビューの一行\nです\nThis is a lyric preview"
        val handle = host.createFloatingPreviewCard(
            isExpanded = { true },
            setExpanded = {},
            style = { host.floatingStyle() },
            isWordByWordLyricsEnabled = { false },
            plainPreviewText = { full },
            wordByWordPreviewText = { full }
        )
        val width = View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        handle.cardView.measure(width, height)
        handle.cardView.layout(0, 0, 180, handle.cardView.measuredHeight)

        val textLayout = checkNotNull(handle.lyricTextView.layout)
        val translationLine = textLayout.getLineForOffset(full.indexOf("This is"))
        assertTrue(
            "translationLine=$translationLine lineCount=${textLayout.lineCount} " +
                "viewWidth=${handle.lyricTextView.width} layoutWidth=${textLayout.width} " +
                "text=${handle.lyricTextView.text}",
            translationLine >= 2
        )
        assertEquals(0, textLayout.getEllipsisCount(translationLine))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun foldRetargetsFromTheVisibleFrameAndPreservesTheSettingsViewport() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            for ((locale, size) in listOf("en-rUS" to (390 to 720), "zh-rCN" to (320 to 420))) {
                val (width, height) = size
                RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h${height}dp-mdpi")
                ThemeSettingsStore.setDark(RuntimeEnvironment.getApplication(), locale == "en-rUS")
                Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                    val activity = controller.get()
                    val host = activity.graph.uiHost
                    host.windowLayout = WindowLayoutSpec(width.toFloat(), height.toFloat(), 1f)
                    host.setFloatingPreviewExpanded(false)
                    host.setLyricsSwitchAnimationMode(LyricsSwitchAnimationMode.NONE)
                    host.setWordByWordLyricsEnabled(false)
                    val scope = FloatingPageScope(host)
                    val page = scope.createView()
                    activity.setContentView(page)

                    fun layoutPage() {
                        page.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                        )
                        page.layout(0, 0, width, height)
                        page.viewTreeObserver.dispatchOnGlobalLayout()
                    }

                    fun advanceFrames(count: Int) {
                        repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
                        layoutPage()
                    }

                    layoutPage()
                    advanceFrames(20)
                    val preview = requireNotNull(scope.previewHandle)
                    val toggle = (preview.cardView as LinearLayout).getChildAt(1) as ImageButton
                    // performClick does not enter touch mode the way tapping the screen does.
                    toggle.isFocusable = false
                    val settings = page.findInteractionScroll() as ScrollView
                    val content = settings.getChildAt(0)
                    fun contentTop() = content.y - settings.scrollY
                    settings.scrollTo(0, host.dp(400))
                    val scrollY = settings.scrollY
                    val collapsedTop = contentTop()

                    toggle.performClick()
                    layoutPage()
                    assertEquals(locale, collapsedTop, contentTop(), 0.01f)
                    advanceFrames(4)
                    val openingFraction = preview.expansionFraction()
                    assertTrue("Opening fraction $openingFraction", openingFraction in 0.01f..0.99f)
                    assertTrue(contentTop() > collapsedTop)
                    assertEquals(scrollY, settings.scrollY)

                    val openingTop = contentTop()
                    val openingAngle = toggle.rotation
                    toggle.performClick()
                    assertEquals(openingFraction, preview.expansionFraction(), 0.001f)
                    assertEquals(openingTop, contentTop(), 0.01f)
                    assertEquals(openingAngle, toggle.rotation, 0.01f)
                    advanceFrames(2)
                    val closingFraction = preview.expansionFraction()
                    assertTrue(closingFraction < openingFraction)

                    toggle.performClick()
                    assertEquals(closingFraction, preview.expansionFraction(), 0.001f)
                    advanceFrames(20)
                    assertEquals(1f, preview.expansionFraction())
                    assertEquals(0f, content.translationY, 0.01f)
                    assertEquals(scrollY, settings.scrollY)

                    settings.scrollTo(0, content.height)
                    fun contentBottom() = contentTop() + content.height
                    assertEquals(settings.height.toFloat(), contentBottom(), 0.01f)
                    toggle.performClick()
                    advanceFrames(4)
                    assertTrue(preview.expansionFraction() in 0.01f..0.99f)
                    assertEquals(settings.height.toFloat(), contentBottom(), 0.01f)
                    advanceFrames(20)
                    assertEquals(View.GONE, preview.bodyView.visibility)
                    assertEquals(0, settings.paddingTop)
                    assertEquals(settings.height.toFloat(), contentBottom(), 0.01f)

                    withoutAnimations {
                        toggle.performClick()
                        layoutPage()
                        assertEquals(1f, preview.expansionFraction())
                        assertEquals(-90f, toggle.rotation, 0.01f)
                        assertEquals(0f, content.translationY, 0.01f)
                    }
                }
            }
        } finally {
            ShadowChoreographer.setPaused(false)
        }
    }

    private fun withoutAnimations(block: () -> Unit) {
        val scale = ValueAnimator.getDurationScale()
        fun setDurationScale(value: Float) {
            ReflectionHelpers.callStaticMethod<Any?>(
                ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, value)
            )
        }
        try {
            setDurationScale(0f)
            block()
        } finally {
            setDurationScale(scale)
        }
    }
}
