package com.andsi.airlyrics.app

import com.andsi.airlyrics.testutil.seedLegacyWordLyrics
import com.andsi.airlyrics.testutil.seedPlainLyrics
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.LyricsStorageTestBase
import com.andsi.airlyrics.lyrics.storage.WordByWordLyricsStorageOps
import com.andsi.airlyrics.testutil.awaitUiCondition as await
import com.andsi.airlyrics.testutil.descendants
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalBulkLyricsEditorTest : LyricsStorageTestBase() {
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @After fun closeEditor() {
        if (::controller.isInitialized) controller.close()
        ShadowDialog.reset()
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    fun timedLrcSharesTheDraftAndKeepsInvalidTextOpen() {
        val original = "[00:01.00]Original\n[00:02.00]Second line"
        assertTrue(seedPlainLyrics(context, "Song", "Artist", 10000, original))
        openEditor()
        assertTrue(mode(R.string.ui_lyrics_mode_timeline).isChecked)
        capture("editor-stable-timeline-en-400")
        mode(R.string.ui_lyrics_mode_text).performClick()
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        val initialEditorHeight = textScroller().height
        val invalid = "[00:01.00]Edited\nMissing timestamp"
        editor().setText(invalid)
        val errorText = activity.getString(R.string.ui_lyrics_bulk_invalid_line, 2)
        val save = requireNotNull(textLabel(activity.getString(R.string.ui_save_changes)))
        val timeline = mode(R.string.ui_lyrics_mode_timeline)
        val wasPaused = ShadowChoreographer.isPaused()
        val previousDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            listOf(save, timeline, save, timeline).forEach { trigger ->
                trigger.performClick()
                assertTrue(editor().isEnabled)
                assertTrue(save.isEnabled)
                val error = requireNotNull(textLabel(errorText))
                var moved = false
                repeat(20) {
                    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                    moved = moved || (error.translationX != 0f)
                }
                assertTrue("Each invalid action moves the error", moved)
                assertEquals(0f, error.translationX, 0.001f)
                assertEquals(initialEditorHeight, textScroller().height)
            }
        } finally {
            ShadowChoreographer.setPaused(wasPaused)
            ShadowChoreographer.setFrameDelay(previousDelay)
        }
        assertTrue(mode(R.string.ui_lyrics_mode_text).isChecked)
        assertEquals(invalid, editor().text.toString())

        val edited = "[00:01.00]Edited\n[00:02.00]New line"
        editor().setText(edited)
        mode(R.string.ui_lyrics_mode_timeline).performClick()
        await { textLabel("New line") != null }
        mode(R.string.ui_lyrics_mode_text).performClick()
        assertEquals(edited, editor().text.toString())
        capture("editor-stable-text-en-400")
        save()
        assertEquals(edited, LyricsStorage.readPlainLyrics(context, "Song", "Artist", 10000))
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h760dp-mdpi")
    fun narrowWordEditorRestoresBulkModeAndSavesWithOriginalTranslation() {
        val original = WordByWordLine(
            startMs = 1000,
            endMs = 4200,
            text = "第一句",
            segments = listOf(WordByWordSegment("第一", 1100, 2300), WordByWordSegment("句", 2600, 3800)),
            translatedText = "First line",
            translatedSegments = listOf(WordByWordSegment("First line", 1100, 3500)),
        )
        assertTrue(seedLegacyWordLyrics(context, "歌曲", "歌手", 10000, listOf(original)))
        openEditor()
        capture("editor-stable-timeline-zh-320")
        mode(R.string.ui_lyrics_mode_text).performClick()
        val edited = editor().text.toString().replace("第一", "第二")
        editor().setText(edited)
        controller.recreate()
        await {
            val root = dialogOrNull()?.window?.decorView ?: return@await false
            descendants(root).any { (it is EditText) && it.isShown && (it.text.toString() == edited) }
        }
        assertTrue(mode(R.string.ui_lyrics_mode_text).isChecked)
        capture("editor-stable-text-zh-320")
        save()
        assertEquals(
            original.copy(text = "第二句", segments = listOf(original.segments.first().copy(text = "第二"), original.segments.last())),
            WordByWordLyricsStorageOps.readWordByWordLyrics(context, "歌曲", "歌手", 10000).single(),
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-night-mdpi")
    fun dialogInsetsKeepTheCaretAndFooterReachableWhenKeyboardOpensTwice() {
        val lyrics = (1..24).joinToString("\n") { "[00:%02d.00]歌词 %02d".format(java.util.Locale.ROOT, it, it) }
        assertTrue(seedPlainLyrics(context, "Song", "Artist", 30000, lyrics))
        openEditor()
        val list = views().filterIsInstance<RecyclerView>().single()
        val position = 23
        list.scrollToPosition(position)
        await { list.findViewHolderForAdapterPosition(position) != null }
        val row = requireNotNull(list.findViewHolderForAdapterPosition(position)).itemView
        descendants(row).first { it.isClickable && !it.contentDescription.isNullOrEmpty() }.performClick()
        ShadowLooper.idleMainLooper(450, TimeUnit.MILLISECONDS)
        val edit = requireNotNull(textLabel(activity.getString(R.string.ui_edit_line)))
        val bounds = Rect()
        assertTrue(edit.getGlobalVisibleRect(bounds))
        assertEquals(edit.height, bounds.height())

        mode(R.string.ui_lyrics_mode_text).performClick()
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        val dialog = requireNotNull(dialogOrNull())
        val window = requireNotNull(dialog.window)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        val decor = window.decorView
        val viewport = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.container)
        val save = requireNotNull(textLabel(activity.getString(R.string.ui_save_changes)))
        val input = editor()
        val scroller = textScroller()
        val windowWidth = decor.width
        val windowHeight = decor.height
        val keyboardHeight = 306
        val keyboardTop = windowHeight - keyboardHeight

        fun showInsets(keyboard: Int) {
            val insets = WindowInsets.Builder()
                .setFrame(windowWidth, windowHeight)
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setVisible(WindowInsets.Type.statusBars(), true)
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 20))
                .setVisible(WindowInsets.Type.navigationBars(), true)
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsets.Type.ime(), keyboard > 0)
                .build()
            viewport.dispatchApplyWindowInsets(insets)
        }

        fun assertEditingAreaVisible() {
            val footer = Rect()
            assertTrue("The save button has to be visible", save.getGlobalVisibleRect(footer))
            assertEquals(save.height, footer.height())
            assertTrue("Save button $footer overlaps keyboard at $keyboardTop", footer.bottom <= keyboardTop)

            val visibleText = Rect()
            assertTrue("The lyrics pane has to be visible", scroller.getGlobalVisibleRect(visibleText))
            assertTrue("Lyrics pane has to fit several lines: $visibleText", visibleText.height() >= 120)
            val layout = requireNotNull(input.layout)
            val caretLine = layout.getLineForOffset(input.selectionEnd)
            val location = IntArray(2).also(input::getLocationOnScreen)
            val lineTop = location[1] + input.totalPaddingTop + layout.getLineTop(caretLine)
            val lineBottom = location[1] + input.totalPaddingTop + layout.getLineBottom(caretLine)
            assertTrue(
                "Active line [$lineTop, $lineBottom] outside $visibleText (scroll ${scroller.scrollY})",
                (lineTop >= visibleText.top) && (lineBottom <= visibleText.bottom),
            )
        }

        showInsets(0)
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        val heightWithoutKeyboard = scroller.height
        input.requestFocus()
        repeat(2) { opening ->
            val before = input.text!!.toString()
            val position = if (opening == 0) before.indexOf(']') + 1 else before.trimEnd('\r', '\n').length
            input.setSelection(position)
            scroller.scrollTo(0, 0)
            showInsets(keyboardHeight)
            ShadowLooper.idleMainLooper(450, TimeUnit.MILLISECONDS)
            assertEquals("Opening the keyboard must keep the chosen edit position", position, input.selectionEnd)
            assertEditingAreaVisible()
            val connection = requireNotNull(input.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.commitText("字", 1))
            ShadowLooper.idleMainLooper(120, TimeUnit.MILLISECONDS)
            assertEquals(position + 1, input.selectionEnd)
            assertEquals(before.replaceRange(position, position, "字"), activity.graph.uiHost.editorSession.state.value?.text)
            assertEditingAreaVisible()
            showInsets(0)
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertEquals("The text pane must recover when the keyboard closes", heightWithoutKeyboard, scroller.height)
        }

        showInsets(keyboardHeight)
        ShadowLooper.idleMainLooper(450, TimeUnit.MILLISECONDS)
        assertEditingAreaVisible()
        capture("editor-window-ime-visible-zh-360-dark")

        val bodyHeight = scroller.height
        layoutWindow(decor, windowWidth, keyboardTop)
        assertEquals("A window already resized by the IME must fit on its first layout", bodyHeight, scroller.height)
        assertEditingAreaVisible()
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w844dp-h390dp-land-mdpi")
    fun openEditorUsesTheResizedWindowForItsAvailableWidthAndHeight() {
        val lyrics = (1..24).joinToString("\n") { "[00:%02d.00]Lyric %02d".format(java.util.Locale.ROOT, it, it) }
        assertTrue(seedPlainLyrics(context, "Song", "Artist", 30_000, lyrics))
        openEditor()
        mode(R.string.ui_lyrics_mode_text).performClick()
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        val dialog = requireNotNull(dialogOrNull())
        val decor = requireNotNull(dialog.window).decorView
        val sheet: View = requireNotNull(dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet))
        val shortHeight = textScroller().height

        layoutWindow(decor, 1240, 775)
        assertTrue(sheet.width > 640)
        assertTrue("A taller window gives the editor more room", textScroller().height > (shortHeight + 100))
        val save = requireNotNull(textLabel(activity.getString(R.string.ui_save_changes)))
        fun assertSaveFits() {
            val bounds = Rect()
            save.getDrawingRect(bounds)
            (decor as ViewGroup).offsetDescendantRectToMyCoords(save, bounds)
            assertTrue(bounds.left >= 0 && bounds.right <= decor.width)
            assertTrue(bounds.top >= 0 && bounds.bottom <= decor.height)
        }
        assertSaveFits()

        layoutWindow(decor, 550, 720)
        assertTrue(sheet.width <= 550)
        assertSaveFits()
    }

    @Test
    @Suppress("DEPRECATION")
    @Config(sdk = [28], qualifiers = "en-rUS-w400dp-h800dp-mdpi")
    fun legacyKeyboardUsesTheAlreadyResizedWindow() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val lyrics = (1..24).joinToString("\n") { "[00:%02d.00]Lyric %02d".format(java.util.Locale.ROOT, it, it) }
        assertTrue(seedPlainLyrics(context, "Song", "Artist", 30000, lyrics))
        openEditor()
        mode(R.string.ui_lyrics_mode_text).performClick()
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)

        val window = requireNotNull(dialogOrNull()?.window)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        val decor = window.decorView
        val keyboardTop = decor.height - 306
        val scroller = textScroller()
        val heightWithoutKeyboard = scroller.height
        layoutWindow(decor, decor.width, keyboardTop)
        assertTrue(scroller.height in (120 until heightWithoutKeyboard))
        val save = requireNotNull(textLabel(activity.getString(R.string.ui_save_changes)))
        val bounds = Rect()
        assertTrue(save.getGlobalVisibleRect(bounds))
        assertEquals(save.height, bounds.height())
        assertTrue(bounds.bottom <= keyboardTop)
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h900dp-mdpi")
    fun rapidSwitchesKeepTheTitleFixedAndAReleasedSwipeKeepsMoving() {
        val lyrics = (1..90).joinToString("\n") {
            "[%02d:%02d.00]Lyric %02d".format(java.util.Locale.ROOT, it / 60, it % 60, it)
        }
        assertTrue(seedPlainLyrics(context, "Song", "Artist", 120000, lyrics))
        openEditor()
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        val title = requireNotNull(textLabel("Song"))
        val baseline = IntArray(2).also(title::getLocationOnScreen)
        val current = IntArray(2)
        val container = requireNotNull(dialogOrNull()).findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
        val sheet = container.getChildAt(0)
        fun geometry() = "parent=${(container.parent as View).height}, sheet=${sheet.height}, padding=${sheet.paddingBottom}, top=${container.top}"
        val before = geometry()
        listOf(R.string.ui_lyrics_mode_text, R.string.ui_lyrics_mode_timeline, R.string.ui_lyrics_mode_text).forEach {
            mode(it).performClick()
            repeat(4) {
                ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                title.getLocationOnScreen(current)
                assertEquals("Before: $before. After: ${geometry()}", baseline[1], current[1])
                assertEquals(1f, title.alpha, 0f)
                assertTrue(title.isShown)
            }
        }

        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        val scroll = textScroller()
        val downTime = SystemClock.uptimeMillis()
        val x = scroll.width / 2f
        val y = scroll.height * 0.75f
        fun touch(action: Int, offset: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y - offset, 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                scroll.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        touch(MotionEvent.ACTION_DOWN, 0f)
        repeat(5) { index ->
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            touch(MotionEvent.ACTION_MOVE, (index + 1) * 28f)
        }
        val beforeRelease = scroll.scrollY
        ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_UP, 160f)
        val onRelease = scroll.scrollY
        repeat(18) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            scroll.computeScroll()
        }
        assertTrue("Drag over the text should move it", beforeRelease > 0)
        assertTrue("Motion must continue past release", scroll.scrollY > (onRelease + 30))
    }

    private fun layoutWindow(decor: View, width: Int, height: Int) {
        val exact = View.MeasureSpec.EXACTLY
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, exact), View.MeasureSpec.makeMeasureSpec(height, exact))
        decor.layout(0, 0, width, height)
    }

    private fun openEditor() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val host = activity.graph.uiHost
        host.localLyricsRow(host.savedLyricsState().lyrics.single()).performClick()
        await { dialogOrNull() is BottomSheetDialog }
    }

    private fun save() {
        requireNotNull(textLabel(activity.getString(R.string.ui_save_changes))).performClick()
        await { activity.graph.uiHost.editorSession.state.value == null }
    }

    private fun mode(label: Int) = requireNotNull(textLabel(activity.getString(label))) as RadioButton
    private fun editor() = views().filterIsInstance<EditText>().single { it.isShown }
    private fun textScroller() = views().filterIsInstance<NestedScrollView>().single { it.isShown }
    private fun textLabel(text: String) =
        views().filterIsInstance<TextView>().firstOrNull { it.isShown && (it.text.toString() == text) }
    private fun views() = descendants(requireNotNull(dialogOrNull()?.window).decorView).asSequence()
    private fun dialogOrNull(): Dialog? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

    private fun capture(name: String) {
        ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
        val dialog = requireNotNull(dialogOrNull())
        val root = dialog.window!!.decorView
        val screen = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(screen))
        File(System.getProperty("java.io.tmpdir"), "airlyrics-$name.png").outputStream().use {
            screen.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screen.recycle()
        listOf(
            mode(R.string.ui_lyrics_mode_timeline),
            mode(R.string.ui_lyrics_mode_text),
            requireNotNull(textLabel(activity.getString(R.string.ui_save_changes))),
        ).forEach { view ->
            val bounds = Rect()
            val description = "$name: ${view.text}, ${view.width} × ${view.height}"
            assertTrue(description, view.getGlobalVisibleRect(bounds))
            assertTrue("$description, $bounds", (bounds.width() == view.width) && (bounds.height() == view.height))
        }
    }
}
