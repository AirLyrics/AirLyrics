package com.andsi.airlyrics.ui.state

import android.annotation.SuppressLint
import androidx.core.view.isGone
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.core.widget.NestedScrollView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import kotlin.math.roundToInt

/** Structural keys are stable for static cards; dynamic rows supply their own business keys. */
internal fun View.markInteractionAnchors(prefix: String) {
    val ownKey = getTag(R.id.interaction_anchor) as? String ?: prefix
    setTag(R.id.interaction_anchor, ownKey)
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).markInteractionAnchors("$ownKey/$i")
}

internal fun View.findInteractionScroll(): View? {
    if (this is ScrollView || this is NestedScrollView) return this
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findInteractionScroll()?.let { return it }
    return null
}

// The touch observer returns false; the scroll view retains its normal click handling.
@SuppressLint("ClickableViewAccessibility")
internal fun MainUiHost.bindInteractionScroll(scroll: View, key: String, onInteraction: () -> Unit = {}): () -> Unit {
    val group = scroll as? ViewGroup ?: return {}
    val density = resources.displayMetrics.density
    val saved = interactions.read(key)
    var restoring = true
    fun isLoading(view: View): Boolean = view.getTag(R.id.interaction_loading) == true ||
        (view is ViewGroup && (0 until view.childCount).any { isLoading(view.getChildAt(it)) })
    fun anchors(root: View): List<View> = buildList {
        if (root.isGone) return@buildList
        if (root.getTag(R.id.interaction_anchor) != null) add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(anchors(root.getChildAt(i)))
    }
    fun top(view: View): Int {
        var result = view.top
        var parent = view.parent as? View
        while (parent != null && parent !== scroll) {
            result += parent.top - parent.scrollY
            parent = parent.parent as? View
        }
        return result
    }
    fun capture() {
        if (restoring || !scroll.isAttachedToWindow) return
        if (key.startsWith("panel.") && interactions.panel == null) return
        if (key.startsWith("formatGuide.") && interactions.read("formatGuide") == null) return
        if (key.startsWith("aux.") && interactions.read(key.substringBeforeLast('.')) == null) return
        val content = group.getChildAt(0) ?: return
        val candidates = anchors(content).filter { it.height > 0 && top(it) <= scroll.scrollY }
        val anchor = candidates.maxByOrNull { top(it) }
        interactions.write(key) {
            putFloat("y", scroll.scrollY / density)
            putString("anchor", anchor?.getTag(R.id.interaction_anchor) as? String)
            putFloat("offset", (scroll.scrollY - (anchor?.let(::top) ?: 0)) / density)
        }
    }
    scroll.setOnScrollChangeListener { _, _, _, _, _ ->
        if (!restoring) onInteraction()
        capture()
    }
    interactionUi.snapshot(scroll, ::capture)
    val restoreListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!restoring || isLoading(group)) return@OnGlobalLayoutListener
        val content = group.getChildAt(0)
        val anchor = content?.let(::anchors)?.find { it.getTag(R.id.interaction_anchor) == saved?.getString("anchor") }
        val y = if (anchor != null) top(anchor) + ((saved?.getFloat("offset") ?: 0f) * density).roundToInt()
            else ((saved?.getFloat("y") ?: 0f) * density).roundToInt()
        scroll.scrollTo(0, y.coerceIn(0, ((content?.height ?: 0) - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)))
        restoring = false
    }
    scroll.viewTreeObserver.addOnGlobalLayoutListener(restoreListener)
    scroll.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) restoring = false
        false
    }
    val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            interactionUi.forgetSnapshot(scroll)
            scroll.viewTreeObserver.removeOnGlobalLayoutListener(restoreListener)
        }
    }
    scroll.addOnAttachStateChangeListener(detachListener)
    return {
        interactionUi.forgetSnapshot(scroll)
        scroll.viewTreeObserver.removeOnGlobalLayoutListener(restoreListener)
        scroll.removeOnAttachStateChangeListener(detachListener)
        scroll.setOnScrollChangeListener(null)
        scroll.setOnTouchListener(null)
    }
}
