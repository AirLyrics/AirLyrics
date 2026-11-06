package com.andsi.airlyrics.ui.pages.settings

import kotlin.math.abs
import kotlin.math.exp

/**
 * Small, deterministic page-space simulation for the lifted mascot.
 *
 * Coordinates are offsets from the logo art origin. Bounds keep the visible lifted body on screen;
 * resting on a boundary never docks the cat. Docking belongs to the touch interaction.
 */
internal class MascotFall(private val density: Float) {
    enum class Edge { LEFT, RIGHT, CEILING, FLOOR }

    var collisionEdge: Edge? = null
        private set
    var collisionSpeed = 0f
        private set
    val isGrounded get() = (y >= (bottom - 0.5f)) && (vy == 0f)

    var x = 0f
        private set
    var y = 0f
        private set
    var vx = 0f
        private set
    var vy = 0f
        private set
    var impact = 0f
        private set

    private var left = 0f
    private var top = 0f
    private var right = 0f
    private var bottom = 0f
    private val gravity = 1900f * density
    private val maxSpeed = 2700f * density
    private val quietSpeed = 65f * density

    fun setBounds(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right.coerceAtLeast(left)
        this.bottom = bottom.coerceAtLeast(top)
        x = x.coerceIn(this.left, this.right)
        y = y.coerceIn(this.top, this.bottom)
    }

    fun launch(x: Float, y: Float, vx: Float, vy: Float) {
        this.x = x.coerceIn(left, right)
        this.y = y.coerceIn(top, bottom)
        this.vx = vx.coerceIn(-maxSpeed, maxSpeed)
        this.vy = vy.coerceIn(-maxSpeed, maxSpeed)
        impact = 0f
        collisionEdge = null
        collisionSpeed = 0f
    }

    /** Ground-only spring release. A larger pull adds launch energy once, not on each bounce. */
    fun springLaunch(compression: Float, horizontalDirection: Float): Boolean {
        if (!isGrounded || (compression <= 0.05f)) return false
        val q = compression.coerceIn(0f, 1f)
        vx = (180f + (980f * q)) * density * horizontalDirection.coerceIn(-1f, 1f)
        vy = -(450f + (1950f * q)) * density
        impact = 0f
        collisionEdge = null
        collisionSpeed = 0f
        return true
    }

    fun shiftOrigin(dx: Float, dy: Float) {
        x += dx
        y += dy
        left += dx
        right += dx
        top += dy
        bottom += dy
    }

    fun settleOnFloor() {
        y = bottom
        vx = 0f
        vy = 0f
        impact = 0f
        collisionEdge = null
        collisionSpeed = 0f
    }

    /** Returns true while another frame is useful. */
    fun step(seconds: Float): Boolean {
        val dt = seconds.coerceIn(0f, 0.04f)
        if (dt == 0f) return true
        collisionEdge = null
        collisionSpeed = 0f
        impact *= exp(-13f * dt)

        val onFloor = (y >= (bottom - 0.5f)) && (vy == 0f)
        if (!onFloor) vy = (vy + (gravity * dt)).coerceAtMost(maxSpeed)
        vx *= exp(-0.38f * dt)
        x += vx * dt
        y += vy * dt

        if (x <= left) {
            if (vx < 0f) recordCollision(Edge.LEFT, -vx)
            x = left
            vx = abs(vx) * 0.68f
        } else if (x >= right) {
            if (vx > 0f) recordCollision(Edge.RIGHT, vx)
            x = right
            vx = -abs(vx) * 0.68f
        }
        if (y <= top) {
            if (vy < 0f) recordCollision(Edge.CEILING, -vy)
            y = top
            vy = abs(vy) * 0.52f
        } else if (y >= bottom) {
            if (vy > 0f) recordCollision(Edge.FLOOR, vy)
            y = bottom
            if (vy > quietSpeed) impact = (vy / (900f * density)).coerceIn(0f, 1f)
            vy = if (vy > quietSpeed) -vy * 0.42f else 0f
            vx *= 0.68f
        }
        if ((y >= (bottom - 0.5f)) && (vy == 0f)) {
            val friction = 920f * density * dt
            vx = when {
                vx > friction -> vx - friction
                vx < -friction -> vx + friction
                else -> 0f
            }
        }
        return !(isGrounded && (abs(vx) < 1f) && (impact < 0.01f))
    }

    private fun recordCollision(edge: Edge, speed: Float) {
        if (speed > collisionSpeed) {
            collisionEdge = edge
            collisionSpeed = speed
        }
    }
}
