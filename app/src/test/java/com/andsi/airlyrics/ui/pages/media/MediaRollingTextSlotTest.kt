package com.andsi.airlyrics.ui.pages.media

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.model.MainUiHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaRollingTextSlotTest {
    @Before fun pauseFrames() = ShadowChoreographer.setPaused(true)
    @After fun resetEnvironment() {
        ShadowChoreographer.setPaused(false)
        RuntimeEnvironment.setFontScale(1f)
        LanguageSettingsStore.setMode(RuntimeEnvironment.getApplication(), LanguageSettingsStore.MODE_SYSTEM)
    }

    @Test fun changingLineCountNeverSuperimposesOldAndNewInk() {
        for ((old, next) in listOf("👩‍🎤\n👩‍🎤" to "Go", "Go" to "👩‍🎤\n👩‍🎤")) {
            withSlot(old) { slot, text ->
                text.colorsByText[next] = listOf(Color.BLUE)
                slot.show(next)
                val animation = running(slot)
                for (percent in 0..99 step 3) {
                    animation.currentPlayTime = animation.duration * percent / 100
                    layout(slot)
                    val pixels = pixels(slot)
                    assertTrue("No ink at $percent% for $old → $next", pixels.any { Color.alpha(it) > 32 })
                    assertFalse("Old and new ink overlap at $percent% for $old → $next",
                        pixels.any { Color.red(it) > 24 && Color.blue(it) > 24 })
                }
                animation.end()
                layout(slot)
                assertEquals(1f, text.alpha)
                assertTrue(pixels(slot).any { Color.blue(it) > 24 })
                assertFalse(pixels(slot).any { Color.red(it) > 24 })
            }
        }
    }

    @Test fun secondLineNeverLeaksIntoTheFirstLineWindow() {
        withSlot("A\nA", listOf(Color.RED, Color.GREEN)) { slot, text ->
            val firstLineBottom = text.compoundPaddingTop + text.layout.getLineBottom(0)
            text.colorsByText["B\nB"] = listOf(Color.BLUE)
            slot.show("B\nB")
            val animation = running(slot)
            for (percent in 0..99 step 3) {
                animation.currentPlayTime = animation.duration * percent / 100
                layout(slot)
                val firstLine = pixels(slot).take(slot.width * firstLineBottom)
                assertFalse("Second-line ink leaked into the first line at $percent%",
                    firstLine.any { Color.green(it) > 24 })
            }
        }
    }

    @Test fun differentCharacterGroupsNeverSuperimposeOldAndNewInk() {
        withSlot("ABCD") { slot, text ->
            text.colorsByText["Alphabetical words"] = listOf(Color.BLUE)
            slot.show("Alphabetical words")
            val animation = running(slot)
            for (percent in 0..99 step 3) {
                animation.currentPlayTime = animation.duration * percent / 100
                layout(slot)
                assertFalse("Different stagger boundaries overlap at $percent%",
                    pixels(slot).any { Color.red(it) > 24 && Color.blue(it) > 24 })
            }
        }
    }

    @Test fun rapidReplacementPreservesTheVisibleFrameAndSettlesToTheLatestText() {
        withSlot("👩‍🎤\n👩‍🎤") { slot, text ->
            text.colorsByText["Go"] = listOf(Color.BLUE)
            slot.show("Go")
            running(slot).currentPlayTime = 180
            layout(slot)
            val before = pixels(slot)
            val beforeHeight = slot.height

            text.colorsByText["Latest"] = listOf(Color.YELLOW)
            slot.show("Latest")
            layout(slot)
            assertEquals(beforeHeight, slot.height)
            assertTrue("Interrupting a swap changed the visible frame", before.contentEquals(pixels(slot)))
            running(slot).end()
            layout(slot)
            assertEquals("Latest", text.text.toString())
            assertEquals(1, slot.childCount)
            assertEquals(1f, text.alpha)
            assertEquals(text.height, slot.height)
        }
    }

    @Test fun renderLongToShortMediaCardsAtNormalNarrowAndLargeTypeSizes() {
        for (locale in listOf("en", "zh-CN")) for (widthDp in listOf(360, 280)) {
            for (fontScale in listOf(1f, 1.5f)) {
                RuntimeEnvironment.setQualifiers("${locale.replace("zh-CN", "zh-rCN")}-w${widthDp}dp-h720dp-mdpi")
                RuntimeEnvironment.setFontScale(fontScale)
                LanguageSettingsStore.setMode(RuntimeEnvironment.getApplication(), locale)
                Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                    val host = controller.get().graph.uiHost
                    assertEquals(locale.substringBefore('-'), host.resources.configuration.locales[0].language)
                    val session = MediaSession(host, "rolling-preview")
                    try {
                        val media = MediaController(host, session.sessionToken)
                        shadowOf(media).setPackageName("media.preview")
                        val old = if (locale == "en") {
                            "A very long live recording title that wraps onto two lines and still needs an expansion button"
                        } else "【作業雜談】我陪你熬夜～你陪我加班💧沒有話題也可以一起聽音樂"
                        val next = if (locale == "en") "Home" else "晴天"
                        val state = host.mediaPageState().copy(
                            controllers = listOf(media), selectedController = media,
                            selectedPackage = "media.preview", selectedTitle = old,
                            selectedArtist = if (locale == "en") "Artist" else "歌手"
                        )
                        lateinit var binding: MediaPageBinding
                        val page = createMediaPage(host, animateContent = false, pageState = state,
                            onBound = { binding = it })
                        Robolectric.buildActivity(Activity::class.java).setup().visible().use { owner ->
                            owner.get().setContentView(FrameLayout(owner.get()).apply { addView(page) })
                            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                            fun measurePage() {
                                page.measure(
                                    View.MeasureSpec.makeMeasureSpec(host.dp(widthDp), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(host.dp(720), View.MeasureSpec.EXACTLY)
                                )
                                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                            }
                            measurePage()
                            val card = descendants(page).single { it.getTag(R.id.interaction_anchor) == "media.current" }
                            val title = descendants(card).filterIsInstance<MediaRollingTextSlot>()
                                .single { it.textView.text.toString() == old }
                            val artist = descendants(card).filterIsInstance<MediaRollingTextSlot>().single { it !== title }
                            assertEquals(2, title.textView.lineCount)
                            assertTrue(binding.update(state.copy(selectedTitle = next)))
                            val animation = running(title)
                            val cellWidth = host.dp(widthDp)
                            val cellHeight = host.dp(240)
                            val sheet = Bitmap.createBitmap(cellWidth * 4, cellHeight * 2, Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = Canvas(sheet)
                                canvas.drawColor(Color.rgb(24, 22, 26))
                                val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f }
                                listOf(0, 15, 30, 45, 60, 75, 90, 100).forEachIndexed { index, percent ->
                                    if (percent == 100) animation.end()
                                    else animation.currentPlayTime = animation.duration * percent / 100
                                    measurePage()
                                    assertTrue("Title overlaps artist at $percent%", title.bottom <= artist.top)
                                    assertTrue(card.width <= cellWidth)
                                    val x = (index % 4) * cellWidth
                                    val y = (index / 4) * cellHeight
                                    canvas.drawText("$percent%", x + 8f, y + 20f, caption)
                                    val save = canvas.save()
                                    canvas.translate(x.toFloat(), y + 32f)
                                    card.draw(canvas)
                                    canvas.restoreToCount(save)
                                }
                                assertEquals(1, title.textView.lineCount)
                                assertEquals(0, title.textView.layout.getEllipsisCount(0))
                                File(System.getProperty("java.io.tmpdir"),
                                    "airlyrics-media-roll-$locale-$widthDp-$fontScale.png").outputStream().use {
                                    sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            } finally {
                                sheet.recycle()
                                binding.finish()
                            }
                        }
                    } finally {
                        session.release()
                    }
                }
            }
        }
    }

    private fun withSlot(
        initial: String,
        colors: List<Int> = listOf(Color.RED),
        block: (MediaRollingTextSlot, InkTextView) -> Unit
    ) {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val host = controller.get().graph.uiHost
            val text = InkTextView(host).apply {
                this.text = initial
                textSize = 24f
                colorsByText[initial] = colors
            }
            val slot = MediaRollingTextSlot(host, text, MediaRollingRole.TITLE)
            Robolectric.buildActivity(Activity::class.java).setup().visible().use { owner ->
                val root = FrameLayout(owner.get()).apply { addView(slot) }
                owner.get().setContentView(root)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                layout(slot)
                assertTrue("Slot must be attached before testing transitions", slot.isAttachedToWindow)
                assertTrue("Positive control must draw old ink", pixels(slot).any { Color.red(it) > 24 })
                block(slot, text)
            }
        }
    }

    // Solid translucent ink makes old/new overlap observable in the output bitmap,
    // independent of font rasterization and without inspecting snapshot internals.
    private class InkTextView(host: MainUiHost) : TextView(host) {
        val colorsByText = mutableMapOf<String, List<Int>>()
        private val ink = Paint()
        override fun onDraw(canvas: Canvas) {
            val lines = layout ?: return
            val rowColors = colorsByText[text.toString()] ?: listOf(Color.RED)
            for (line in 0 until lines.lineCount.coerceAtMost(maxLines)) {
                ink.color = rowColors.getOrElse(line) { rowColors.last() }
                ink.alpha = 128
                canvas.drawRect(
                    0f, (compoundPaddingTop + lines.getLineTop(line) + 3).toFloat(),
                    width.toFloat(), (compoundPaddingTop + lines.getLineBottom(line) - 3).toFloat(), ink
                )
            }
        }
    }

    private fun running(slot: MediaRollingTextSlot): ValueAnimator =
        requireNotNull(ReflectionHelpers.getField<ValueAnimator?>(slot, "animator"))

    private fun layout(slot: MediaRollingTextSlot) {
        slot.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        slot.layout(0, 0, slot.measuredWidth, slot.measuredHeight)
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(view.width * view.height).also {
                bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
