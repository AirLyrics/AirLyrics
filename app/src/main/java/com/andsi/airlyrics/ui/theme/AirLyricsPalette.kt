package com.andsi.airlyrics.ui.theme

internal data class AirLyricsPalette(
    val background: Int,
    val surface: Int,
    val surfaceLight: Int,
    val card: Int,
    val bubble: Int,
    val stroke: Int,
    val accent: Int,
    val onAccent: Int,
    val accentLight: Int,
    val accentSoft: Int,
    val accentPink: Int,
    val accentMint: Int,
    val successIcon: Int,
    val danger: Int,
    val textStrong: Int,
    val text: Int,
    val textMuted: Int
) {
    fun interpolate(to: AirLyricsPalette, fraction: Float): AirLyricsPalette {
        fun mix(from: Int, target: Int) = androidx.core.graphics.ColorUtils.blendARGB(from, target, fraction)
        return AirLyricsPalette(
            mix(background, to.background), mix(surface, to.surface),
            mix(surfaceLight, to.surfaceLight), mix(card, to.card), mix(bubble, to.bubble),
            mix(stroke, to.stroke), mix(accent, to.accent), mix(onAccent, to.onAccent),
            mix(accentLight, to.accentLight), mix(accentSoft, to.accentSoft),
            mix(accentPink, to.accentPink), mix(accentMint, to.accentMint),
            mix(successIcon, to.successIcon), mix(danger, to.danger),
            mix(textStrong, to.textStrong), mix(text, to.text), mix(textMuted, to.textMuted)
        )
    }
}
