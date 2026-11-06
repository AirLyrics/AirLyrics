package com.andsi.airlyrics.ui.pages.settings

import kotlin.math.hypot

/**
 * Gesture ownership for the layered About mascot. Coordinates are local to the artwork bounds.
 * The renderer can consume [Phase] without coupling the touch rules to bitmap or animation code.
 */
internal class MascotGesture(private val touchSlop: Float) {
    enum class Target { CAT, CLOUD }
    enum class Phase { IDLE, PRESSED, DRAGGING }
    data class Snapshot(
        val target: Target?,
        val phase: Phase,
        val dx: Float = 0f,
        val dy: Float = 0f,
    )
    sealed interface End {
        data class Tap(val target: Target) : End
        data class Drag(val target: Target) : End
        data object None : End
    }

    var snapshot = Snapshot(null, Phase.IDLE)
        private set
    var activePointerId = -1
        private set
    private var downX = 0f
    private var downY = 0f

    fun down(id: Int, x: Float, y: Float, target: Target?): Boolean {
        if ((activePointerId != -1) || (target == null)) return false
        activePointerId = id
        downX = x
        downY = y
        snapshot = Snapshot(target, Phase.PRESSED)
        return true
    }

    fun move(id: Int, x: Float, y: Float): Snapshot {
        if (id != activePointerId) return snapshot
        val dx = x - downX
        val dy = y - downY
        val phase = if ((snapshot.phase == Phase.DRAGGING) || (hypot(dx, dy) > touchSlop)) {
            Phase.DRAGGING
        } else {
            Phase.PRESSED
        }
        snapshot = Snapshot(snapshot.target, phase, dx, dy)
        return snapshot
    }

    fun up(id: Int, x: Float, y: Float): End {
        if (id != activePointerId) return End.None
        move(id, x, y)
        val result = when (snapshot.phase) {
            Phase.PRESSED -> End.Tap(requireNotNull(snapshot.target))
            Phase.DRAGGING -> End.Drag(requireNotNull(snapshot.target))
            Phase.IDLE -> End.None
        }
        cancel()
        return result
    }

    fun cancel() {
        activePointerId = -1
        snapshot = Snapshot(null, Phase.IDLE)
    }
}

/** Initial bounds from the supplied 512-unit art manifest; the paws belong to the cat. */
internal fun mascotTargetAt(x: Float, y: Float): MascotGesture.Target? {
    if ((x in (0.195f..0.79f)) && (y in (0.213f..0.558f))) return MascotGesture.Target.CAT
    if ((x in (0.117f..0.906f)) && (y in (0.558f..0.90f))) return MascotGesture.Target.CLOUD
    return null
}
