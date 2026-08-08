package com.andsi.airlyrics.ui.pages.floating

/**
 * Layout and motion constants for the floating lyrics settings page.
 * Keeping these values in one place makes small UI tuning less fragile.
 */
internal object FloatingPageTokens {
    const val PAGE_PADDING_HORIZONTAL_DP = 20
    const val PAGE_PADDING_TOP_DP = 6
    const val PAGE_PADDING_BOTTOM_DP = 24
    const val LIST_PADDING_TOP_DP = 4

    const val SECTION_TITLE_TEXT_SP = 18f
    const val SECTION_TITLE_PADDING_TOP_DP = 12
    const val SECTION_TITLE_PADDING_BOTTOM_DP = 10

    const val PREVIEW_CARD_MARGIN_BOTTOM_DP = 8
    const val PREVIEW_LINE_SPACING_EXTRA_DP = 2
    const val PREVIEW_TOGGLE_SIZE_DP = 36
    const val PREVIEW_TOGGLE_TEXT_SP = 16f
    const val PREVIEW_TOGGLE_ALPHA = 0.62f

    const val PANEL_SELECTED_SCALE = 1.04f
    const val PANEL_SELECTED_ALPHA = 0.92f
    const val PANEL_OPEN_START_SCALE = 0.72f
    const val PANEL_OPEN_OVERSHOOT_TENSION = 0.72f
    const val PANEL_CLOSE_SCALE = 0.86f
    const val PANEL_CLOSE_TRANSLATION_Y_DP = 10

    const val FAST_ANIMATION_MS = 130L
    const val PANEL_OVERLAY_FADE_MS = 120L
    const val PANEL_OPEN_MS = 240L
    const val PANEL_CLOSE_MS = 150L
    const val PREVIEW_FADE_MS = 170L
    const val PREVIEW_SLIDE_MS = 190L
    const val PREVIEW_SCALE_FADE_MS = 180L
    const val PREVIEW_SLIDE_Y_DP = 8
    const val PREVIEW_SCALE_START = 0.96f

    const val OFFSET_STATUS_TEXT_SP = 15f
    const val OFFSET_STATUS_PADDING_TOP_DP = 2
    const val OFFSET_STATUS_PADDING_BOTTOM_DP = 8
}
