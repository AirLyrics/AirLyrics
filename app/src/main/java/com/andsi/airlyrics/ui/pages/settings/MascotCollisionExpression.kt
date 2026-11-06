package com.andsi.airlyrics.ui.pages.settings

/** Impact-driven expression with a short cooldown so nearby edge contacts do not flicker. */
internal class MascotCollisionExpression(private val density: Float) {
    enum class Face { NONE, DIZZY, SPEECHLESS }

    var dizzyStartedAt = 0L
        private set
    private var dizzyUntil = 0L
    private var speechlessUntil = 0L
    private var cooldownUntil = 0L
    private var lastImpactAt = 0L
    private var consecutive = 0

    fun onCollision(speed: Float, nowMs: Long) {
        if ((speed < (300f * density)) || (nowMs < cooldownUntil)) return
        consecutive = if ((nowMs - lastImpactAt) < 700L) consecutive + 1 else 1
        lastImpactAt = nowMs
        cooldownUntil = nowMs + 170L
        if ((speed >= (900f * density)) || (consecutive >= 3)) {
            dizzyStartedAt = nowMs
            dizzyUntil = nowMs + 620L
            speechlessUntil = dizzyUntil + 440L
        } else if (nowMs >= dizzyUntil) {
            speechlessUntil = maxOf(speechlessUntil, nowMs + 560L)
        }
    }

    fun faceAt(nowMs: Long): Face = when {
        nowMs < dizzyUntil -> Face.DIZZY
        nowMs < speechlessUntil -> Face.SPEECHLESS
        else -> Face.NONE
    }

    fun isActive(nowMs: Long): Boolean = faceAt(nowMs) != Face.NONE

    fun clear() {
        dizzyStartedAt = 0L
        dizzyUntil = 0L
        speechlessUntil = 0L
        cooldownUntil = 0L
        lastImpactAt = 0L
        consecutive = 0
    }
}
