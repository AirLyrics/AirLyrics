package com.andsi.airlyrics.ui.components

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainActivity.showAirInfoDialog(
    title: String,
    message: String,
    buttonText: String = "知道了"
): Dialog {
    return showAirDialog(
        title = title,
        message = message,
        positiveText = buttonText
    )
}

internal fun MainActivity.showAirConfirmDialog(
    title: String,
    message: String,
    positiveText: String,
    negativeText: String = "取消",
    onPositive: () -> Unit
): Dialog {
    return showAirDialog(
        title = title,
        message = message,
        positiveText = positiveText,
        negativeText = negativeText,
        onPositive = onPositive
    )
}

internal fun MainActivity.showAirDialog(
    title: String,
    message: String? = null,
    positiveText: String? = "知道了",
    negativeText: String? = null,
    body: (LinearLayout.() -> Unit)? = null,
    onPositive: () -> Unit = {}
): Dialog {
    val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(20), dp(22), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(colorCard)
            setStroke(dp(1), colorStroke)
        }

        addView(TextView(this@showAirDialog).apply {
            text = localizeText(title)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })

        if (!message.isNullOrBlank()) {
            addView(TextView(this@showAirDialog).apply {
                text = localizeText(message)
                textSize = 14f
                setTextColor(colorTextMuted)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(12), 0, dp(4))
            })
        }

        body?.invoke(this)

        if (!positiveText.isNullOrBlank() || !negativeText.isNullOrBlank()) {
            addView(LinearLayout(this@showAirDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)

                if (!negativeText.isNullOrBlank()) {
                    addView(dialogButton(negativeText, primary = false) {
                        dialog.dismiss()
                    })
                }

                if (!positiveText.isNullOrBlank()) {
                    addView(dialogButton(positiveText, primary = true) {
                        onPositive()
                        dialog.dismiss()
                    })
                }
            })
        }
    }

    val outer = ScrollView(this).apply {
        isFillViewport = false
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        addView(panel)
    }

    val root = FrameLayout(this).apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(outer)
    }

    dialog.setContentView(root)
    dialog.setOnShowListener {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.28f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    dialog.show()
    return dialog
}

private fun MainActivity.dialogButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit
): TextView {
    return TextView(this).apply {
        this.text = localizeText(text)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else colorTextStrong)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(8), 0, 0, 0)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(99).toFloat()
            if (primary) {
                setColor(colorAccent)
            } else {
                setColor(colorSurfaceLight)
                setStroke(dp(1), colorStroke)
            }
        }
        enableSoftPressFeedback(0.94f)
        setOnClickListener { onClick() }
    }
}
