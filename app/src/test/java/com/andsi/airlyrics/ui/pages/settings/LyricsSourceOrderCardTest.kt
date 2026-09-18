package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceCompactTitle
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceList
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceTitle
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorOnAccent
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LyricsSourceOrderCardTest {
    private lateinit var context: Context
    private var activityController: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        lyricsPreferences().edit().clear().commit()
        QuickFloatingStore.setDesiredVisible(context, false)
    }

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        lyricsPreferences().edit().clear().commit()
        QuickFloatingStore.setDesiredVisible(context, false)
    }

    @Test
    fun toggleOrderedSource_appendsNewSourceAtEnd() {
        assertEquals(
            listOf(PlainLyricsSearchSource.NETEASE, PlainLyricsSearchSource.LRCLIB),
            toggleOrderedPlainLyricsSource(
                selectedSources = listOf(PlainLyricsSearchSource.NETEASE),
                source = PlainLyricsSearchSource.LRCLIB
            )
        )
    }

    @Test
    fun toggleOrderedSource_emptySelectionSelectsFirstSource() {
        assertEquals(
            listOf(PlainLyricsSearchSource.LRCLIB),
            toggleOrderedPlainLyricsSource(
                selectedSources = emptyList(),
                source = PlainLyricsSearchSource.LRCLIB
            )
        )
    }

    @Test
    fun toggleOrderedSource_removesSourceAndPreservesRemainingOrder() {
        assertEquals(
            listOf(PlainLyricsSearchSource.NETEASE, PlainLyricsSearchSource.LRCLIB),
            toggleOrderedPlainLyricsSource(
                selectedSources = listOf(
                    PlainLyricsSearchSource.NETEASE,
                    PlainLyricsSearchSource.MUSIXMATCH,
                    PlainLyricsSearchSource.LRCLIB
                ),
                source = PlainLyricsSearchSource.MUSIXMATCH
            )
        )
    }

    @Test
    fun toggleOrderedSource_removesLastSelectedSource() {
        val selectedSources = listOf(PlainLyricsSearchSource.LRCLIB)

        assertEquals(
            emptyList<PlainLyricsSearchSource>(),
            toggleOrderedPlainLyricsSource(
                selectedSources = selectedSources,
                source = PlainLyricsSearchSource.LRCLIB
            )
        )
    }

    @Test
    fun toggleOrderedSource_reselectingMovesSourceToEnd() {
        val withoutFirst = toggleOrderedPlainLyricsSource(
            selectedSources = listOf(
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.MUSIXMATCH
            ),
            source = PlainLyricsSearchSource.NETEASE
        )

        assertEquals(
            listOf(
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.MUSIXMATCH,
                PlainLyricsSearchSource.NETEASE
            ),
            toggleOrderedPlainLyricsSource(
                selectedSources = withoutFirst,
                source = PlainLyricsSearchSource.NETEASE
            )
        )
    }

    @Test
    fun orderedSourceOptions_placesSelectedSourcesFirstAndKeepsCanonicalRemainder() {
        val options = listOf(
            PlainLyricsSearchSource.NETEASE,
            PlainLyricsSearchSource.MUSIXMATCH,
            PlainLyricsSearchSource.LRCLIB
        )

        assertEquals(
            listOf(
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.MUSIXMATCH
            ),
            orderedLyricsSourceOptions(
                selectedSources = listOf(PlainLyricsSearchSource.LRCLIB),
                sourceOptions = options
            )
        )
        assertEquals(options, orderedLyricsSourceOptions(emptyList(), options))
    }

    @Test
    fun sourceCard_persistsPriorityAndMovesStableButtonsInDisplayOrder() {
        LyricsSettingsStore.setPlainLyricsSearchSources(
            context,
            listOf(PlainLyricsSearchSource.NETEASE)
        )
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)
        val activity = launchActivity()
        val host = activity.graph.uiHost
        var card = createLyricsSourceOrderCard(host, host.lyricsSettingsState())
        var sourceRow = card.requireSourceOrderRow()
        val originalButtons = PlainLyricsSearchSource.onlineSources.associateWith { source ->
            card.requireSourceButton(source)
        }

        assertEquals(PlainLyricsSearchSource.onlineSources, sourceRow.displayedSources())
        assertPriorityButton(originalButtons.getValue(PlainLyricsSearchSource.NETEASE), host, 1)

        val lrclibButton = originalButtons.getValue(PlainLyricsSearchSource.LRCLIB)
        assertFalse(lrclibButton.isSelected)
        assertEquals(
            host.getString(R.string.ui_lyrics_source_not_selected_state),
            ViewCompat.getStateDescription(lrclibButton)
        )
        lrclibButton.performClick()

        assertEquals(
            listOf(
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.MUSIXMATCH
            ),
            sourceRow.displayedSources()
        )
        assertSame(lrclibButton, card.requireSourceButton(PlainLyricsSearchSource.LRCLIB))
        assertPriorityButton(lrclibButton, host, 2)

        originalButtons.getValue(PlainLyricsSearchSource.MUSIXMATCH).performClick()
        val allSources = listOf(
            PlainLyricsSearchSource.NETEASE,
            PlainLyricsSearchSource.LRCLIB,
            PlainLyricsSearchSource.MUSIXMATCH
        )
        assertEquals(allSources, LyricsSettingsStore.getPlainLyricsSearchSources(context))
        assertEquals(allSources, sourceRow.displayedSources())
        allSources.forEachIndexed { index, source ->
            assertPriorityButton(originalButtons.getValue(source), host, index + 1)
        }
        card = createLyricsSourceOrderCard(host, host.lyricsSettingsState())
        sourceRow = card.requireSourceOrderRow()
        assertEquals(allSources, sourceRow.displayedSources())
        allSources.forEachIndexed { index, source ->
            assertPriorityButton(card.requireSourceButton(source), host, index + 1)
        }

        val neteaseButton = card.requireSourceButton(PlainLyricsSearchSource.NETEASE)
        neteaseButton.performClick()
        assertEquals(
            listOf(PlainLyricsSearchSource.LRCLIB, PlainLyricsSearchSource.MUSIXMATCH),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertEquals(
            listOf(
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.MUSIXMATCH,
                PlainLyricsSearchSource.NETEASE
            ),
            sourceRow.displayedSources()
        )
        assertSame(neteaseButton, card.requireSourceButton(PlainLyricsSearchSource.NETEASE))
        assertFalse(neteaseButton.isSelected)
        assertPriorityButton(card.requireSourceButton(PlainLyricsSearchSource.LRCLIB), host, 1)
        assertPriorityButton(card.requireSourceButton(PlainLyricsSearchSource.MUSIXMATCH), host, 2)

        card.requireSourceButton(PlainLyricsSearchSource.LRCLIB).performClick()
        assertEquals(
            listOf(
                PlainLyricsSearchSource.MUSIXMATCH,
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.LRCLIB
            ),
            sourceRow.displayedSources()
        )
        val soleSelectedButton = card.requireSourceButton(PlainLyricsSearchSource.MUSIXMATCH)
        soleSelectedButton.performClick()

        assertEquals(
            emptyList<PlainLyricsSearchSource>(),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertEquals(
            PlainLyricsSearchSource.onlineSources,
            sourceRow.displayedSources()
        )
        PlainLyricsSearchSource.onlineSources.forEach { source ->
            assertUnselectedButton(card.requireSourceButton(source), host)
        }
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun sourceCard_emptySelectionClickSelectsPriorityOneAndMovesTileFirst() {
        val activity = launchActivity()
        val host = activity.graph.uiHost
        val emptyState = host.lyricsSettingsState().copy(selectedPlainLyricsSources = emptyList())
        val card = createLyricsSourceOrderCard(host, emptyState)

        card.requireSourceButton(PlainLyricsSearchSource.LRCLIB).performClick()

        assertEquals(
            listOf(PlainLyricsSearchSource.LRCLIB),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertEquals(
            listOf(
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.MUSIXMATCH
            ),
            card.requireSourceOrderRow().displayedSources()
        )
        assertPriorityButton(card.requireSourceButton(PlainLyricsSearchSource.LRCLIB), host, 1)
    }

    @Test
    fun sourceCard_clickStartsPhysicalReorderAnimationAndSettles() {
        LyricsSettingsStore.setPlainLyricsSearchSources(
            context,
            listOf(PlainLyricsSearchSource.NETEASE)
        )
        val activity = launchActivity()
        val host = activity.graph.uiHost
        val card = createLyricsSourceOrderCard(host, host.lyricsSettingsState())
        val sourceRow = card.requireSourceOrderRow()
        activity.setContentView(card)
        measureAndLayout(card, host.dp(280))

        card.requireSourceButton(PlainLyricsSearchSource.LRCLIB).performClick()
        measureAndLayout(card, host.dp(280))
        sourceRow.viewTreeObserver.dispatchOnPreDraw()

        assertEquals(
            listOf(
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.MUSIXMATCH
            ),
            sourceRow.displayedSources()
        )
        if (ValueAnimator.areAnimatorsEnabled()) {
            assertTrue(sourceRow.sourceButtons().any { abs(it.translationX) > 0.5f })
        }

        advancePastSourceReorder()

        assertTrue(sourceRow.sourceButtons().all { it.translationX == 0f })
    }

    @Test
    fun sourceCard_usesOneRowOfEqualTilesWithoutTruncatingCompactTitles() {
        val activity = launchActivity()
        val host = activity.graph.uiHost
        val card = createLyricsSourceOrderCard(
            host,
            host.lyricsSettingsState().copy(
                selectedPlainLyricsSources = PlainLyricsSearchSource.onlineSources
            )
        )
        val sourceRow = card.requireSourceOrderRow()

        measureAndLayout(card, host.dp(280))

        val cardLayout = card as ViewGroup
        assertEquals(2, cardLayout.childCount)
        assertSame(sourceRow, cardLayout.getChildAt(1))
        assertEquals(3, sourceRow.childCount)
        val buttons = PlainLyricsSearchSource.onlineSources.map { source ->
            card.requireSourceButton(source)
        }
        val measuredWidths = buttons.map { it.measuredWidth }
        assertTrue(measuredWidths.max() - measuredWidths.min() <= 1)
        assertEquals(1, buttons.map { it.measuredHeight }.distinct().size)
        assertEquals(host.dp(AirUiTokens.Layout.LyricsSourceButtonHeight), buttons.first().measuredHeight)
        buttons.forEachIndexed { index, button ->
            val title = button.requireTextView(host.localizedPlainLyricsSourceCompactTitle(button.source))
            val priority = button.requireTextView((index + 1).toString())
            assertEquals(1, title.lineCount)
            assertEquals(0, title.layout.getEllipsisCount(0))
            assertEquals(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    AirUiTokens.TextSize.BodySmall,
                    host.resources.displayMetrics
                ),
                title.textSize,
                0.01f
            )
            assertTrue(abs(priority.centerY() - title.centerY()) <= 1f)
        }
    }

    @Test
    fun settingsHomeLyricsSummaryUsesSourceNamesWithoutPriorityPunctuation() {
        val sources = PlainLyricsSearchSource.onlineSources
        LyricsSettingsStore.setPlainLyricsSearchSources(context, sources)
        val activity = launchActivity()
        val host = activity.graph.uiHost

        val page = createSettingsHomePage(host)
        val expectedSummary = "${host.localizedPlainLyricsSourceList(sources)} · ${host.getString(R.string.ui_auto_save)}"

        assertTrue(page.hasText(expectedSummary))
        assertFalse(expectedSummary.contains("→"))
        assertFalse(expectedSummary.contains("1."))
    }

    @Test
    fun sourceRow_latestOrderCancelsInFlightMotionAndSettlesEveryTile() {
        val activity = launchActivity()
        val host = activity.graph.uiHost
        val row = LyricsSourceOrderRow(host)
        val buttons = PlainLyricsSearchSource.onlineSources.associateWith { source ->
            LyricsSourceOptionButton(
                host = host,
                source = source,
                compactTitle = host.localizedPlainLyricsSourceCompactTitle(source)
            ).apply {
                render(selectedPriority = null, fullTitle = host.localizedPlainLyricsSourceTitle(source))
            }
        }
        activity.setContentView(row)
        val initialOrder = PlainLyricsSearchSource.onlineSources.map(buttons::getValue)
        row.setSourceOrder(initialOrder, animate = false)
        measureAndLayout(row, host.dp(280))

        val animatedOrder = listOf(
            PlainLyricsSearchSource.LRCLIB,
            PlainLyricsSearchSource.NETEASE,
            PlainLyricsSearchSource.MUSIXMATCH
        ).map(buttons::getValue)
        row.setSourceOrder(animatedOrder, animate = true)
        measureAndLayout(row, host.dp(280))
        row.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(animatedOrder.map { it.source }, row.displayedSources())
        if (ValueAnimator.areAnimatorsEnabled()) {
            assertTrue(animatedOrder.any { abs(it.translationX) > 0.5f })
        }

        val latestOrder = listOf(
            PlainLyricsSearchSource.MUSIXMATCH,
            PlainLyricsSearchSource.LRCLIB,
            PlainLyricsSearchSource.NETEASE
        ).map(buttons::getValue)
        row.setSourceOrder(latestOrder, animate = true)
        measureAndLayout(row, host.dp(280))
        row.viewTreeObserver.dispatchOnPreDraw()
        if (ValueAnimator.areAnimatorsEnabled()) {
            assertTrue(latestOrder.any { abs(it.translationX) > 0.5f })
        }

        advancePastSourceReorder()

        assertEquals(latestOrder.map { it.source }, row.displayedSources())
        assertTrue(latestOrder.all { it.translationX == 0f })
    }

    private fun launchActivity(): MainActivity {
        return Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
    }

    private fun assertPriorityButton(
        button: LyricsSourceOptionButton,
        context: MainUiHost,
        priority: Int
    ) {
        assertTrue(button.isSelected)
        assertEquals(
            context.getString(R.string.ui_lyrics_source_selected_priority_state, priority),
            ViewCompat.getStateDescription(button)
        )
        assertEquals(context.localizedPlainLyricsSourceTitle(button.source), button.contentDescription)
        assertEquals(Button::class.java.name, button.accessibilityClassName)
        val priorityLabel = button.requireTextView(priority.toString())
        assertNull(priorityLabel.background)
        assertFalse(priorityLabel.typeface.isBold)
        assertEquals(
            AirColorUtils.withAlpha(context.colorOnAccent, Color.alpha(priorityLabel.currentTextColor)),
            priorityLabel.currentTextColor
        )
        assertEquals(160, Color.alpha(priorityLabel.currentTextColor))
    }

    private fun assertUnselectedButton(
        button: LyricsSourceOptionButton,
        context: Context
    ) {
        assertFalse(button.isSelected)
        assertEquals(
            context.getString(R.string.ui_lyrics_source_not_selected_state),
            ViewCompat.getStateDescription(button)
        )
        assertFalse(button.hasText("1"))
        assertFalse(button.hasText("2"))
        assertFalse(button.hasText("3"))
    }

    private fun measureAndLayout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun advancePastSourceReorder() {
        shadowOf(Looper.getMainLooper()).idleFor(
            AirUiTokens.Motion.LyricsSourceReorderMs + 50L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun View.requireSourceOrderRow(): LyricsSourceOrderRow =
        descendantsAndSelf().filterIsInstance<LyricsSourceOrderRow>().firstOrNull()
            ?: error("No lyrics source order row found")

    private fun ViewGroup.sourceButtons(): List<LyricsSourceOptionButton> =
        (0 until childCount).map { index -> getChildAt(index) as LyricsSourceOptionButton }

    private fun View.requireSourceButton(source: PlainLyricsSearchSource): LyricsSourceOptionButton =
        descendantsAndSelf()
            .filterIsInstance<LyricsSourceOptionButton>()
            .firstOrNull { it.source == source }
            ?: error("No lyrics source button found for: $source")

    private fun View.requireTextView(expectedText: String): TextView {
        return descendantsAndSelf()
            .filterIsInstance<TextView>()
            .firstOrNull { it.text.toString() == expectedText }
            ?: error("No TextView found with text: $expectedText")
    }

    private fun View.hasText(expectedText: String): Boolean =
        descendantsAndSelf()
            .filterIsInstance<TextView>()
            .any { it.text.toString() == expectedText }

    private fun View.centerY(): Float = (top + bottom) / 2f

    private fun View.descendantsAndSelf(): Sequence<View> = sequence {
        yield(this@descendantsAndSelf)
        if (this@descendantsAndSelf is ViewGroup) {
            for (index in 0 until childCount) {
                yieldAll(getChildAt(index).descendantsAndSelf())
            }
        }
    }

    private fun lyricsPreferences() =
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
}
