package com.andsi.airlyrics.app.host

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.andsi.airlyrics.R
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

@Suppress("DEPRECATION")
internal class LyricsEditorBottomSheet(
    private val host: MainActivityUiHost,
    private val closeLineEditor: () -> Boolean,
) : BottomSheetDialog(host, R.style.ThemeOverlay_AirLyrics_LyricsSources) {
    private val managesInsets = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private lateinit var container: FrameLayout
    private lateinit var coordinator: View

    val visibleHeight: Int
        get() = coordinator.height

    fun setEditorContent(view: View) {
        setContentView(view)
        container = requireNotNull(findViewById(MaterialR.id.container))
        coordinator = requireNotNull(findViewById(MaterialR.id.coordinator))
        if (managesInsets) installInsetsViewport()

        behavior.apply {
            maxWidth = host.dp(640)
            maxHeight = (host.resources.displayMetrics.heightPixels * 0.90f).toInt()
            isDraggableOnNestedScroll = false
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        findViewById<View>(MaterialR.id.design_bottom_sheet)?.apply {
            backgroundTintList = null
            background = null
            stateListAnimator = null
            elevation = 0f
            outlineProvider = null
        }
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val softInputMode = if (managesInsets) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            setSoftInputMode(softInputMode)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        container.fitsSystemWindows = !managesInsets
        coordinator.fitsSystemWindows = !managesInsets
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, !managesInsets) }
        ViewCompat.requestApplyInsets(container)
    }

    override fun cancel() {
        if (!closeLineEditor()) super.cancel()
    }

    private fun installInsetsViewport() {
        val viewport = LyricsEditorViewport(context)
        val index = container.indexOfChild(coordinator)
        val layoutParams = coordinator.layoutParams
        container.removeViewAt(index)
        viewport.addView(coordinator, FrameLayout.LayoutParams(-1, -1))
        container.addView(viewport, index, layoutParams)
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            viewport.applyWindowInsets(insets)
            WindowInsetsCompat.CONSUMED
        }
    }
}

private class LyricsEditorViewport(context: Context) : FrameLayout(context) {
    private var safeInsets = Insets.NONE
    private var insetFrameHeight = 0

    fun applyWindowInsets(insets: WindowInsetsCompat) {
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        )
        val frameHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            insets.toWindowInsets()?.frame?.height ?: 0
        } else 0
        if ((safe == safeInsets) && (frameHeight == insetFrameHeight)) return
        safeInsets = safe
        insetFrameHeight = frameHeight
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The frame in the inset event may still be the old size when the window is resized.
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val excludedHeight = if (height > 0) (insetFrameHeight - height).coerceAtLeast(0) else 0
        val bottom = (safeInsets.bottom - excludedHeight).coerceAtLeast(0)
        if ((paddingLeft != safeInsets.left) || (paddingTop != safeInsets.top) ||
            (paddingRight != safeInsets.right) || (paddingBottom != bottom)
        ) {
            updatePadding(left = safeInsets.left, top = safeInsets.top, right = safeInsets.right, bottom = bottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
