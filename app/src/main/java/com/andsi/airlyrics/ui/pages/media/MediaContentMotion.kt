package com.andsi.airlyrics.ui.pages.media

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.LruCache
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.media.mediaArtworkRefreshKey
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import java.util.WeakHashMap

private fun clamp(value: Float) = value.coerceIn(0f, 1f)
private fun outCubic(value: Float): Float = 1f - (1f - clamp(value)).let { it * it * it }
private fun inCubic(value: Float): Float = clamp(value).let { it * it * it }

/** Playback changes are intentionally shorter and quieter than a song replacement. */
internal class MediaStatusMotion(
    private val host: MainUiHost,
    private val view: TextView,
    private var playing: Boolean? = null
) {
    private var shownText = view.text.toString()
    private var animator: ValueAnimator? = null

    fun show(text: String, isPlaying: Boolean? = playing) {
        if (shownText == text && playing == isPlaying) return
        stopAnimation()
        val fromPlaying = playing
        shownText = text
        playing = isPlaying
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow) {
            applyFinal()
            return
        }
        val oldText = view.text.toString()
        val oldBackground = statusBackground(fromPlaying)
        val newBackground = statusBackground(isPlaying)
        val oldForeground = statusForeground(fromPlaying)
        val newForeground = statusForeground(isPlaying)
        val background = view.background as? GradientDrawable
        val colors = ArgbEvaluator()
        val rise = host.dp(2).toFloat()
        var textSwitched = false
        val running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AirUiTokens.Motion.MediaStatusSwapMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (animator !== animation) return@addUpdateListener
                val progress = animation.animatedValue as Float
                if (oldText != text) {
                    val switched = progress >= 0.5f
                    if (switched && !textSwitched) {
                        view.text = text
                        textSwitched = true
                    }
                    view.alpha = if (switched) {
                        0.8f + 0.2f * outCubic((progress - 0.5f) / 0.5f)
                    } else {
                        1f - 0.2f * inCubic(progress / 0.5f)
                    }
                    view.translationY = if (switched) rise * (1f - outCubic((progress - 0.5f) / 0.5f))
                        else -rise * inCubic(progress / 0.5f)
                }
                if (oldBackground != null && newBackground != null) {
                    background?.setColor(colors.evaluate(progress, oldBackground, newBackground) as Int)
                }
                if (oldForeground != null && newForeground != null) {
                    val foregroundProgress = if (oldText == text) progress else if (textSwitched) 1f else 0f
                    view.setTextColor(colors.evaluate(foregroundProgress, oldForeground, newForeground) as Int)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) stopAnimation()
                }
            })
        }
        animator = running
        running.start()
    }

    fun finish() = stopAnimation()

    private fun stopAnimation() {
        val running = animator
        animator = null
        running?.cancel()
        applyFinal()
    }

    private fun applyFinal() {
        view.text = shownText
        view.alpha = 1f
        view.translationY = 0f
        statusBackground(playing)?.let { (view.background as? GradientDrawable)?.setColor(it) }
        statusForeground(playing)?.let(view::setTextColor)
    }

    private fun statusBackground(value: Boolean?): Int? = when (value) {
        true -> host.colorAccent
        false -> host.colorSurfaceLight
        null -> null
    }

    private fun statusForeground(value: Boolean?): Int? = when (value) {
        true -> host.colorOnAccent
        false -> host.colorTextMuted
        null -> null
    }
}

/** Survives a media-page View rebuild, keeping artwork identity and small decoded URI images. */
internal class MediaArtworkMotionState {
    private val decodedUris = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val hardwareKeys = WeakHashMap<Bitmap, String>()
    private var lastPresentedKey: String? = null
    private var lastPresentedBitmap: Bitmap? = null

    fun wasPresented(key: String): Boolean = lastPresentedKey == key
    fun presented(key: String?, bitmap: Bitmap?) {
        lastPresentedKey = key
        lastPresentedBitmap = bitmap
    }
    fun lastArtwork(): Pair<String, Bitmap>? = lastPresentedKey?.let { key ->
        lastPresentedBitmap?.let { key to it }
    }
    fun decoded(key: String): Bitmap? = decodedUris.get(key)
    fun cache(key: String, bitmap: Bitmap) { decodedUris.put(key, bitmap) }
    fun hardwareKey(bitmap: Bitmap): String? = hardwareKeys[bitmap]
    fun cacheHardwareKey(bitmap: Bitmap, key: String) { hardwareKeys[bitmap] = key }
}

/** Only a resolved image moves; the fixed rounded frame keeps the card layout stable. */
@SuppressLint("ViewConstructor")
internal class MediaArtworkSwap(
    private val host: MainUiHost,
    private val motionState: MediaArtworkMotionState = MediaArtworkMotionState()
) : FrameLayout(host) {
    private var current = imageView()
    private var incoming: ImageView? = null
    private var animator: ValueAnimator? = null
    private var desiredKey: String? = null
    private var hasDesired = false
    private var currentKey: String? = null
    private var incomingKey: String? = null
    private var pendingEntrance = false
    private var pendingHardwareBitmap: Bitmap? = null
    private var requestId = 0L
    private var disposed = false
    private val startEntranceRunnable = Runnable {
        if (pendingEntrance && isAttachedToWindow && width > 0 && height > 0) startEntrance()
    }
    private val startOnLayout = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (width > 0 && height > 0) scheduleEntrance()
    }

    init {
        layoutParams = LinearLayout.LayoutParams(
            host.dp(MEDIA_ARTWORK_SIZE_DP), host.dp(MEDIA_ARTWORK_SIZE_DP)
        ).apply { marginStart = host.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs) }
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(host.colorSurfaceLight)
        }
        clipToOutline = true
        addView(current)
        motionState.lastArtwork()?.let { (key, bitmap) ->
            current.setImageBitmap(bitmap)
            currentKey = key
        }
    }

    fun show(bitmap: Bitmap?, uri: Uri?, contentKey: String, animate: Boolean = true) {
        if (bitmap?.config == Bitmap.Config.HARDWARE) {
            motionState.hardwareKey(bitmap)?.let { key ->
                showIdentified(bitmap, null, key, animate)
                return
            }
            if (disposed || pendingHardwareBitmap === bitmap) return
            pendingHardwareBitmap = bitmap
            val id = ++requestId
            cancelTransition()
            val generation = host.currentUiGeneration()
            host.runOnAppIo {
                val key = try {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)?.let { copy ->
                        try { copy.mediaArtworkRefreshKey() } finally { copy.recycle() }
                    }
                } catch (_: RuntimeException) {
                    null
                } ?: bitmap.mediaArtworkRefreshKey(contentKey)
                host.runOnStartedUi(generation) {
                    if (!disposed && requestId == id && pendingHardwareBitmap === bitmap) {
                        pendingHardwareBitmap = null
                        motionState.cacheHardwareKey(bitmap, key)
                        showIdentified(bitmap, null, key, animate)
                    }
                }
            }
            return
        }
        val key = when {
            bitmap != null -> bitmap.mediaArtworkRefreshKey(contentKey)
            uri != null -> "uri:$uri"
            else -> null
        }
        showIdentified(bitmap, uri, key, animate)
    }

    private fun showIdentified(bitmap: Bitmap?, uri: Uri?, key: String?, animate: Boolean) {
        if (disposed) return
        if (pendingHardwareBitmap != null) {
            pendingHardwareBitmap = null
            requestId++
        }
        if (hasDesired && key == desiredKey) return
        hasDesired = true
        desiredKey = key
        val id = ++requestId
        cancelTransition()
        if (key != null && key == currentKey && current.drawable != null) {
            if (isAttachedToWindow) motionState.presented(key, current.bitmap())
            return
        }
        when {
            bitmap != null -> showResolved(bitmap, key, animate)
            uri != null -> {
                val size = host.dp(MEDIA_ARTWORK_SIZE_DP)
                val cacheKey = "$key:$size"
                motionState.decoded(cacheKey)?.let {
                    showResolved(it, key, animate)
                    return
                }
                val generation = host.currentUiGeneration()
                host.runOnAppIo {
                    val resolved = decodeMediaArtwork(host, uri, size)
                    host.runOnStartedUi(generation) {
                        if (!disposed && requestId == id && desiredKey == key) {
                            if (resolved != null) motionState.cache(cacheKey, resolved)
                            showResolved(resolved, key, animate)
                        }
                    }
                }
            }
            else -> showResolved(null, null, animate)
        }
    }

    fun finish() {
        disposed = true
        requestId++
        pendingHardwareBitmap = null
        cancelTransition()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pendingEntrance) scheduleEntrance()
        else if (current.drawable != null) motionState.presented(currentKey, current.bitmap())
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(startEntranceRunnable)
        removeOnLayoutChangeListener(startOnLayout)
        if (animator != null) completeTransition()
        super.onDetachedFromWindow()
    }

    private fun imageView() = ImageView(host).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
    }

    private fun ImageView.bitmap(): Bitmap? = (drawable as? BitmapDrawable)?.bitmap

    private fun showResolved(bitmap: Bitmap?, key: String?, animate: Boolean) {
        if (bitmap == null) {
            current.setImageDrawable(null)
            currentKey = null
            motionState.presented(null, null)
            return
        }
        if (!animate || !ValueAnimator.areAnimatorsEnabled() ||
            (current.drawable == null && motionState.wasPresented(key!!))) {
            current.setImageBitmap(bitmap)
            currentKey = key
            resetTransform(current)
            if (isAttachedToWindow) motionState.presented(key, bitmap)
            return
        }
        if (current.drawable == null) {
            current.setImageBitmap(bitmap)
            currentKey = key
            prepareEntrance(current)
        } else {
            incoming = imageView().apply {
                setImageBitmap(bitmap)
                prepareEntrance(this)
            }.also(::addView)
            incomingKey = key
        }
        pendingEntrance = true
        scheduleEntrance()
    }

    private fun scheduleEntrance() {
        removeCallbacks(startEntranceRunnable)
        removeOnLayoutChangeListener(startOnLayout)
        if (!pendingEntrance || !isAttachedToWindow) return
        if (width > 0 && height > 0) post(startEntranceRunnable)
        else addOnLayoutChangeListener(startOnLayout)
    }

    private fun startEntrance() {
        pendingEntrance = false
        val next = incoming ?: current
        val key = incomingKey ?: currentKey ?: return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            completeTransition()
            return
        }
        motionState.presented(key, next.bitmap())
        val old = if (incoming != null) current else null
        val rise = host.dp(6).toFloat()
        // The About-page logo's compressed start and overshoot, scaled down for 72dp artwork.
        val overshoot = OvershootInterpolator(1.55f)
        val running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AirUiTokens.Motion.MediaArtworkSwapMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (animator !== animation) return@addUpdateListener
                val fraction = animation.animatedValue as Float
                val spring = overshoot.getInterpolation(fraction)
                val fade = outCubic(fraction / 0.48f)
                next.scaleX = 0.86f + 0.14f * spring
                next.scaleY = next.scaleX
                next.translationY = rise * (1f - spring)
                next.alpha = fade
                old?.alpha = 1f - fade
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) completeTransition()
                }
            })
        }
        animator = running
        running.start()
    }

    private fun cancelTransition() {
        removeCallbacks(startEntranceRunnable)
        removeOnLayoutChangeListener(startOnLayout)
        val wasPending = pendingEntrance
        pendingEntrance = false
        val running = animator
        animator = null
        running?.cancel()
        incoming?.let(::removeView)
        incoming = null
        incomingKey = null
        if (wasPending && !isAttachedToWindow && currentKey != null &&
            !motionState.wasPresented(currentKey!!)) {
            current.setImageDrawable(null)
            currentKey = null
        }
        resetTransform(current)
    }

    private fun completeTransition() {
        removeCallbacks(startEntranceRunnable)
        removeOnLayoutChangeListener(startOnLayout)
        val running = animator
        animator = null
        running?.cancel()
        pendingEntrance = false
        incoming?.let { next ->
            removeView(current)
            current = next
            currentKey = incomingKey
            incoming = null
            incomingKey = null
        }
        resetTransform(current)
        if (!disposed && current.drawable != null) {
            motionState.presented(currentKey, current.bitmap())
        }
    }

    private fun prepareEntrance(view: ImageView) {
        view.alpha = 0f
        view.scaleX = 0.86f
        view.scaleY = 0.86f
        view.translationY = host.dp(6).toFloat()
    }

    private fun resetTransform(view: ImageView) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }
}
