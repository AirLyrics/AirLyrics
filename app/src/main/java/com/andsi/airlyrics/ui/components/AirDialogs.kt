package com.andsi.airlyrics.ui.components

import com.andsi.airlyrics.R

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.andsi.airlyrics.ui.insets.remainingTopSystemInset
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.design.tokens.AirUiTokens

private const val DEFAULT_POSITIVE_TEXT = "__airlyrics_default_positive__"

internal fun MainUiHost.showAirInfoDialog(
    title: String,
    message: String,
    buttonText: String? = null
): Dialog {
    return showAirDialog(
        title = title,
        message = message,
        positiveText = buttonText ?: getString(R.string.ui_ok)
    )
}

internal fun MainUiHost.showAirConfirmDialog(
    title: String,
    message: String,
    positiveText: String,
    negativeText: String? = null,
    onPositive: () -> Unit
): Dialog {
    return showAirDialog(
        title = title,
        message = message,
        positiveText = positiveText,
        negativeText = negativeText ?: getString(R.string.ui_cancel),
        onPositive = onPositive
    )
}

@Suppress("DEPRECATION")
internal fun MainUiHost.showAirDialog(
    title: String,
    message: String? = null,
    positiveText: String? = DEFAULT_POSITIVE_TEXT,
    negativeText: String? = null,
    body: (LinearLayout.() -> Unit)? = null,
    onPositive: () -> Unit = {}
): Dialog {
    val host = this
    val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogTop), dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogBottom))
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Dialog).toFloat()
            setColor(colorCard)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }

        addView(TextView(this@showAirDialog).apply {
            text = title
            textSize = AirUiTokens.TextSize.DialogTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })

        if (!message.isNullOrBlank()) {
            addView(TextView(this@showAirDialog).apply {
                text = message
                textSize = AirUiTokens.TextSize.Body
                setTextColor(colorTextMuted)
                setLineSpacing(dp(AirUiTokens.Space.Xs).toFloat(), 1f)
                setPadding(0, dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), 0, dp(AirUiTokens.Space.Sm))
            })
        }

        body?.invoke(this)

        val resolvedPositiveText = if (positiveText == DEFAULT_POSITIVE_TEXT) getString(R.string.ui_ok) else positiveText
        if (!resolvedPositiveText.isNullOrBlank() || !negativeText.isNullOrBlank()) {
            addView(LinearLayout(this@showAirDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(AirUiTokens.Space.CardV), 0, 0)

                if (!negativeText.isNullOrBlank()) {
                    addView(dialogButton(negativeText, primary = false) {
                        dialog.dismiss()
                    })
                }

                if (!resolvedPositiveText.isNullOrBlank()) {
                    addView(dialogButton(resolvedPositiveText, primary = true) {
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

    val rootPadding = dp(AirUiTokens.Space.CardH)
    val root = FrameLayout(this).apply {
        setPadding(rootPadding, rootPadding, rootPadding, rootPadding)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val topInset = view.remainingTopSystemInset(safeInsets.top)
            view.setPadding(
                rootPadding + safeInsets.left,
                rootPadding + topInset,
                rootPadding + safeInsets.right,
                rootPadding + safeInsets.bottom
            )
            insets
        }
        addView(outer)
    }

    dialog.window?.applyAirDialogSystemBars(host)
    dialog.setContentView(root)
    dialog.setOnShowListener {
        dialog.window?.apply {
            applyAirDialogSystemBars(host)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(AirUiTokens.Layout.DialogDimAmount)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.post {
            ViewCompat.requestApplyInsets(root)
        }
    }
    dialog.show()
    return dialog
}

@Suppress("DEPRECATION")
private fun Window.applyAirDialogSystemBars(host: MainUiHost) {
    setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
    WindowCompat.setDecorFitsSystemWindows(this, false)
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(this, decorView).apply {
        val useDarkIcons = !host.isDarkTheme()
        isAppearanceLightStatusBars = useDarkIcons
        isAppearanceLightNavigationBars = useDarkIcons
    }
}

private fun MainUiHost.dialogButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit
): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.Body
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else colorTextStrong)
        setPadding(dp(AirUiTokens.Space.CardV), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.CardV), dp(AirUiTokens.Space.Xxl))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(AirUiTokens.Space.Xl), 0, 0, 0)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
            if (primary) {
                setColor(colorAccent)
            } else {
                setColor(colorSurfaceLight)
                setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
            }
        }
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener { onClick() }
    }
}
