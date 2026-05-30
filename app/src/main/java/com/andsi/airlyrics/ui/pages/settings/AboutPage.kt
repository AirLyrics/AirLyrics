package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.changelogItem
import com.andsi.airlyrics.app.getAppVersionName
import com.andsi.airlyrics.app.openUrl
import com.andsi.airlyrics.app.settingsBackHeader
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun createAboutSettingsPage(activity: MainActivity): View = with(activity) {
    val container = com.andsi.airlyrics.ui.components.pageContainer(activity)
    container.addView(settingsBackHeader("关于"))
    container.addView(aboutLogoHeader())
    container.addView(changeLogButton())
    return scroll(activity, container)
}

private fun MainActivity.aboutLogoHeader(): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(18))
        layoutParams = params
        setPadding(0, dp(2), 0, dp(6))

        val easterEggOverlay = EasterEggOverlay(activity)

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = dp(260)

            addView(InteractiveLogoView(activity) {
                easterEggOverlay.onLogoClicked()
            }.apply {
                layoutParams = FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER)
            })

            addView(easterEggOverlay.apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
        })

        addView(bigText(activity, "AirLyrics").apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            addView(statusPill(activity, getAppVersionName(), playing = true).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, dp(8), 0)
                layoutParams = lp
            })

            addView(githubIconButton(activity))
        })
    }
}

private fun MainActivity.githubIconButton(activity: MainActivity): View {
    return FrameLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(32))
        background = GradientDrawable().apply {
            cornerRadius = dp(99).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(1), colorAccentSoft)
        }
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(0.94f)
        setOnClickListener {
            playTinyPulse(this)
            openUrl("https://github.com/AndSi-327/android-floating-lyrics")
        }

        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_air_github)
            setColorFilter(colorAccent)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            contentDescription = "GitHub"
        })
    }
}

private fun MainActivity.changeLogButton(): View {
    val activity = this
    return TextView(activity).apply {
        text = "Change log"
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccent)
        setPadding(dp(28), dp(11), dp(28), dp(11))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, dp(12))
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(99).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(1), colorAccentSoft)
        }
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(0.95f)
        setOnClickListener {
            playTinyPulse(this)
            showUpdateLogDialog()
        }
    }
}

private fun MainActivity.showUpdateLogDialog() {
    showAirDialog(
        title = "更新日志",
        message = null,
        positiveText = "知道了",
        body = {
            addView(changelogItem("关于页改版", "移除了说明味太重的内容，改成更简洁的互动式关于页。"))
            addView(changelogItem("互动 Logo", "把猫趴云放进关于页，点击会有 Q 弹的小动画，可以一直戳。"))
            addView(changelogItem("关于页彩蛋", "连续点击小猫，每 6 下会解锁一段竖排小字，共三段。"))
            addView(changelogItem("逐字歌词策略", "不再联网查找逐字歌词，仅保留本地导入逐字歌词能力。"))
            addView(changelogItem("歌词偏移调节", "支持用户手动调整歌词 offset，并保存到对应歌曲。"))
        }
    )
}

private class InteractiveLogoView(
    activity: MainActivity,
    private val onExtraClick: () -> Unit
) : LinearLayout(activity) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))

        addView(ImageView(activity).apply {
            setImageResource(R.drawable.airlyrics_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        isClickable = true
        isFocusable = true
        setOnClickListener {
            bounce(activity)
            onExtraClick()
        }
    }

    private fun bounce(activity: MainActivity) {
        animate().cancel()
        animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .translationY(activity.dp(3).toFloat())
            .setDuration(55L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .translationY(-activity.dp(4).toFloat())
                    .setDuration(115L)
                    .setInterpolator(OvershootInterpolator(1.55f))
                    .withEndAction {
                        animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setDuration(160L)
                            .setInterpolator(OvershootInterpolator(1.15f))
                            .start()
                    }
                    .start()
            }
            .start()
    }
}

private class EasterEggOverlay(activity: MainActivity) : FrameLayout(activity) {
    private var clickCount = 0
    private val segmentViews = mutableListOf<VerticalMessageSegment>()

    init {
        clipChildren = false
        clipToPadding = false

        val leftGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val rightGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val segment1 = VerticalMessageSegment(
            activity,
            text = activity.localizeText("如果你喜欢这个项目").toString(),
            colors = listOf(
                Color.rgb(255, 244, 250),
                Color.rgb(255, 210, 228)
            )
        )
        val segment2 = VerticalMessageSegment(
            activity,
            text = activity.localizeText("点个star!").toString(),
            colors = listOf(
                Color.rgb(255, 188, 200),
                Color.rgb(255, 152, 170)
            )
        )
        val segment3 = VerticalMessageSegment(
            activity,
            text = activity.localizeText("我会非常非常非常开心的！").toString(),
            colors = listOf(
                Color.rgb(255, 238, 150),
                Color.rgb(145, 203, 255)
            )
        )

        segmentViews += listOf(segment1, segment2, segment3)

        leftGroup.addView(segment1.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        leftGroup.addView(segment2.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = activity.dp(8)
            }
        })
        rightGroup.addView(segment3.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        addView(leftGroup, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP
        ).apply {
            leftMargin = activity.dp(22)
            topMargin = activity.dp(10)
        })

        addView(rightGroup, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).apply {
            rightMargin = activity.dp(22)
            topMargin = activity.dp(10)
        })
    }

    fun onLogoClicked() {
        clickCount += 1
        val unlockedIndex = when {
            clickCount >= 11 -> 2
            clickCount >= 6 -> 1
            clickCount >= 1 -> 0
            else -> -1
        }
        for (index in 0..unlockedIndex) {
            segmentViews.getOrNull(index)?.reveal()
        }
    }
}

private class VerticalMessageSegment(
    activity: MainActivity,
    text: String,
    private val colors: List<Int>
) : LinearLayout(activity) {
    private val charViews = mutableListOf<TextView>()
    private var revealed = false

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        text.forEachIndexed { index, ch ->
            val textView = TextView(activity).apply {
                this.text = ch.toString()
                textSize = 17f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
                setTextColor(colors[index % colors.size])
                alpha = 0f
                translationY = activity.dp(8).toFloat()
                rotation = when (index % 4) {
                    0 -> -7f
                    1 -> 5f
                    2 -> -4f
                    else -> 6f
                }
                setPadding(0, 0, 0, activity.dp(1))
                includeFontPadding = false
                letterSpacing = 0.04f
                setShadowLayer(10f, 0f, 0f, Color.argb(68, 255, 255, 255))
            }
            charViews += textView
            addView(textView)
        }
    }

    fun isRevealed(): Boolean = revealed

    fun reveal() {
        if (revealed) return
        revealed = true
        charViews.forEachIndexed { index, textView ->
            textView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 120L)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        startFloatingJitter(textView, index)
                    }
                })
                .start()
        }
    }

    private fun startFloatingJitter(view: TextView, index: Int) {
        val direction = if (index % 2 == 0) 1f else -1f
        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, 0f - 3f * direction, 0f).apply {
            duration = 1800L + index * 80L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            startDelay = index * 40L
            start()
        }
        ObjectAnimator.ofFloat(view, View.ROTATION, view.rotation, view.rotation + 2f * direction, view.rotation).apply {
            duration = 1600L + index * 60L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            startDelay = index * 45L
            start()
        }
    }
}
