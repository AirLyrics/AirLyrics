package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
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
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun pageContainer(activity: MainUiHost, animateChanges: Boolean = true): LinearLayout  = with(activity) pageContainer@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (animateChanges) {
            layoutTransition = softLayoutTransition()
        }
        setPadding(dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.PageTop), dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.PageBottom))
    }
}

internal fun scroll(activity: MainUiHost, child: View, animateChildren: Boolean = true): ScrollView  = with(activity) scroll@ {
    return ScrollView(this).apply {
        isFillViewport = false
        addView(child)
        if (animateChildren) {
            post { animateChildrenCascade(activity, child) }
        }
    }
}

internal fun sectionTitle(activity: MainUiHost, title: String, subtitle: String): View  = with(activity) sectionTitle@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl))
        addView(TextView(activity).apply {
            text = title
            textSize = AirUiTokens.TextSize.PageTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = subtitle
            textSize = AirUiTokens.TextSize.Body
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        })
    }
}

internal fun card(activity: MainUiHost, content: LinearLayout.() -> Unit): LinearLayout = with(activity) card@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.CardV), dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.CardV))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        layoutParams = params
        elevation = dp(AirUiTokens.Space.Xxs).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Card).toFloat()
            setColor(colorCard)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        content()
    }
}

internal fun floatingStatusPreviewCard(activity: MainUiHost, content: LinearLayout.() -> Unit): LinearLayout = with(activity) floatingStatusPreviewCard@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(AirUiTokens.Space.Xl))
        layoutParams = params
        elevation = dp(AirUiTokens.Space.Sm).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Preview).toFloat()
            setColor(colorCard)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorAccentSoft)
        }
        content()
    }
}

internal fun actionButton(activity: MainUiHost, text: String, onClick: () -> Unit): TextView = with(activity) actionButton@ {
    return TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(AirUiTokens.Space.Xxl), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(colorAccent)
        }
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        setOnClickListener {
            onClick()
            playTinyPulse(this)
        }
    }
}

internal fun horizontalButtons(activity: MainUiHost, vararg buttons: Pair<String, () -> Unit>): LinearLayout = with(activity) horizontalButtons@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { index, pair ->
            addView(TextView(activity).apply {
                text = pair.first
                gravity = Gravity.CENTER
                textSize = AirUiTokens.TextSize.Button
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                params.setMargins(
                    if (index == 0) 0 else dp(AirUiTokens.Space.Lg),
                    dp(AirUiTokens.Space.Xxl),
                    if (index == buttons.lastIndex) 0 else dp(AirUiTokens.Space.Lg),
                    0
                )
                layoutParams = params
                background = GradientDrawable().apply {
                    cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
                    setColor(colorAccent)
                }
                enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
                setOnClickListener {
                    pair.second()
                    playTinyPulse(this)
                }
            })
        }
    }
}

internal fun settingRow(activity: MainUiHost, name: String, value: String): View  = with(activity) settingRow@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Sm))

        addView(TextView(activity).apply {
            text = name
            textSize = AirUiTokens.TextSize.Button
            setTextColor(colorTextStrong)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })

        addView(TextView(activity).apply {
            text = value
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(colorTextMuted)
        })
    }
}

internal fun statusPill(activity: MainUiHost, text: String, playing: Boolean): TextView  = with(activity) statusPill@ {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.BodySmall
        setTextColor(if (playing) Color.WHITE else colorTextMuted)
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Lg))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
            setColor(if (playing) colorAccent else colorSurfaceLight)
        }
    }
}

internal fun label(activity: MainUiHost, text: String, color: Int): TextView  = with(activity) label@ {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.BodySmall
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl))
    }
}

internal fun bigText(activity: MainUiHost, text: String): TextView  = with(activity) bigText@ {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.Title
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorTextStrong)
    }
}

internal fun normalText(activity: MainUiHost, text: String): TextView  = with(activity) normalText@ {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorText)
        setPadding(0, dp(AirUiTokens.Space.Md), 0, 0)
    }
}

internal fun smallHint(activity: MainUiHost, text: String): TextView  = with(activity) smallHint@ {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.BodySmall
        setTextColor(colorTextMuted)
        setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
    }
}

internal fun spacer(activity: MainUiHost, height: Int): View  = with(activity) spacer@ {
    return View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }
}

internal fun animatePageEnter(activity: MainUiHost, view: View, fromRight: Boolean) = with(activity) animatePageEnter@ {
    val distance = dp(AirUiTokens.Layout.PageEnterDistance).toFloat() * if (fromRight) 1f else -1f
    view.alpha = 0f
    view.translationX = distance
    view.animate()
        .alpha(AirUiTokens.Motion.RestAlpha)
        .translationX(0f)
        .setDuration(AirUiTokens.Motion.PageEnterMs)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

internal fun animateChildrenCascade(activity: MainUiHost, root: View) = with(activity) animateChildrenCascade@ {
    val parent = root as? ViewGroup ?: return
    val delayStep = AirUiTokens.Motion.ChildDelayStepMs
    for (index in 0 until parent.childCount) {
        val child = parent.getChildAt(index)
        child.alpha = 0f
        child.translationY = dp(AirUiTokens.Layout.ChildEnterDistance).toFloat()
        child.animate()
            .alpha(AirUiTokens.Motion.RestAlpha)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(AirUiTokens.Layout.ChildEnterMaxIndex)) * delayStep)
            .setDuration(AirUiTokens.Motion.ChildEnterMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}

internal fun softLayoutTransition(): LayoutTransition {
    return LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setDuration(AirUiTokens.Motion.LayoutChangeMs)
    }
}

@SuppressLint("ClickableViewAccessibility")
internal fun View.enableSoftPressFeedback(pressedScale: Float = AirUiTokens.Motion.DefaultPressScale) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate()
                   .scaleX(pressedScale)
                   .scaleY(pressedScale)
                    .alpha(AirUiTokens.Motion.PressAlpha)
                    .setDuration(AirUiTokens.Motion.PressDownMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate()
                    .scaleX(AirUiTokens.Motion.RestScale)
                    .scaleY(AirUiTokens.Motion.RestScale)
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .setDuration(AirUiTokens.Motion.PressUpMs)
                    .setInterpolator(OvershootInterpolator(AirUiTokens.Motion.OvershootSoft))
                    .start()
            }
        }
        false
    }
}

internal fun playTinyPulse(view: View) {
    view.animate()
        .scaleX(AirUiTokens.Motion.TinyPulseScale)
        .scaleY(AirUiTokens.Motion.TinyPulseScale)
        .setDuration(AirUiTokens.Motion.PulseUpMs)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction {
            view.animate()
                .scaleX(AirUiTokens.Motion.RestScale)
                .scaleY(AirUiTokens.Motion.RestScale)
                .setDuration(AirUiTokens.Motion.PulseDownMs)
                .setInterpolator(OvershootInterpolator(AirUiTokens.Motion.OvershootTiny))
                .start()
        }
        .start()
}
