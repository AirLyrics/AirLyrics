package com.andsi.airlyrics.ui.pages.floating

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorDanger
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextStrong

private const val HEX_EDITOR_STATE = "panel.color.hex"
private val HEX_RGB = Regex("#?[0-9a-fA-F]{6}")

internal fun parseFloatingHexColor(value: String): Int? {
    val normalized = value.trim()
    if (!HEX_RGB.matches(normalized)) return null
    return AirColorUtils.opaqueRgb(normalized.removePrefix("#").toInt(16))
}

internal class FloatingHexColorEditor(
    private val host: MainUiHost,
    private val isActive: () -> Boolean,
    private val currentColor: () -> Int,
    private val applyRgb: (Int) -> Unit
) {
    private var window: Dialog? = null

    fun restoreIfNeeded() {
        if (host.interactions.read(HEX_EDITOR_STATE) != null) show()
    }

    fun show() {
        if (!isActive() || window?.isShowing == true) return
        val saved = host.interactions.read(HEX_EDITOR_STATE)
        val initial = saved?.getString("draft") ?: compactColorSummary(currentColor())
        host.interactions.write(HEX_EDITOR_STATE) { putString("draft", initial) }

        val input = AppCompatEditText(host).apply {
            setTag(R.id.interaction_anchor, "floating.color.hex.input")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "#RRGGBB"
            contentDescription = host.getString(R.string.ui_color_hex)
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 16f
            minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
            setPadding(host.dp(12), host.dp(12), host.dp(12), host.dp(12))
            setTextColor(host.colorTextStrong)
            backgroundTintList = null
            highlightColor = androidx.core.graphics.ColorUtils.setAlphaComponent(host.colorAccent, 80)
            setText(initial)
            val start = saved?.getInt("start", 0)?.coerceIn(0, initial.length) ?: 0
            val end = saved?.getInt("end", initial.length)?.coerceIn(start, initial.length) ?: initial.length
            setSelection(start, end)
        }
        val error = TextView(host).apply {
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(host.colorDanger)
            setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, 0)
            setTag(R.id.interaction_anchor, "floating.color.hex.error")
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun showError(invalid: Boolean) {
            error.setText(R.string.ui_color_hex_format)
            error.visibility = if (invalid) View.VISIBLE else View.GONE
            input.background = GradientDrawable().apply {
                cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
                setColor(host.colorSurfaceLight)
                setStroke(host.dp(AirUiTokens.Stroke.Hairline), if (invalid) host.colorDanger else host.colorStroke)
            }
        }
        showError(false)

        fun snapshot() {
            if (!isActive() || host.interactions.read(HEX_EDITOR_STATE) == null) return
            host.interactions.write(HEX_EDITOR_STATE) {
                putString("draft", input.text.toString())
                putInt("start", input.selectionStart.coerceAtLeast(0))
                putInt("end", input.selectionEnd.coerceAtLeast(0))
            }
        }
        host.interactionUi.snapshot(input, ::snapshot)
        input.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { host.interactionUi.forgetSnapshot(input) }
        })
        input.doAfterTextChanged {
            snapshot()
            if (error.isVisible && parseFloatingHexColor(it.toString()) != null) showError(false)
        }
        fun submit() {
            val rgb = parseFloatingHexColor(input.text.toString())
            if (rgb == null) {
                showError(true)
                input.requestFocus()
                return
            }
            if (isActive()) applyRgb(rgb)
            host.interactions.remove(HEX_EDITOR_STATE)
            window?.dismiss()
        }

        window = host.showAirDialog(
            title = host.getString(R.string.ui_color_hex),
            positiveText = null,
            negativeText = null,
            onUserDismiss = { if (isActive()) host.interactions.remove(HEX_EDITOR_STATE) },
            afterUserDismiss = { window = null },
            body = {
                addView(input, LinearLayout.LayoutParams(-1, -2))
                addView(error)
                addView(AdaptiveGridLayout(host, 2, host.dp(8), host.dp(8)).apply {
                    setPadding(0, host.dp(12), 0, 0)
                    addView(actionButton(host, host.getString(R.string.ui_cancel)) {
                        host.interactions.remove(HEX_EDITOR_STATE)
                        window?.cancel()
                    }.apply {
                        id = android.R.id.button2
                        host.styleAirTonalButton(this, AirTonalButtonTone.DEFAULT)
                    })
                    addView(actionButton(host, host.getString(R.string.ui_apply), ::submit).apply {
                        id = android.R.id.button1
                    })
                })
            }
        )
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        input.requestFocus()
        @Suppress("DEPRECATION") // Legacy IME resize is still needed on Android 8–10.
        window?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        input.post {
            if (window?.isShowing == true && isActive() && host.interactions.read(HEX_EDITOR_STATE) != null) {
                window?.window?.let { dialogWindow ->
                    WindowCompat.getInsetsController(dialogWindow, input).show(WindowInsetsCompat.Type.ime())
                }
            }
        }
    }
}
