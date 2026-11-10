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
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Uses the same input window, motion, dimming and result delivery as Spotify ID search. */
class WordLyricsSearchDialog : DialogFragment() {
    private var titleInput: TextInputEditText? = null
    private var artistInput: TextInputEditText? = null
    private var submitted = false
    private var albumInput: TextInputEditText? = null
    private var durationInput: TextInputEditText? = null
    private var optionsExpanded = false
    private var durationHelpVisible = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dark = ThemeSettingsStore.isDark(requireContext())
        val palette = AirLyricsTheme.palette(dark, ThemeSettingsStore.getAccent(requireContext()))
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogTop),
                dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogBottom))
        }
        body.addView(TextView(requireContext()).apply {
            text = getString(R.string.search_word_search)
            textSize = AirUiTokens.TextSize.DialogTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textStrong)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        fun input(label: Int, key: String, action: Int, parent:LinearLayout=body): Pair<TextInputLayout,TextInputEditText> {
            val field = TextInputLayout(requireContext()).apply {
                setHint(label)
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
                highlightColor = ColorUtils.setAlphaComponent(palette.accent,80)
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) textCursorDrawable?.setTint(palette.accent)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = action
                setText(savedInstanceState?.getString(key) ?: arguments?.getString(key).orEmpty())
                setSelection(text?.length ?: 0)
            }
            field.addView(edit)
            parent.addView(field,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(20) })
            return field to edit
        }
        val (titleField,titleEdit) = input(R.string.search_title,TITLE,EditorInfo.IME_ACTION_NEXT)
        val (_,artistEdit) = input(R.string.search_artist,ARTIST,EditorInfo.IME_ACTION_SEARCH)
        titleInput = titleEdit
        artistInput = artistEdit
        val advanced=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
        val more=button(R.string.search_match_options,false,palette).apply {
            backgroundTintList=ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(palette.accent)
            gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4),dp(8),dp(4),dp(8))
        }
        body.addView(more,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        body.addView(advanced,LinearLayout.LayoutParams(-1,-2))
        val (_,albumEdit)=input(R.string.search_album,ALBUM,EditorInfo.IME_ACTION_NEXT,advanced)
        val (durationField,durationEdit)=input(R.string.search_duration_match,DURATION_TEXT,EditorInfo.IME_ACTION_SEARCH,advanced)
        durationField.placeholderText=arguments?.getString(DURATION_TEXT)?.takeIf(String::isNotBlank)
        durationEdit.inputType=InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        durationField.endIconMode=TextInputLayout.END_ICON_CUSTOM
        durationField.setEndIconDrawable(R.drawable.ic_air_info)
        durationField.endIconContentDescription=getString(R.string.search_duration_help_action)
        durationHelpVisible=savedInstanceState?.getBoolean("duration-help")==true
        fun renderDurationHelp() {
            durationField.helperText=if(durationHelpVisible) getString(R.string.search_duration_help) else null
        }
        renderDurationHelp()
        durationField.setEndIconOnClickListener {
            durationHelpVisible=!durationHelpVisible
            renderDurationHelp()
        }
        albumInput=albumEdit
        durationInput=durationEdit
        optionsExpanded=savedInstanceState?.getBoolean("match-options") ?: false
        fun renderOptions() {
            advanced.visibility=if(optionsExpanded) android.view.View.VISIBLE else android.view.View.GONE
            more.setText(if(optionsExpanded) R.string.search_hide_match_options else R.string.search_match_options)
        }
        renderOptions()
        more.setOnClickListener { optionsExpanded=!optionsExpanded;renderOptions() }
        val actions = AdaptiveGridLayout(requireContext(),2,dp(8),dp(8)).apply {
            setPadding(0,dp(20),0,0)
        }
        val cancel = button(R.string.ui_cancel,false,palette).apply { id=android.R.id.button2 }
        val search = button(R.string.ui_search,true,palette).apply {
            id=android.R.id.button1
            setIconResource(R.drawable.ic_air_search)
            iconSize=dp(18)
            iconPadding=dp(8)
            iconTint=ColorStateList.valueOf(palette.onAccent)
        }
        actions.addView(cancel)
        actions.addView(search)
        body.addView(actions,LinearLayout.LayoutParams(-1,-2))
        val registry = (activity as? LyricsSearchActivity)?.interactionUi
        val window = LyricsSearchInputDialog(requireContext(),palette,dark,body,
            registry?.maxShowingDimAmount() ?: 0f,animateEntry=savedInstanceState==null)
        window.setTitle(R.string.search_word_search)
        registry?.register(window,window::dismissImmediately)
        cancel.setOnClickListener { window.cancel() }
        val submit = {
            if(!submitted && !window.isLeaving) {
                val title = titleEdit.text.toString().trim()
                val duration=durationEdit.text.toString().trim()
                val durationMs=parseDuration(duration)
                if(durationMs==null) {
                    optionsExpanded=true
                    renderOptions()
                    durationField.error=getString(R.string.search_duration_invalid)
                    durationEdit.requestFocus()
                } else if(title.isEmpty()) {
                    titleField.error=getString(R.string.search_title_required)
                    titleEdit.requestFocus()
                } else {
                    submitted=true
                    parentFragmentManager.setFragmentResult(RESULT,Bundle(arguments ?: Bundle()).apply {
                        putString(TITLE,title)
                        putString(ARTIST,artistEdit.text.toString().trim())
                        putString(ALBUM,albumEdit.text.toString().trim())
                        putLong(DURATION,durationMs)
                    })
                    window.dismiss()
                }
            }
        }
        search.setOnClickListener { submit() }
        artistEdit.setOnEditorActionListener { _,action,_ ->
            if(action==EditorInfo.IME_ACTION_SEARCH) { submit();true } else false
        }
        return window
    }

    private fun button(label:Int,primary:Boolean,palette:AirLyricsPalette) =
        MaterialButton(requireContext()).apply {
            setText(label)
            isAllCaps=false
            textSize=AirUiTokens.TextSize.Button
            typeface=Typeface.DEFAULT_BOLD
            letterSpacing=0f
            cornerRadius=dp(AirUiTokens.Radius.Md)
            minWidth=0
            minimumWidth=0
            minHeight=dp(48)
            minimumHeight=dp(48)
            insetTop=0
            insetBottom=0
            setPadding(dp(16),dp(12),dp(16),dp(12))
            elevation=0f
            stateListAnimator=null
            backgroundTintList=ColorStateList.valueOf(if(primary) palette.accent else palette.surfaceLight)
            setTextColor(if(primary) palette.onAccent else palette.textStrong)
        }

    override fun onDismiss(dialog:DialogInterface) {
        if(dialog is Dialog) (activity as? LyricsSearchActivity)?.interactionUi?.forget(dialog)
        super.onDismiss(dialog)
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString(TITLE,titleInput?.text?.toString())
        outState.putString(ARTIST,artistInput?.text?.toString())
        outState.putString(ALBUM,albumInput?.text?.toString())
        outState.putString(DURATION_TEXT,durationInput?.text?.toString())
        outState.putBoolean("match-options",optionsExpanded)
        outState.putBoolean("duration-help",durationHelpVisible)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroyView() {
        (dialog as? LyricsSearchInputDialog)?.dismissImmediately()
        titleInput=null
        artistInput=null
        albumInput=null
        durationInput=null
        super.onDestroyView()
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()

    companion object {
        internal fun parseDuration(value:String):Long? {
            if(value.isBlank()) return 0L
            val match=Regex("^(\\d{1,4}):([0-5]\\d)$").matchEntire(value) ?: return null
            val seconds=match.groupValues[1].toLong()*60+match.groupValues[2].toLong()
            return (seconds*1000).takeIf { seconds in 1..86400 }
        }

        const val TAG="word-lyrics-search"
        const val RESULT="word-lyrics-search-result"
        const val TITLE="word-title"
        const val ARTIST="word-artist"
        const val ALBUM="word-album"
        const val DURATION="word-duration"
        const val DURATION_TEXT="word-duration-text"
        const val TARGET="word-target"

        fun create(query:SongIdentity,targetKey:String=query.storageKey())=WordLyricsSearchDialog().apply {
            arguments=Bundle().apply {
                putString(TITLE,query.title)
                putString(ARTIST,query.artist)
                putString(ALBUM,query.album)
                putLong(DURATION,query.durationMs)
                putString(TARGET,targetKey)
                putString(DURATION_TEXT,query.durationMs.takeIf { it>0 }?.let {
                    String.format(java.util.Locale.ROOT,"%d:%02d",it/60000,it/1000%60)
                }.orEmpty())
            }
        }
    }
}
