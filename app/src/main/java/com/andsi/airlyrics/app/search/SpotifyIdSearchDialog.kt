package com.andsi.airlyrics.app.search

import android.app.Dialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.SpotifyTrackId
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** The fragment owns input restoration; the dialog owns motion and the window's lifetime. */
class SpotifyIdSearchDialog : DialogFragment() {
    private var input: TextInputEditText? = null
    private var submitted = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val isDark = ThemeSettingsStore.isDark(requireContext())
        val palette = AirLyricsTheme.palette(isDark, ThemeSettingsStore.getAccent(requireContext()))
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogTop),
                dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogBottom))
        }
        body.addView(TextView(requireContext()).apply {
            text = getString(R.string.search_spotify_id)
            textSize = AirUiTokens.TextSize.DialogTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textStrong)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val field = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.search_spotify_id_input)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = palette.surfaceLight
            setBoxCornerRadii(dp(AirUiTokens.Radius.Sm).toFloat(), dp(AirUiTokens.Radius.Sm).toFloat(),
                dp(AirUiTokens.Radius.Sm).toFloat(), dp(AirUiTokens.Radius.Sm).toFloat())
            boxStrokeWidth = dp(AirUiTokens.Stroke.Hairline)
            boxStrokeWidthFocused = dp(AirUiTokens.Stroke.Selected)
            setBoxStrokeColorStateList(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(palette.accent, palette.stroke)))
            defaultHintTextColor = ColorStateList.valueOf(palette.textMuted)
            hintTextColor = ColorStateList.valueOf(palette.accent)
            setErrorTextColor(ColorStateList.valueOf(palette.danger))
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setEndIconTintList(ColorStateList.valueOf(palette.textMuted))
        }
        val edit = TextInputEditText(field.context).apply {
            setSingleLine()
            textSize = 16f
            minimumHeight = dp(64)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setTextColor(palette.textStrong)
            highlightColor = ColorUtils.setAlphaComponent(palette.accent, 80)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable?.setTint(palette.accent)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(savedInstanceState?.getString(INPUT) ?: arguments?.getString(INPUT).orEmpty())
            setSelection(text?.length ?: 0)
        }
        input = edit
        field.addView(edit)
        body.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        val actions = AdaptiveGridLayout(requireContext(), 2, dp(8), dp(8)).apply {
            setPadding(0, dp(20), 0, 0)
        }
        val cancel = actionButton(R.string.ui_cancel, false, palette).apply { id = android.R.id.button2 }
        val search = actionButton(R.string.ui_search, true, palette).apply {
            id = android.R.id.button1
            setIconResource(R.drawable.ic_air_search)
            iconSize = dp(18)
            iconPadding = dp(8)
            iconTint = ColorStateList.valueOf(palette.onAccent)
        }
        actions.addView(cancel)
        actions.addView(search)
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val registry = (activity as? LyricsSearchActivity)?.interactionUi
        val window = LyricsSearchInputDialog(requireContext(), palette, isDark, body,
            registry?.maxShowingDimAmount() ?: 0f, animateEntry = savedInstanceState == null)
        window.setTitle(R.string.search_spotify_id)
        registry?.register(window, window::dismissImmediately)
        cancel.setOnClickListener { window.cancel() }
        val submit = {
            if (!submitted && !window.isLeaving) {
                val id = SpotifyTrackId.parse(edit.text?.toString())
                if (id == null) {
                    field.error = getString(R.string.search_spotify_id_invalid)
                } else {
                    submitted = true
                    parentFragmentManager.setFragmentResult(RESULT, Bundle().apply { putString(ID, id) })
                    // Let onDismiss remove the fragment after the window's exit has finished.
                    window.dismiss()
                }
            }
        }
        search.setOnClickListener { submit() }
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        return window
    }

    private fun actionButton(label: Int, primary: Boolean, palette: AirLyricsPalette) =
        MaterialButton(requireContext()).apply {
            setText(label)
            isAllCaps = false
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0f
            cornerRadius = dp(AirUiTokens.Radius.Md)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = 0f
            stateListAnimator = null
            backgroundTintList = ColorStateList.valueOf(if (primary) palette.accent else palette.surfaceLight)
            setTextColor(if (primary) palette.onAccent else palette.textStrong)
        }

    override fun onDismiss(dialog: DialogInterface) {
        if (dialog is Dialog) (activity as? LyricsSearchActivity)?.interactionUi?.forget(dialog)
        super.onDismiss(dialog)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(INPUT, input?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        (dialog as? LyricsSearchInputDialog)?.dismissImmediately()
        input = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "spotify-id-search"
        const val RESULT = "spotify-id-search-result"
        const val ID = "spotify-id"
        private const val INPUT = "input"

        fun create(currentId: String?) = SpotifyIdSearchDialog().apply {
            arguments = Bundle().apply { putString(INPUT, currentId) }
        }
    }
}
