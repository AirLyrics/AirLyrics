package com.andsi.airlyrics.ui.components

import android.animation.LayoutTransition
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainActivity.pageContainer(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutTransition = softLayoutTransition()
        setPadding(dp(20), dp(6), dp(20), dp(24))
    }
}

internal fun MainActivity.scroll(child: View): ScrollView {
    return ScrollView(this).apply {
        isFillViewport = false
        addView(child)
        post { animateChildrenCascade(child) }
    }
}

internal fun MainActivity.sectionTitle(title: String, subtitle: String): View {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))
        addView(TextView(this@sectionTitle).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(this@sectionTitle).apply {
            text = subtitle
            textSize = 14f
            setTextColor(colorTextMuted)
            setPadding(0, dp(4), 0, 0)
        })
    }
}

internal fun MainActivity.card(content: LinearLayout.() -> Unit): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(12))
        layoutParams = params
        elevation = dp(2).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(colorCard)
            setStroke(dp(1), colorStroke)
        }
        content()
    }
}

internal fun MainActivity.floatingStatusPreviewCard(content: LinearLayout.() -> Unit): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(8))
        layoutParams = params
        elevation = dp(4).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(colorCard)
            setStroke(dp(1), colorAccentSoft)
        }
        content()
    }
}

internal fun MainActivity.actionButton(text: String, onClick: () -> Unit): TextView {
    return TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(10), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(colorAccent)
        }
        enableSoftPressFeedback(0.97f)
        setOnClickListener {
            onClick()
            playTinyPulse(this)
        }
    }
}

internal fun MainActivity.horizontalButtons(vararg buttons: Pair<String, () -> Unit>): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { index, pair ->
            addView(TextView(this@horizontalButtons).apply {
                text = pair.first
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                params.setMargins(
                    if (index == 0) 0 else dp(6),
                    dp(10),
                    if (index == buttons.lastIndex) 0 else dp(6),
                    0
                )
                layoutParams = params
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(colorAccent)
                }
                enableSoftPressFeedback(0.96f)
                setOnClickListener {
                    pair.second()
                    playTinyPulse(this)
                }
            })
        }
    }
}

internal fun MainActivity.settingRow(name: String, value: String): View {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))

        addView(TextView(this@settingRow).apply {
            text = name
            textSize = 15f
            setTextColor(colorTextStrong)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })

        addView(TextView(this@settingRow).apply {
            text = value
            textSize = 13f
            setTextColor(colorTextMuted)
        })
    }
}

internal fun MainActivity.statusPill(text: String, playing: Boolean): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(if (playing) Color.WHITE else colorTextMuted)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(12), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = dp(99).toFloat()
            setColor(if (playing) colorAccent else colorSurfaceLight)
        }
    }
}

internal fun MainActivity.label(text: String, color: Int): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        setPadding(0, 0, 0, dp(8))
    }
}

internal fun MainActivity.bigText(text: String): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorTextStrong)
    }
}

internal fun MainActivity.normalText(text: String): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(colorText)
        setPadding(0, dp(5), 0, 0)
    }
}

internal fun MainActivity.smallHint(text: String): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(colorTextMuted)
        setPadding(0, dp(8), 0, 0)
    }
}

internal fun MainActivity.spacer(height: Int): View {
    return View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }
}

internal fun MainActivity.animatePageEnter(view: View, fromRight: Boolean) {
    val distance = dp(26).toFloat() * if (fromRight) 1f else -1f
    view.alpha = 0f
    view.translationX = distance
    view.animate()
        .alpha(1f)
        .translationX(0f)
        .setDuration(230L)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

internal fun MainActivity.animateChildrenCascade(root: View) {
    val parent = root as? ViewGroup ?: return
    val delayStep = 24L
    for (index in 0 until parent.childCount) {
        val child = parent.getChildAt(index)
        child.alpha = 0f
        child.translationY = dp(12).toFloat()
        child.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(8)) * delayStep)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}

internal fun softLayoutTransition(): LayoutTransition {
    return LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setDuration(170L)
    }
}

internal fun View.enableSoftPressFeedback(pressedScale: Float = 0.97f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate()
                    .scaleX(pressedScale)
                    .scaleY(pressedScale)
                    .alpha(0.88f)
                    .setDuration(70L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(150L)
                    .setInterpolator(OvershootInterpolator(0.52f))
                    .start()
            }
        }
        false
    }
}

internal fun playTinyPulse(view: View) {
    view.animate()
        .scaleX(1.025f)
        .scaleY(1.025f)
        .setDuration(80L)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .setInterpolator(OvershootInterpolator(0.48f))
                .start()
        }
        .start()
}
