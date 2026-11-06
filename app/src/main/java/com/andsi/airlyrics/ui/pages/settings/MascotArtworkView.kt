package com.andsi.airlyrics.ui.pages.settings

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.doOnPreDraw
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.MascotSession
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private typealias Layer = MascotArt.Layer

internal fun cloudSpringRemaining(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    if (t >= 1f) return 0f
    return exp(-5.6f * t) * (cos(10.5f * t) + (0.31f * sin(10.5f * t)))
}

/** Drawn above the About scroll view so the lifted cat can fall and be grabbed anywhere on the page. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class MascotDragOverlay(activity: MainUiHost) : View(activity) {
    private enum class GrabMode { UNDECIDED, DRAG, SPRING }

    private var art: MascotArt? = null
    private var source: InteractiveLogoView? = null
    private val session = activity.mascotSession
    private var ownerToken = 0L
    private var githubTarget: View? = null
    private val targetLocation = IntArray(2)
    private var targetX = 0f
    private var targetY = 0f
    private var touchDragging = false
    private var hoverPending = false
    private var hovering = false
    private var hoverProgress = 0f
    private var hoverDestination = 0f
    private var hoverAnimator: ValueAnimator? = null
    private var absorbAnimator: ValueAnimator? = null
    private var absorbProgress = 0f
    private val hoverRunnable = Runnable {
        hoverPending = false
        if (isShowing && isNearGithub()) {
            hovering = true
            githubTarget?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            animateHover(1f)
        }
    }
    private val fall = MascotFall(resources.displayMetrics.density)
    private val collisionExpression = MascotCollisionExpression(resources.displayMetrics.density)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var heldPointerId = -1
    private var grabX = 0f
    private var grabY = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private var grabLift = 0f
    private var movedWhileHeld = false
    private var grabMode = GrabMode.UNDECIDED
    private var wasGroundedAtGrab = false
    private var groundMix = 0f
    private var springCompression = 0f
    private var velocityTracker: VelocityTracker? = null
    private val sourceLocation = IntArray(2)
    private val overlayLocation = IntArray(2)
    private var anchorX = 0f
    private var anchorY = 0f
    private var artSize = 0f
    private var sourceInset = 0f
    private var lastFrameNanos = 0L
    private var framePosted = false
    private var swing = 0f
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        if ((source != null) && (heldPointerId == -1) && isAttachedToWindow) {
            refreshAnchor()
            setFallBounds()
            val seconds = if (lastFrameNanos == 0L) 1f / 60f
                else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.04f)
            lastFrameNanos = frameTimeNanos
            val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
            val moving = if (animationsEnabled) fall.step(seconds)
                else { fall.settleOnFloor(); false }
            val now = SystemClock.uptimeMillis()
            if (animationsEnabled && (fall.collisionEdge != null)) {
                collisionExpression.onCollision(fall.collisionSpeed, now)
            }
            dx = fall.x
            dy = fall.y
            lift = (lift + (seconds * 5f)).coerceAtMost(1f)
            if (animationsEnabled) {
                springCompression *= exp(-11f * seconds)
                val targetGround = if (fall.isGrounded || (fall.impact > 0.05f)) 1f else 0f
                groundMix += (targetGround - groundMix) * (seconds * 12f).coerceAtMost(1f)
                if (abs(groundMix - targetGround) < 0.005f) groundMix = targetGround
            } else {
                groundMix = 1f
                springCompression = 0f
                collisionExpression.clear()
            }
            val targetSwing = (fall.vx / (artSize * 4f).coerceAtLeast(1f)).coerceIn(-6f, 6f)
            swing += (targetSwing - swing) * (seconds * 10f).coerceAtMost(1f)
            if (!moving && (lift >= 1f)) swing = 0f
            captureState()
            invalidate()
            val targetGroundMix = if (fall.isGrounded || (fall.impact > 0.05f)) 1f else 0f
            if (moving || (lift < 1f) || (springCompression > 0.01f) ||
                (groundMix != targetGroundMix) || collisionExpression.isActive(now)
            ) scheduleFrame()
        }
    }
    var dx = 0f
        private set
    var dy = 0f
        private set
    var lift = 0f
        private set
    var shy = false
    val isShowing get() = source != null
    val isCatAbsent get() = isShowing || (session.mode == MascotSession.Mode.ABSORBED)
    val isGrounded get() = isShowing && fall.isGrounded && (groundMix > 0.9f)
    val compression get() = springCompression
    val isGithubHovered get() = hovering

    init {
        isClickable = false
        isFocusable = false
    }

    fun setGithubTarget(target: View) {
        githubTarget = target
    }

    fun restoreIfNeeded(source: InteractiveLogoView, art: MascotArt, inset: Float) {
        if ((session.mode != MascotSession.Mode.FLOATING) || isShowing || (width == 0) || (height == 0)) return
        val savedX = session.centreXFraction
        val savedY = session.centreYFraction
        val savedVx = session.velocityXDp
        val savedVy = session.velocityYDp
        val savedGrounded = session.grounded
        val savedLift = session.lift
        val savedGroundMix = session.groundMix
        val savedShy = session.shy
        show(source, art, inset, restoring = true)
        dx = (savedX * width) - anchorX - (artSize * 0.5f)
        dy = (savedY * height) - anchorY - (artSize * 0.5f)
        setFallBounds()
        fall.launch(dx, dy, savedVx * resources.displayMetrics.density, savedVy * resources.displayMetrics.density)
        if (savedGrounded) fall.settleOnFloor()
        dx = fall.x
        dy = fall.y
        lift = savedLift
        groundMix = if (savedGrounded) 1f else savedGroundMix
        shy = savedShy
        captureState()
        source.invalidate()
        invalidate()
        if (!savedGrounded) scheduleFrame()
    }

    fun show(source: InteractiveLogoView, art: MascotArt, inset: Float, restoring: Boolean = false) {
        stopPhysics()
        ownerToken = session.claim()
        if (!restoring) session.float(ownerToken)
        this.source = source
        this.art = art
        sourceInset = inset
        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(overlayLocation)
        anchorX = (sourceLocation[0] - overlayLocation[0]) + inset
        anchorY = (sourceLocation[1] - overlayLocation[1]) + inset
        artSize = source.width - (inset * 2f)
        dx = 0f
        dy = 0f
        lift = 0f
        swing = 0f
        groundMix = 0f
        springCompression = 0f
        collisionExpression.clear()
        shy = false
        touchDragging = false
        captureState()
        invalidate()
    }

    /** Direct dragging stays bounded by the lifted cat's visible body, not transparent art margins. */
    fun update(rawDx: Float, rawDy: Float, rawLift: Float, fromTouch: Boolean = false) {
        if (!isShowing) return
        val minX = -anchorX - (artSize * 0.18f)
        val minY = -anchorY - (artSize * 0.05f)
        val maxX = (width - anchorX - (artSize * 0.84f)).coerceAtLeast(minX)
        val bodyMaxY = (height - anchorY - (artSize * (410f / 512f))).coerceAtLeast(minY)
        // A short About viewport can put the resting cloud below the full hanging body's limit.
        // Let the hand align the cat with that cloud; free fall still uses full-body bounds.
        val docking = (abs(rawDx) <= (artSize * 0.29f)) && (abs(rawDy) <= (artSize * 0.29f))
        val maxY = if (docking) bodyMaxY.coerceAtLeast(0f) else bodyMaxY
        dx = rawDx.coerceIn(minX, maxX)
        dy = rawDy.coerceIn(minY, maxY)
        lift = rawLift.coerceIn(0f, 1f)
        swing = (((dx - grabDx) / artSize.coerceAtLeast(1f)) * 3f).coerceIn(-6f, 6f)
        touchDragging = fromTouch
        if (fromTouch) updateHover() else clearHover()
        captureState()
        invalidate()
    }

    fun release(velocityX: Float, velocityY: Float, mayDock: Boolean) {
        if (!isShowing) return
        if (mayDock && hovering && isNearGithub()) {
            startAbsorption()
            return
        }
        touchDragging = false
        clearHover()
        if (mayDock && nearCloud()) {
            stopPhysics()
            groundMix = 0f
            springCompression = 0f
            collisionExpression.clear()
            source?.dockCat()
            return
        }
        stopPhysics()
        setFallBounds()
        fall.launch(dx, dy, velocityX, velocityY)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            fall.settleOnFloor()
            dx = fall.x
            dy = fall.y
            lift = 1f
            swing = 0f
            groundMix = 1f
            springCompression = 0f
            collisionExpression.clear()
            captureState()
            invalidate()
        } else {
            captureState()
            scheduleFrame()
        }
    }

    private fun nearCloud(): Boolean {
        refreshAnchor()
        return (abs(dx) <= (artSize * 0.29f)) &&
            (abs(dy) <= (artSize * 0.29f))
    }

    private fun isNearGithub(): Boolean {
        val target = githubTarget ?: return false
        if (!target.isAttachedToWindow || (target.width == 0) || (artSize <= 0f)) return false
        refreshAnchor()
        target.getLocationInWindow(targetLocation)
        getLocationInWindow(overlayLocation)
        targetX = (targetLocation[0] - overlayLocation[0]) + (target.width * 0.5f)
        targetY = (targetLocation[1] - overlayLocation[1]) + (target.height * 0.5f)
        val faceX = anchorX + dx + (artSize * 0.5f)
        val faceY = anchorY + dy + (artSize * (232f / 512f))
        val margin = artSize * if (hovering || hoverPending) 0.18f else 0.13f
        return (abs(faceX - targetX) <= ((target.width * 0.5f) + margin)) &&
            (abs(faceY - targetY) <= ((target.height * 0.5f) + margin))
    }

    private fun updateHover() {
        if (isNearGithub()) {
            if (!hovering && !hoverPending) {
                hoverPending = true
                postDelayed(hoverRunnable, 100L)
            }
        } else clearHover()
    }

    private fun clearHover() {
        removeCallbacks(hoverRunnable)
        hoverPending = false
        if (hovering || (hoverProgress > 0f)) {
            hovering = false
            animateHover(0f)
        }
    }

    private fun animateHover(target: Float) {
        if ((target == hoverDestination) && (hoverAnimator?.isRunning == true)) return
        hoverDestination = target
        hoverAnimator?.cancel()
        hoverAnimator = null
        if (!ValueAnimator.areAnimatorsEnabled()) {
            hoverProgress = target
            invalidate()
            return
        }
        if (abs(hoverProgress - target) < 0.001f) return
        hoverAnimator = ValueAnimator.ofFloat(hoverProgress, target).apply {
            duration = 140L
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                hoverProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startAbsorption() {
        stopPhysics()
        removeCallbacks(hoverRunnable)
        hoverPending = false
        hovering = false
        touchDragging = false
        hoverAnimator?.cancel()
        hoverAnimator = null
        absorbAnimator?.cancel()
        session.absorb(ownerToken)
        collisionExpression.clear()
        shy = false
        source?.invalidate()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishAbsorption()
            return
        }
        absorbAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 430L
            interpolator = DecelerateInterpolator(1.15f)
            addUpdateListener {
                absorbProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) {
                            absorbAnimator = null
                            finishAbsorption()
                        }
                    }
                },
            )
            start()
        }
    }

    private fun finishAbsorption() {
        val logo = source
        hide()
        logo?.invalidate()
    }

    private fun captureState() {
        if ((session.mode != MascotSession.Mode.FLOATING) || (source == null) || (width == 0) || (height == 0)) return
        val density = resources.displayMetrics.density
        session.capture(
            ownerToken,
            (anchorX + dx + (artSize * 0.5f)) / width,
            (anchorY + dy + (artSize * 0.5f)) / height,
            if (touchDragging) 0f else fall.vx / density,
            if (touchDragging) 0f else fall.vy / density,
            !touchDragging && fall.isGrounded,
            lift, groundMix, shy,
        )
    }

    /** Keep the cat at its screen position if the About content scrolls behind it. */
    private fun refreshAnchor() {
        val owner = source ?: return
        owner.getLocationInWindow(sourceLocation)
        getLocationInWindow(overlayLocation)
        val nextX = (sourceLocation[0] - overlayLocation[0]) + sourceInset
        val nextY = (sourceLocation[1] - overlayLocation[1]) + sourceInset
        val shiftX = anchorX - nextX
        val shiftY = anchorY - nextY
        if ((shiftX != 0f) || (shiftY != 0f)) {
            dx += shiftX
            dy += shiftY
            fall.shiftOrigin(shiftX, shiftY)
            anchorX = nextX
            anchorY = nextY
        }
    }

    private fun setFallBounds() {
        fall.setBounds(
            -anchorX - (artSize * 0.18f),
            -anchorY - (artSize * 0.21f),
            width - anchorX - (artSize * 0.84f),
            height - anchorY - (artSize * (410f / 512f)),
        )
    }

    private fun scheduleFrame() {
        if (framePosted) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopPhysics() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback)
        framePosted = false
        lastFrameNanos = 0L
    }

    fun returnToCloud() {
        session.dock(ownerToken)
        hide()
    }

    fun hide() {
        if (isAttachedToWindow && (source?.isAttachedToWindow == true)) refreshAnchor()
        captureState()
        stopPhysics()
        removeCallbacks(hoverRunnable)
        hoverPending = false
        hovering = false
        hoverAnimator?.cancel()
        hoverAnimator = null
        absorbAnimator?.cancel()
        absorbAnimator = null
        velocityTracker?.recycle()
        velocityTracker = null
        heldPointerId = -1
        val previousSource = source
        source = null
        art = null
        touchDragging = false
        hoverProgress = 0f
        hoverDestination = 0f
        absorbProgress = 0f
        dx = 0f
        dy = 0f
        lift = 0f
        swing = 0f
        groundMix = 0f
        springCompression = 0f
        collisionExpression.clear()
        shy = false
        previousSource?.invalidate()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshAnchor()
                val left = anchorX + dx + (artSize * 0.18f)
                val right = anchorX + dx + (artSize * 0.84f)
                val top = anchorY + dy + (artSize * (0.20f + (0.08f * groundMix)))
                val bottom = anchorY + dy + (artSize * 0.82f)
                if ((source == null) || (session.mode == MascotSession.Mode.ABSORBED) ||
                    (event.x !in (left..right)) || (event.y !in (top..bottom))) return false
                wasGroundedAtGrab = fall.isGrounded
                stopPhysics()
                heldPointerId = event.getPointerId(0)
                grabX = event.x
                grabY = event.y
                grabDx = dx
                grabDy = dy
                grabLift = lift
                movedWhileHeld = false
                grabMode = GrabMode.UNDECIDED
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                source?.onFloatingGrabStart()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(heldPointerId)
                if (index >= 0) {
                    velocityTracker?.addMovement(event)
                    val mx = event.getX(index) - grabX
                    val my = event.getY(index) - grabY
                    val distance = hypot(mx, my)
                    if ((grabMode == GrabMode.UNDECIDED) && (distance > touchSlop)) {
                        grabMode = if (wasGroundedAtGrab && (my > touchSlop) && (abs(mx) < (artSize * 0.08f)))
                            GrabMode.SPRING else GrabMode.DRAG
                        movedWhileHeld = true
                    }
                    when (grabMode) {
                        GrabMode.SPRING -> {
                            clearHover()
                            springCompression = (my / (artSize * 0.38f)).coerceIn(0f, 1f)
                            groundMix = 1f
                            lift = 1f
                            captureState()
                            invalidate()
                        }
                        GrabMode.DRAG -> {
                            if (wasGroundedAtGrab) {
                                groundMix = (1f - (distance / (artSize * 0.25f))).coerceIn(0f, 1f)
                                springCompression = 0f
                            }
                            update(grabDx + mx, grabDy + my, grabLift + (distance / (artSize * 0.18f)), fromTouch = true)
                        }
                        GrabMode.UNDECIDED -> Unit
                    }
                }
                return heldPointerId != -1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (heldPointerId == -1) return false
                finishGrab(event, event.actionMasked == MotionEvent.ACTION_CANCEL)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return heldPointerId != -1
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == heldPointerId) finishGrab(event, cancelled = true)
                return true
            }
        }
        return false
    }

    private fun finishGrab(event: MotionEvent, cancelled: Boolean) {
        val pointerId = heldPointerId
        var vx = 0f
        var vy = 0f
        if (!cancelled) {
            velocityTracker?.addMovement(event)
            velocityTracker?.computeCurrentVelocity(1000)
            vx = velocityTracker?.getXVelocity(pointerId) ?: 0f
            vy = velocityTracker?.getYVelocity(pointerId) ?: 0f
        }
        velocityTracker?.recycle()
        velocityTracker = null
        heldPointerId = -1
        parent?.requestDisallowInterceptTouchEvent(false)
        if (cancelled || (grabMode != GrabMode.DRAG)) clearHover()
        when (grabMode) {
            GrabMode.SPRING -> {
                if (!cancelled && ValueAnimator.areAnimatorsEnabled() && (springCompression > 0.05f)) {
                    val centreX = anchorX + dx + (artSize * 0.5f)
                    val offset = (grabX - centreX) / artSize.coerceAtLeast(1f)
                    val direction = if (abs(offset) > 0.08f) {
                        if (offset > 0f) 1f else -1f
                    } else if (centreX < (width * 0.5f)) 1f else -1f
                    if (fall.springLaunch(springCompression, direction)) {
                        collisionExpression.clear()
                        scheduleFrame()
                    }
                } else {
                    springCompression = 0f
                    groundMix = 1f
                    fall.settleOnFloor()
                    invalidate()
                }
            }
            GrabMode.UNDECIDED -> {
                if (wasGroundedAtGrab) {
                    fall.settleOnFloor()
                    groundMix = 1f
                    invalidate()
                } else source?.onFloatingGrabEnd(0f, 0f, mayDock = false)
            }
            GrabMode.DRAG -> source?.onFloatingGrabEnd(vx, vy, movedWhileHeld && !cancelled)
        }
        grabMode = GrabMode.UNDECIDED
        captureState()
    }

    override fun onDraw(canvas: Canvas) {
        val artwork = art ?: return
        val q = maxOf(springCompression, fall.impact * 0.68f).coerceIn(0f, 1f)
        val absorbing = session.mode == MascotSession.Mode.ABSORBED
        val mood = if (absorbing) MascotCollisionExpression.Face.DIZZY
            else collisionExpression.faceAt(SystemClock.uptimeMillis())
        val drawLift = lift + ((1f - lift) * absorbProgress)
        val drawGroundMix = groundMix * (1f - absorbProgress)
        val faceX = anchorX + dx + (artSize * 0.5f)
        val faceY = anchorY + dy + (artSize * (232f / 512f))
        val magnetLimit = 12f * resources.displayMetrics.density
        val magnetX = ((targetX - faceX) * 0.18f).coerceIn(-magnetLimit, magnetLimit) * hoverProgress
        val magnetY = ((targetY - faceY) * 0.18f).coerceIn(-magnetLimit, magnetLimit) * hoverProgress
        val travelX = (magnetX * (1f - absorbProgress)) + ((targetX - faceX) * absorbProgress)
        val travelY = (magnetY * (1f - absorbProgress)) + ((targetY - faceY) * absorbProgress)
        val scale = (1f - (0.16f * hoverProgress)) * (1f - absorbProgress).coerceAtLeast(0.001f)
        canvas.save()
        canvas.translate(faceX + travelX, faceY + travelY)
        canvas.rotate(620f * absorbProgress)
        canvas.scale(scale, scale)
        canvas.translate(-faceX, -faceY)
        canvas.translate(anchorX + dx, anchorY + dy)
        canvas.scale(artSize / 512f, artSize / 512f)
        canvas.rotate(swing * drawLift * (1f - drawGroundMix) * (1f - absorbProgress), 256f, 180f)
        // Both ground key poses touch y=370; the hanging texture touches y=410.
        canvas.translate(0f, 40f * drawGroundMix)
        canvas.clipRect(0f, 0f, 512f, 288f + (224f * drawLift))
        val hangAlpha = ((drawLift - 0.78f) / 0.22f).coerceIn(0f, 1f)
        if (hangAlpha < 1f) artwork.drawSoftBody(canvas, drawLift, 1f - hangAlpha)
        if (hangAlpha > 0f) artwork.drawGroundBody(canvas, drawGroundMix, q, hangAlpha)

        canvas.save()
        canvas.translate(0f, 32f * q * drawGroundMix)
        when (mood) {
            MascotCollisionExpression.Face.DIZZY -> {
                if (ValueAnimator.areAnimatorsEnabled()) {
                    artwork.draw(canvas, Layer.FACE_DIZZY_BASE)
                    val angle = if (absorbing) 1080f * absorbProgress else
                        ((SystemClock.uptimeMillis() - collisionExpression.dizzyStartedAt) * 0.24f) % 360f
                    canvas.withRotation(angle, 204f, 239f) {
                        artwork.draw(this, Layer.DIZZY_EYE_LEFT)
                    }
                    canvas.withRotation(-angle, 294f, 234f) {
                        artwork.draw(this, Layer.DIZZY_EYE_RIGHT)
                    }
                } else {
                    artwork.draw(canvas, Layer.FACE_DIZZY)
                }
            }
            MascotCollisionExpression.Face.SPEECHLESS ->
                artwork.draw(canvas, Layer.FACE_SPEECHLESS)
            MascotCollisionExpression.Face.NONE -> {
                val face = if (drawGroundMix > 0.5f) Layer.FACE_IDLE
                    else if (shy) Layer.FACE_SHY else Layer.FACE_SURPRISE
                artwork.draw(canvas, face)
            }
        }
        canvas.restore()

        if (hangAlpha < 1f) {
            artwork.draw(canvas, Layer.PAW_LEFT, 1f - hangAlpha)
            artwork.draw(canvas, Layer.PAW_RIGHT, 1f - hangAlpha)
        }
        if (hangAlpha > 0f) artwork.drawGroundPaws(canvas, drawGroundMix, q, hangAlpha)
        if (mood == MascotCollisionExpression.Face.SPEECHLESS) {
            canvas.withTranslation(y = 32f * q * drawGroundMix) {
                artwork.draw(this, Layer.SPEECHLESS_LINES)
            }
        }
        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isShowing && (heldPointerId == -1) && (session.mode == MascotSession.Mode.FLOATING)) {
            refreshAnchor()
            setFallBounds()
            fall.launch(dx, dy, fall.vx, fall.vy)
            captureState()
            scheduleFrame()
        }
    }

    override fun onDetachedFromWindow() {
        hide()
        githubTarget = null
        super.onDetachedFromWindow()
    }
}

@SuppressLint("ViewConstructor")
internal class InteractiveLogoView(
    activity: MainUiHost,
    private val dragOverlay: MascotDragOverlay,
    private val onExtraClick: () -> Unit,
) : View(activity) {
    private val artwork = MascotArt(activity)
    private val gesture = MascotGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val inset = activity.dp(8).toFloat()
    private var animator: ValueAnimator? = null
    private var catVelocity: VelocityTracker? = null
    private var press = 0f
    private var cloudX = 0f
    private var cloudY = 0f
    private var cloudSquash = 0f
    private var reaction = 0f
    private var shyMix = 0f
    private var cloudDragStarted = 0L
    private val shyRunnable = Runnable {
        if (dragOverlay.isShowing) {
            dragOverlay.shy = true
            dragOverlay.invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "AirLyrics"
        doOnPreDraw { dragOverlay.restoreIfNeeded(this, artwork, inset) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width - (2f * inset), height - (2f * inset))
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 512f, size / 512f)

        if (!dragOverlay.isCatAbsent) {
            canvas.withScale(1f + (0.025f * press), 1f - (0.05f * press), 256f, 260f) {
                rotate(((cloudX / size) * 5f).coerceIn(-2f, 2f), 256f, 190f)
                artwork.draw(this, Layer.REST)
            }
        }

        val pullX = (cloudX / size) * 512f
        val pullY = (cloudY / size) * 512f
        if (dragOverlay.isCatAbsent) {
            artwork.drawElasticCloud(canvas, Layer.CLOUD_COMPLETE, pullX, pullY, cloudSquash)
        } else {
            artwork.drawElasticCloud(canvas, Layer.CLOUD_FRONT, pullX, pullY, cloudSquash)
            artwork.drawElasticCloud(canvas, Layer.CLOUD_LYRICS, pullX, pullY, cloudSquash)
        }
        canvas.save()
        canvas.translate(pullX * MascotArt.CLOUD_ATTACHMENT_FRACTION, pullY * MascotArt.CLOUD_ATTACHMENT_FRACTION)
        artwork.draw(canvas, Layer.NOTES)
        canvas.restore()

        if (!dragOverlay.isCatAbsent) {
            canvas.withRotation(((cloudX / size) * 5f).coerceIn(-2f, 2f), 256f, 190f) {
                scale(1f + (0.01f * press), 1f - (0.02f * press), 256f, 260f)
                if (reaction < 1f) artwork.draw(this, Layer.FACE_IDLE, 1f - reaction)
                if (reaction > 0f) {
                    if (press > 0f) {
                        artwork.draw(this, Layer.FACE_HAPPY, reaction)
                    } else {
                        artwork.draw(this, Layer.FACE_SURPRISE, reaction * (1f - shyMix))
                        artwork.draw(this, Layer.FACE_SHY, reaction * shyMix)
                    }
                }
            }
            canvas.withRotation(((cloudX / size) * 5f).coerceIn(-2f, 2f), 256f, 190f) {
                scale(1f + (0.01f * press), 1f - (0.02f * press), 256f, 260f)
                translate(
                    pullX * MascotArt.CLOUD_ATTACHMENT_FRACTION,
                    (pullY * MascotArt.CLOUD_ATTACHMENT_FRACTION) +
                        (cloudSquash * MascotArt.CLOUD_PAW_COMPRESSION_Y),
                )
                artwork.draw(this, Layer.PAW_LEFT)
                artwork.draw(this, Layer.PAW_RIGHT)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val size = min(width - (2f * inset), height - (2f * inset)).coerceAtLeast(1f)
                val x = (event.x - ((width - size) / 2f)) / size
                val y = (event.y - ((height - size) / 2f)) / size
                val target = mascotTargetAt(x, y)
                if (dragOverlay.isCatAbsent && (target == MascotGesture.Target.CAT)) return false
                if (!gesture.down(event.getPointerId(0), event.x, event.y, target)) return false
                stopAnimation()
                catVelocity?.recycle()
                catVelocity = if (target == MascotGesture.Target.CAT)
                    VelocityTracker.obtain().also { it.addMovement(event) } else null
                resetPose()
                parent?.requestDisallowInterceptTouchEvent(true)
                if (gesture.snapshot.target == MascotGesture.Target.CLOUD) {
                    cloudSquash = 0.035f
                } else {
                    press = 0.35f
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                catVelocity?.addMovement(event)
                val index = event.findPointerIndex(gesture.activePointerId)
                if (index < 0) return true
                val previousPhase = gesture.snapshot.phase
                val (target, phase, dx, dy) = gesture.move(
                    gesture.activePointerId, event.getX(index), event.getY(index),
                )
                if (phase == MascotGesture.Phase.DRAGGING) {
                    if (target == MascotGesture.Target.CAT) {
                        if (previousPhase != MascotGesture.Phase.DRAGGING) {
                            press = 0f
                            reaction = 0f
                            dragOverlay.show(this, artwork, inset)
                            invalidate()
                            postDelayed(shyRunnable, 450L)
                        }
                        val size = (width - (2f * inset)).coerceAtLeast(1f)
                        dragOverlay.update(dx, dy, hypot(dx, dy) / (size * 0.18f), fromTouch = true)
                    } else {
                        if (previousPhase != MascotGesture.Phase.DRAGGING) cloudDragStarted = SystemClock.uptimeMillis()
                        val density = context.resources.displayMetrics.density
                        cloudX = (dx * 0.30f).coerceIn(-18f * density, 18f * density)
                        cloudY = (dy * 0.25f).coerceIn(-14f * density, 14f * density)
                        cloudSquash = (hypot(dx, dy) / (width * 2.2f)).coerceIn(0f, 0.08f)
                        reaction = 1f
                        shyMix = ((SystemClock.uptimeMillis() - cloudDragStarted) / 300f).coerceIn(0f, 1f)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                catVelocity?.addMovement(event)
                catVelocity?.computeCurrentVelocity(1000)
                val vx = catVelocity?.getXVelocity(pointerId) ?: 0f
                val vy = catVelocity?.getYVelocity(pointerId) ?: 0f
                catVelocity?.recycle()
                catVelocity = null
                val result = gesture.up(pointerId, event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                when (result) {
                    MascotGesture.End.Tap(MascotGesture.Target.CAT) -> performClick()
                    MascotGesture.End.Tap(MascotGesture.Target.CLOUD) -> cloudTap()
                    MascotGesture.End.Drag(MascotGesture.Target.CAT) -> dragOverlay.release(vx, vy, mayDock = true)
                    MascotGesture.End.Drag(MascotGesture.Target.CLOUD) -> returnCloud()
                    else -> resetPose()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val target = gesture.snapshot.target
                gesture.cancel()
                catVelocity?.recycle()
                catVelocity = null
                parent?.requestDisallowInterceptTouchEvent(false)
                when (target) {
                    MascotGesture.Target.CAT -> if (dragOverlay.isShowing) dragOverlay.release(0f, 0f, mayDock = false)
                        else resetPose()
                    MascotGesture.Target.CLOUD -> returnCloud()
                    null -> resetPose()
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == gesture.activePointerId) {
                    val target = gesture.snapshot.target
                    gesture.cancel()
                    catVelocity?.recycle()
                    catVelocity = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    when (target) {
                        MascotGesture.Target.CAT -> if (dragOverlay.isShowing) dragOverlay.release(0f, 0f, mayDock = false)
                            else resetPose()
                        MascotGesture.Target.CLOUD -> returnCloud()
                        null -> resetPose()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onExtraClick()
        stopAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetPose()
            return true
        }
        animateProgress(
            durationMs = 230L,
            update = { f ->
                press = (0.35f * (1f - f)) + (0.65f * sin(PI * f).toFloat().coerceAtLeast(0f))
                reaction = sin(PI * f).toFloat().coerceAtLeast(0f)
                invalidate()
            },
            finish = ::resetPose,
        )
        return true
    }

    private fun cloudTap() {
        stopAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetPose()
            return
        }
        animateProgress(
            durationMs = 560L,
            update = { f ->
                cloudSquash = 0.075f * sin(PI * (f * 2.5f).coerceAtMost(1f)).toFloat().coerceAtLeast(0f)
                reaction = sin(PI * f).toFloat().coerceAtLeast(0f)
                shyMix = ((f - 0.2f) / 0.35f).coerceIn(0f, 1f)
                invalidate()
            },
            finish = ::resetPose,
        )
    }

    private fun returnCloud() {
        stopAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetPose()
            return
        }
        val startX = cloudX
        val startY = cloudY
        val startSquash = cloudSquash
        animateProgress(
            durationMs = 520L,
            update = { f ->
                val spring = cloudSpringRemaining(f)
                cloudX = startX * spring
                cloudY = startY * spring
                cloudSquash = ((startSquash * abs(spring)) + (0.016f * abs(sin(10.5f * f)) * exp(-4f * f)))
                    .coerceAtMost(0.09f)
                reaction = 1f - f
                shyMix = 1f
                invalidate()
            },
            finish = ::resetPose,
        )
    }

    fun onFloatingGrabStart() {
        stopAnimation()
        postDelayed(shyRunnable, 450L)
    }

    fun onFloatingGrabEnd(velocityX: Float, velocityY: Float, mayDock: Boolean) {
        dragOverlay.release(velocityX, velocityY, mayDock)
    }

    fun dockCat() {
        removeCallbacks(shyRunnable)
        if (!dragOverlay.isShowing) return
        stopAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            dragOverlay.returnToCloud()
            resetPose()
            return
        }
        val startX = dragOverlay.dx
        val startY = dragOverlay.dy
        val startLift = dragOverlay.lift
        animateProgress(
            durationMs = 420L,
            update = { f ->
                val eased = OvershootInterpolator(0.45f).getInterpolation(f).coerceAtMost(1.06f)
                val remaining = (1f - eased).coerceAtLeast(0f)
                dragOverlay.update(startX * remaining, startY * remaining, startLift * remaining)
                cloudSquash = if (f > 0.78f) 0.035f * sin(PI * ((f - 0.78f) / 0.22f)).toFloat().coerceAtLeast(0f) else 0f
                invalidate()
            },
        ) {
            dragOverlay.returnToCloud()
            resetPose()
        }
    }

    private fun animateProgress(durationMs: Long, update: (Float) -> Unit, finish: () -> Unit) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener { update(it.animatedFraction) }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) finish()
                        if (animator === animation) animator = null
                    }
                },
            )
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        removeCallbacks(shyRunnable)
    }

    private fun resetPose() {
        press = 0f
        cloudX = 0f
        cloudY = 0f
        cloudSquash = 0f
        reaction = 0f
        shyMix = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        gesture.cancel()
        catVelocity?.recycle()
        catVelocity = null
        dragOverlay.hide()
        artwork.release()
        resetPose()
        super.onDetachedFromWindow()
    }
}
