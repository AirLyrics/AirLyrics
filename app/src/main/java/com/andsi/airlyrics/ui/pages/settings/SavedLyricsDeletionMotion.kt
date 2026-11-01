package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.ListView
import androidx.core.graphics.createBitmap
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.MainUiHost
import kotlin.math.abs

/** FLIP the surviving visible rows while snapshots of the deleted rows leave the viewport. */
internal class SavedLyricsDeletionMotion(
    private val host: MainUiHost,
    private val container: ViewGroup,
    private val list: ListView,
    private val adapter: SavedLyricsListAdapter
) {
    private data class MovingRow(val view: View, val offset: Float)
    private data class Ghost(val view: ImageView, val bitmap: Bitmap)

    private var animator: ValueAnimator? = null
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private val moving = mutableListOf<MovingRow>()
    private val ghosts = mutableListOf<Ghost>()
    private var onSettled: ((Boolean) -> Unit)? = null
    val isActive: Boolean get() = animator != null || preDraw != null

    fun cancel() {
        preDraw?.let { if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(it) }
        preDraw = null
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        settle(false)
    }

    fun submit(items: List<LocalLyricsUiItem>, deletedKeys: Set<String>, settled: (Boolean) -> Unit): Boolean {
        cancel()
        val removed = adapter.keys.toSet().intersect(deletedKeys) - items.mapTo(mutableSetOf()) { it.selectionKey() }
        if (removed.isEmpty() || !ValueAnimator.areAnimatorsEnabled() || !list.isAttachedToWindow ||
            list.width == 0 || list.height == 0
        ) {
            adapter.submit(items)
            return false
        }

        val oldTops = mutableMapOf<String, Int>()
        val attached = adapter.attachedItems(list)
        val firstKey = attached.firstOrNull()?.key
        val firstTop = attached.firstOrNull()?.view?.top ?: 0
        val viewport = Rect(0, 0, list.width, list.height)
        container.offsetDescendantRectToMyCoords(list, viewport)
        attached.forEach { bound ->
            val row = bound.view
            val bounds = Rect(0, 0, row.width, row.height)
            container.offsetDescendantRectToMyCoords(row, bounds)
            oldTops[bound.key] = bounds.top + row.translationY.toInt()
            val visibleTop = bounds.top.coerceAtLeast(viewport.top)
            val visibleBottom = bounds.bottom.coerceAtMost(viewport.bottom)
            if (bound.key in removed && row.width > 0 && visibleBottom > visibleTop) {
                // Only visible deleted rows are copied; the data change is never held for the exit.
                val bitmap = createBitmap(row.width, visibleBottom - visibleTop, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.translate(0f, (bounds.top - visibleTop).toFloat())
                row.draw(canvas)
                val image = ImageView(host).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_XY
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    isClickable = false
                }
                container.overlay.add(image)
                image.layout(bounds.left, visibleTop, bounds.right, visibleBottom)
                ghosts += Ghost(image, bitmap)
            }
        }
        adapter.submit(items)
        val anchor = items.indexOfFirst { it.selectionKey() == firstKey }
        if (anchor >= 0) list.setSelectionFromTop(anchor, firstTop)
        if (oldTops.isEmpty()) {
            settle(false)
            return false
        }
        onSettled = settled
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(this)
                preDraw = null
                adapter.attachedItems(list).forEach { bound ->
                    val previous = oldTops[bound.key] ?: return@forEach
                    val bounds = Rect(0, 0, bound.view.width, bound.view.height)
                    container.offsetDescendantRectToMyCoords(bound.view, bounds)
                    val delta = (previous - bounds.top).toFloat()
                    if (abs(delta) > 0.5f) {
                        bound.view.translationY = delta
                        moving += MovingRow(bound.view, delta)
                    }
                }
                if (moving.isEmpty() && ghosts.isEmpty()) {
                    settle(true)
                } else {
                    start()
                }
                return true
            }
        }
        preDraw = listener
        list.viewTreeObserver.addOnPreDrawListener(listener)
        return true
    }

    private fun start() {
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 245L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                moving.forEach { row -> row.view.translationY = row.offset * (1f - progress) }
                val exit = (progress / 0.78f).coerceIn(0f, 1f)
                ghosts.forEach { ghost ->
                    ghost.view.alpha = 1f - exit
                    ghost.view.translationX = -host.dp(9) * exit
                    ghost.view.scaleX = 1f - 0.025f * exit
                    ghost.view.scaleY = ghost.view.scaleX
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) {
                        animator = null
                        settle(true)
                    }
                }
            })
        }
        animator = animation
        animation.start()
    }

    private fun settle(completed: Boolean) {
        moving.forEach { it.view.translationY = 0f }
        moving.clear()
        ghosts.forEach { ghost ->
            container.overlay.remove(ghost.view)
            ghost.bitmap.recycle()
        }
        ghosts.clear()
        onSettled?.invoke(completed)
        onSettled = null
    }
}
