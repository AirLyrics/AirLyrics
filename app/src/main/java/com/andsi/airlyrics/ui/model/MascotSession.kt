package com.andsi.airlyrics.ui.model

/**
 * About mascot state for this app process. This deliberately does not use SavedStateHandle or
 * preferences: leaving the page and configuration changes retain the cat, a fresh app launch
 * starts with the cat on its cloud.
 */
internal class MascotSession {
    enum class Mode { CLOUD, FLOATING, ABSORBED }

    var mode = Mode.CLOUD
        private set
    var centreXFraction = 0f
        private set
    var centreYFraction = 0f
        private set
    var velocityXDp = 0f
        private set
    var velocityYDp = 0f
        private set
    var grounded = false
        private set
    var lift = 1f
        private set
    var groundMix = 0f
        private set
    var shy = false
        private set

    private var owner = 0L

    fun claim(): Long = ++owner

    fun capture(
        token: Long,
        centreXFraction: Float,
        centreYFraction: Float,
        velocityXDp: Float,
        velocityYDp: Float,
        grounded: Boolean,
        lift: Float,
        groundMix: Float,
        shy: Boolean,
    ) {
        if ((token != owner) || (mode != Mode.FLOATING)) return
        this.centreXFraction = centreXFraction.coerceIn(0f, 1f)
        this.centreYFraction = centreYFraction.coerceIn(0f, 1f)
        this.velocityXDp = velocityXDp
        this.velocityYDp = velocityYDp
        this.grounded = grounded
        this.lift = lift.coerceIn(0f, 1f)
        this.groundMix = groundMix.coerceIn(0f, 1f)
        this.shy = shy
    }

    fun float(token: Long) {
        if (token == owner) mode = Mode.FLOATING
    }

    fun dock(token: Long) {
        if (token == owner) mode = Mode.CLOUD
    }

    fun absorb(token: Long) {
        if (token == owner) mode = Mode.ABSORBED
    }
}
