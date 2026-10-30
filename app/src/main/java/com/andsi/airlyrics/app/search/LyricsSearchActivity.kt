package com.andsi.airlyrics.app.search

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.*
import com.andsi.airlyrics.i18n.lyrics.localizedLyricsLookupMessage
import com.andsi.airlyrics.i18n.lyrics.localizedPlainLyricsProviderName
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.search.*
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** A dedicated window gives the keyboard, candidates and preview their own lifecycle. */
class LyricsSearchActivity : AppCompatActivity() {
    private lateinit var session: LyricsSearchSession
    private val palette by lazy { AirLyricsTheme.palette(ThemeSettingsStore.isDark(this),ThemeSettingsStore.getAccent(this)) }
    private lateinit var titleInput: TextInputEditText
    private lateinit var artistInput: TextInputEditText
    private lateinit var albumInput: TextInputEditText
    private lateinit var albumField: TextInputLayout
    private lateinit var form: LinearLayout
    private lateinit var results: LyricsSourcePages
    private lateinit var appBar: AppBarLayout
    private lateinit var header: LinearLayout
    private lateinit var queryScroll: NestedScrollView
    private lateinit var previewPanel: LinearLayout
    private var pendingSourceFocus = false
    private lateinit var sourcesButton: MaterialButton
    private lateinit var headingTitle: TextView
    private lateinit var headingScroll: HorizontalScrollView
    private lateinit var previewDetails: TextView
    private lateinit var previewRetry: MaterialButton
    private lateinit var preview: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var targetText: TextView
    private lateinit var feedback: TextView
    private lateinit var loading: LyricsLoadingView
    private lateinit var switchButton: MaterialButton
    private lateinit var searchButton: MaterialButton
    private val queryFields = mutableMapOf<SearchQueryField,TextInputLayout>()
    private val resetVisible = mutableMapOf<SearchQueryField,Boolean>()
    private lateinit var applyButton: MaterialButton
    private var updating=false
    private var shownResults: Map<PlainLyricsSearchSource,SourceResults>?=null
    private var shownPreview: SearchPreview?=null
    private var inPreview=false
    private var currentSong: SongIdentity?=null
    private var confirmation: AlertDialog?=null
    private var confirmationRevision: String?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial=readTarget(savedInstanceState ?: intent.extras) ?: run { finish(); return }
        val app=applicationContext
        session=ViewModelProvider(this,object: ViewModelProvider.Factory {
            override fun <T: ViewModel> create(modelClass: Class<T>): T {
                val service=ManualLyricsSearch(app)
                val access=object: LyricsSearchAccess {
                    override fun search(source:PlainLyricsSearchSource,query:SongIdentity,token:LyricsLookupCancellationToken)=service.search(source,query,token)
                    override fun resolveInternationalTitle(query:SongIdentity,token:LyricsLookupCancellationToken)=service.resolveInternationalTitle(query,token)
                    override fun fetch(candidate:LyricsCandidate,target:SongIdentity,token:LyricsLookupCancellationToken)=service.fetch(candidate,target,token)
                    override fun protectedRevision(target:SongIdentity)=service.protectedRevision(target)
                    override fun apply(target:SongIdentity,result:LyricsProviderResult,token:LyricsLookupCancellationToken,approvedRevision:String?)=service.apply(target,result,token,approvedRevision)
                }
                @Suppress("UNCHECKED_CAST")
                return LyricsSearchSession(initial,LyricsSettingsStore.getPlainLyricsSearchSources(app).toSet(),access) as T
            }
        })[LyricsSearchSession::class.java]
        supportFragmentManager.setFragmentResultListener(LyricsSourcesSheet.RESULT,this) { _,result ->
            val selected=result.getStringArrayList(LyricsSourcesSheet.KEYS).orEmpty()
                .mapNotNull(PlainLyricsSearchSource::fromKeyOrNull)
                .filter { it in PlainLyricsSearchSource.onlineSources }.toSet()
            if(selected.isNotEmpty()) {
                session.setSources(selected)
            }
        }
        val initialScroll = if (session.state.value.previewCandidate != null) session.previewScroll else session.resultsScroll
        buildUi()
        render(session.state.value)
        scroll.post {
            if (session.state.value.previewCandidate != null) scroll.scrollTo(0, initialScroll)
            else if (session.state.value.results.isNotEmpty()) appBar.setExpanded(initialScroll == 0, false)
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.state.collect(::render) }
                launch {
                    while (true) {
                        currentSong=CurrentMediaReader.readSelectedCurrentMedia(this@LyricsSearchActivity,
                            MediaSourceStore.getSelectedPackage(this@LyricsSearchActivity))?.toSongIdentity()
                        renderTarget(session.state.value)
                        delay(1.seconds)
                    }
                }
            }
        }
    }

    private fun navigateBack() {
        val state = session.state.value
        if (state.applying) return
        if (ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            WindowCompat.getInsetsController(window,window.decorView).hide(WindowInsetsCompat.Type.ime())
            currentFocus?.clearFocus()
            return
        }
        if (state.previewCandidate != null || state.preview != null || state.previewLoading) {
            session.backToResults()
        } else if (state.results.isNotEmpty() && (pendingSourceFocus || session.resultsScroll > 0 || appBar.top < 0)) {
            // Returning from results reveals the query without discarding any source/page state.
            pendingSourceFocus=false
            appBar.setExpanded(true,ValueAnimator.areAnimatorsEnabled())
        } else {
            finish()
        }
    }

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).apply {
            isAppearanceLightStatusBars=!ThemeSettingsStore.isDark(this@LyricsSearchActivity)
            isAppearanceLightNavigationBars=isAppearanceLightStatusBars
        }
        val panel=CoordinatorLayout(this).apply {
            setPadding(dp(20),0,dp(20),0)
            setBackgroundColor(palette.background)
        }
        appBar=AppBarLayout(this).apply {
            setBackgroundColor(palette.background)
            elevation=0f
            stateListAnimator=null
        }
        header=column().apply { setPadding(0,dp(12),0,0) }
        appBar.addView(header,AppBarLayout.LayoutParams(-1,-2).apply {
            scrollFlags=AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
        })
        panel.addView(appBar,CoordinatorLayout.LayoutParams(-1,-2).apply {
            behavior=AppBarLayout.Behavior()
        })
        queryScroll=NestedScrollView(this).apply {
            isFillViewport=true
            isNestedScrollingEnabled=false
            visibility=View.GONE
        }
        panel.addView(queryScroll,CoordinatorLayout.LayoutParams(-1,-1))
        appBar.addOnOffsetChangedListener { _,offset ->
            if(!inPreview && session.state.value.results.isNotEmpty()) session.resultsScroll=-offset
        }
        val panelParams = FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
        val root=object: FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
                val wide=resources.configuration.screenWidthDp>=840
                panelParams.width = if (wide) dp(760) else LayoutParams.MATCH_PARENT
                panelParams.height = if (wide) (MeasureSpec.getSize(heightMeasureSpec) * 0.9).toInt() else LayoutParams.MATCH_PARENT
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(palette.surface)
            addView(panel, panelParams)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom)
            insets
        }
        val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        heading.addView(AppCompatImageButton(this).apply {
            setImageResource(R.drawable.ic_air_arrow_back)
            imageTintList = ColorStateList.valueOf(palette.accent)
            background = null
            contentDescription = getString(R.string.search_back)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) }
            setOnClickListener { navigateBack() }
        })
        headingTitle=text(getString(R.string.search_lyrics),22f).apply {
            setTypeface(typeface,Typeface.BOLD)
            setSingleLine()
        }
        headingScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false
            isHorizontalFadingEdgeEnabled=true
            setFadingEdgeLength(dp(16))
            addView(headingTitle,FrameLayout.LayoutParams(-2,-2))
        }
        heading.addView(headingScroll,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(heading)
        targetText=text("")
        header.addView(targetText)
        switchButton=button(R.string.search_switch_target) { currentSong?.let(session::switchTarget) }
        header.addView(switchButton)
        form=column().apply { setPadding(0,0,0,dp(12)) }
        header.addView(form)
        titleInput=input(R.string.search_title,SearchQueryField.TITLE,R.string.search_restore_title).also { form.addView(it.second) }.first
        artistInput=input(R.string.search_artist,SearchQueryField.ARTIST,R.string.search_restore_artist).also { form.addView(it.second) }.first
        form.addView(textAction(R.string.search_more) { session.toggleMore() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        val album=input(R.string.search_album,SearchQueryField.ALBUM,R.string.search_restore_album);albumInput=album.first;albumField=album.second
        form.addView(albumField)
        sourcesButton=textAction(R.string.search_sources) {
            if (supportFragmentManager.findFragmentByTag(LyricsSourcesSheet.TAG) == null) {
                LyricsSourcesSheet.create(session.state.value.sources,session.sourceOrder)
                    .show(supportFragmentManager,LyricsSourcesSheet.TAG)
            }
        }
        form.addView(sourcesButton)
        searchButton=button(R.string.ui_search) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(titleInput.windowToken,0)
            titleInput.clearFocus();artistInput.clearFocus();albumInput.clearFocus()
            pendingSourceFocus=true
            session.search()
            // Also handle repeated searches whose loading state is unchanged.
            appBar.post { focusSourcesIfReady() }
        }.apply {
            setIconResource(R.drawable.ic_air_search)
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            setTextColor(palette.onAccent)
            iconTint = ColorStateList.valueOf(palette.onAccent)
        }
        form.addView(searchButton)
        val watcher=object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(!updating) session.editQuery(titleInput.text.toString(),artistInput.text.toString(),albumInput.text.toString())
            }
            override fun afterTextChanged(s:Editable?) {}
        }
        listOf(titleInput,artistInput,albumInput).forEach {
            it.addTextChangedListener(watcher)
            it.setOnEditorActionListener { _,action,_ -> if(action==EditorInfo.IME_ACTION_SEARCH) { searchButton.performClick();true } else false }
        }
        feedback=text("").apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        header.addView(feedback)
        loading=LyricsLoadingView(this,palette)
        header.addView(loading,LinearLayout.LayoutParams(-1,-2))
        results=LyricsSourcePages(this,palette,session,::errorText)
        panel.addView(results,CoordinatorLayout.LayoutParams(-1,-1).apply {
            behavior=AppBarLayout.ScrollingViewBehavior()
        })
        previewPanel=column().apply { setPadding(0,0,0,dp(12)) }
        panel.addView(previewPanel,CoordinatorLayout.LayoutParams(-1,-1).apply {
            behavior=AppBarLayout.ScrollingViewBehavior()
        })
        scroll=ScrollView(this).apply { isFillViewport=true }
        preview=column()
        previewDetails=text("",15f).apply { setTextColor(palette.textMuted) }
        preview.addView(previewDetails)
        val previewBody=column()
        preview.addView(previewBody)
        scroll.addView(preview)
        // The header survives loading, empty and failure states.
        preview=previewBody
        previewPanel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        previewRetry=textAction(R.string.search_retry) {
            session.state.value.previewCandidate?.let(session::preview)
        }
        previewPanel.addView(previewRetry)
        applyButton=button(R.string.search_apply) { session.apply() }.apply {
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            setTextColor(palette.onAccent)
        }
        previewPanel.addView(applyButton)
        scroll.setOnScrollChangeListener { _,_,y,_,_ -> if(inPreview) session.previewScroll=y }
        setContentView(root)
    }

    private fun render(state:LyricsSearchState) {
        if(state.saved) { finish();return }
        updating=true
        listOf(titleInput to state.query.title,artistInput to state.query.artist,albumInput to state.query.album).forEach { (view,value) ->
            if(view.text.toString()!=value) {
                view.setText(value)
                view.setSelection(value.length)
            }
            view.isEnabled=!state.applying
        }
        sourcesButton.text=getString(R.string.search_sources_selected,state.sources.joinToString(" / ") { localizedPlainLyricsProviderName(it.key) })
        updating=false
        albumField.visibility=if(state.moreConditions) View.VISIBLE else View.GONE
        val showPreview=state.previewCandidate!=null
        val changed=inPreview!=showPreview
        inPreview=showPreview
        form.visibility=if(!showPreview) View.VISIBLE else View.GONE
        renderResetIcons(state)
        previewPanel.visibility=if(showPreview) View.VISIBLE else View.GONE
        // Before searching there is no nested result list to bring a collapsed header back.
        // Keep the form in a bounded scroll view, including when the keyboard reduces the viewport.
        val queryOnly=!showPreview && state.results.isEmpty()
        val headerParent=if(queryOnly) queryScroll else appBar
        if(header.parent !== headerParent) {
            (header.parent as? ViewGroup)?.removeView(header)
            if(queryOnly) {
                queryScroll.addView(header,FrameLayout.LayoutParams(-1,-2))
                session.resultsScroll=0
                pendingSourceFocus=false
            } else {
                appBar.addView(header,AppBarLayout.LayoutParams(-1,-2))
            }
            appBar.setExpanded(true,false)
        }
        queryScroll.visibility=if(queryOnly) View.VISIBLE else View.GONE
        appBar.visibility=if(queryOnly) View.GONE else View.VISIBLE
        val headerParams=header.layoutParams as? AppBarLayout.LayoutParams
        val flags=if(showPreview) 0 else AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
        if(headerParams!=null && headerParams.scrollFlags!=flags) {
            headerParams.scrollFlags=flags
            header.layoutParams=headerParams
        }
        results.visibility=if(!showPreview && state.results.isNotEmpty()) View.VISIBLE else View.GONE
        scroll.visibility=if(showPreview) View.VISIBLE else View.GONE
        if(changed) {
            val restoreCollapsed=session.resultsScroll>0
            appBar.setExpanded(showPreview || !restoreCollapsed,false)
            val entering=if(showPreview) scroll else results
            entering.animate().cancel()
            entering.alpha=1f
            entering.translationY=0f
            if(ValueAnimator.areAnimatorsEnabled()) {
                entering.alpha=0f
                entering.translationY=dp(if(showPreview) 12 else -12).toFloat()
                entering.animate().alpha(1f).translationY(0f).setDuration(200).start()
            }
            if(showPreview) scroll.post { scroll.scrollTo(0,session.previewScroll) }
        }
        val title=state.previewCandidate?.song?.title ?: getString(R.string.search_lyrics)
        if(headingTitle.text.toString()!=title) {
            headingTitle.text=title
            headingScroll.scrollTo(0,0)
        }
        previewDetails.text=state.previewCandidate?.let(::candidateDetails).orEmpty()
        previewRetry.visibility=if(showPreview && !state.previewLoading && state.preview==null) View.VISIBLE else View.GONE
        searchButton.isEnabled=!state.applying && state.sources.isNotEmpty() && listOf(state.query.title,state.query.artist,state.query.album).any { it.isNotBlank() }
        applyButton.visibility=if(showPreview) View.VISIBLE else View.GONE
        applyButton.isEnabled=state.canApply
        applyButton.alpha=if(state.canApply) 1f else 0.38f
        val busy=state.applying || state.previewLoading
        loading.visibility=if(busy) View.VISIBLE else View.GONE
        loading.contentDescription=getString(R.string.ui_loading)
        feedback.text=when {
            busy -> ""
            state.error!=null -> errorText(state.error)
            showPreview && state.preview==null -> getString(unavailableMessage(state.previewUnavailableReason))
            else -> ""
        }
        feedback.visibility=if(feedback.text.isEmpty()) View.GONE else View.VISIBLE
        renderTarget(state)
        if(shownResults!=state.results) { shownResults=state.results;results.render(state.results) }
        if(pendingSourceFocus) appBar.post { focusSourcesIfReady() }
        if(shownPreview!=state.preview) {
            shownPreview=state.preview;preview.removeAllViews()
            state.preview?.takeIf { it.lyrics.plainLrc.isNotBlank() || !it.lyrics.translatedLrc.isNullOrBlank() }?.let { p ->
                preview.addView(previewHeading(getString(if(p.lyrics.wordByWordLines.isEmpty()) R.string.search_line_lyrics else R.string.search_word_lyrics)))
                p.lyrics.access?.attribution?.let { attribution ->
                    preview.addView(text("").apply { text=attribution.displayText();movementMethod=LinkMovementMethod.getInstance() })
                }
                preview.addView(text(p.lyrics.plainLrc).apply { setTextIsSelectable(true) })
                p.lyrics.translatedLrc?.let { translation ->
                    preview.addView(previewHeading(getString(R.string.search_translation)))
                    preview.addView(text(translation).apply { setTextIsSelectable(true) })
                }
            }
        }
        val revision=state.confirmationRevision
        if(revision!=confirmationRevision) {
            confirmation?.dismiss();confirmation=null;confirmationRevision=revision
            if(revision!=null) confirmation=AlertDialog.Builder(this)
                .setTitle(R.string.search_apply).setMessage(R.string.search_replace_local)
                .setNegativeButton(R.string.ui_cancel) { _,_-> session.dismissConfirmation() }
                .setPositiveButton(R.string.search_apply) { _,_-> session.apply(revision) }
                .setOnCancelListener { session.dismissConfirmation() }.show()
        }
    }
    private fun focusSourcesIfReady() {
        if(!pendingSourceFocus || inPreview || session.state.value.results.isEmpty()) return
        pendingSourceFocus=false
        appBar.setExpanded(false,ValueAnimator.areAnimatorsEnabled())
    }

    private fun renderTarget(state:LyricsSearchState) {
        val changed=currentSong?.let { !it.isStrongSameSong(state.target) }==true
        targetText.text = listOfNotNull(
            listOf(state.target.title, state.target.artist).filter { it.isNotBlank() }.joinToString(" · "),
            getString(R.string.search_previous_song).takeIf { changed }
        ).joinToString("\n")
        val showPreview=state.previewCandidate!=null
        targetText.visibility=if(showPreview) View.GONE else View.VISIBLE
        switchButton.visibility=if(changed && !showPreview) View.VISIBLE else View.GONE
        switchButton.isEnabled=!state.applying
    }
    private fun candidateDetails(candidate:LyricsCandidate):String {
        val song=candidate.song
        val duration=if(song.durationMs>0) String.format(Locale.ROOT,"%d:%02d",song.durationMs/60000,song.durationMs/1000%60) else ""
        val album=song.album.takeUnless { it.trim().equals(song.title.trim(),ignoreCase=true) }.orEmpty()
        return listOf(
            listOf(song.artist,album).filter { it.isNotBlank() }.joinToString(" · "),
            listOf(duration,localizedPlainLyricsProviderName(candidate.source.key)).filter { it.isNotBlank() }.joinToString(" · ")
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
    private fun unavailableMessage(reason:LyricsUnavailableReason?)=when(reason) {
        LyricsUnavailableReason.SOURCE_EMPTY -> R.string.search_source_empty
        LyricsUnavailableReason.INSTRUMENTAL -> R.string.search_no_content
        LyricsUnavailableReason.NO_TIMELINE -> R.string.search_no_timeline
        LyricsUnavailableReason.UNSUPPORTED_FORMAT -> R.string.search_unsupported_format
        LyricsUnavailableReason.NO_USABLE_LINES -> R.string.search_no_usable_lines
        LyricsUnavailableReason.UNKNOWN,null -> R.string.search_no_content
    }
    private fun errorText(error:Throwable)=if(error is LyricsLookupException) localizedLyricsLookupMessage(error) else getString(R.string.search_failed)
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value:String,size:Float=15f)=TextView(this).apply {
        text=value;textSize=size;setTextColor(palette.textStrong);setPadding(0,dp(8),0,dp(8))
    }
    private fun previewHeading(value:String)=text(value,17f).apply {
        setTypeface(typeface,Typeface.BOLD)
        setTextColor(ColorUtils.blendARGB(palette.accent,palette.textStrong,0.45f))
        setPadding(0,dp(6),0,dp(6))
        layoutParams=LinearLayout.LayoutParams(-2,-2).apply {
            topMargin=dp(8)
            bottomMargin=dp(6)
        }
        ViewCompat.setAccessibilityHeading(this,true)
    }
    private fun input(label:Int,queryField:SearchQueryField,resetLabel:Int):Pair<TextInputEditText,TextInputLayout> {
        val edit=TextInputEditText(this).apply { setSingleLine();setTextColor(palette.textStrong);imeOptions=EditorInfo.IME_ACTION_SEARCH
            background=null
            setPadding(dp(12),dp(26),dp(12),dp(10))
            minimumHeight=dp(64) }
        val field=TextInputLayout(this).apply {
            hint=getString(label);boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_FILLED
            boxBackgroundColor=palette.background
            defaultHintTextColor=ColorStateList.valueOf(palette.textMuted)
            addView(edit);layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }
            endIconMode=TextInputLayout.END_ICON_CUSTOM
            setEndIconDrawable(R.drawable.ic_air_reset)
            setEndIconTintList(ColorStateList.valueOf(ColorUtils.blendARGB(palette.accent,palette.textStrong,0.35f)))
            setEndIconContentDescription(resetLabel)
            isEndIconCheckable=false
            setEndIconOnClickListener { session.restoreQuery(queryField) }
            isEndIconVisible=false
        }
        queryFields[queryField]=field
        return edit to field
    }
    private fun renderResetIcons(state:LyricsSearchState) {
        queryFields.forEach { (key,field) ->
            val changed=key.value(state.query)!=key.value(state.target)
            val icon=field.findViewById<View>(com.google.android.material.R.id.text_input_end_icon)
            icon.isEnabled=!state.applying && changed
            if(resetVisible[key]!=changed) {
                val wasVisible=resetVisible[key]
                resetVisible[key]=changed
                icon.animate().cancel()
                icon.animate().withEndAction(null)
                val animate=wasVisible!=null && field.isLaidOut && field.isShown && ValueAnimator.areAnimatorsEnabled()
                if(!animate) {
                    field.isEndIconVisible=changed
                    icon.alpha=1f
                    icon.scaleX=1f
                    icon.scaleY=1f
                    icon.rotation=0f
                } else if(changed) {
                    if(!field.isEndIconVisible) {
                        icon.alpha=0f
                        icon.scaleX=0.55f
                        icon.scaleY=0.55f
                        icon.rotation=-30f
                    }
                    field.isEndIconVisible=true
                    icon.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                        .setInterpolator(OvershootInterpolator(1.5f)).setDuration(320).start()
                } else {
                    icon.animate().alpha(0f).scaleX(0.45f).scaleY(0.45f).rotation(25f)
                        .setInterpolator(AnticipateInterpolator(1.2f)).setDuration(240)
                        .withEndAction {
                            if(resetVisible[key]==false) {
                                field.isEndIconVisible=false
                                icon.alpha=1f
                                icon.scaleX=1f
                                icon.scaleY=1f
                                icon.rotation=0f
                            }
                        }.start()
                }
            }
        }
    }
    private fun textAction(label:Int,action:()->Unit)=button(label,action).apply {
        backgroundTintList=ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(palette.accent)
        gravity=Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(4),0,dp(12),0)
    }
    private fun button(label:Int,action:()->Unit)=button(getString(label),action)
    private fun button(label:String,action:()->Unit)=MaterialButton(this).apply {
        text=label;isAllCaps=false;cornerRadius=dp(20);minHeight=dp(48)
        letterSpacing = 0f
        stateListAnimator = null
        elevation = 0f
        setTextColor(palette.textStrong);backgroundTintList=ColorStateList.valueOf(palette.surfaceLight)
        iconTint=ColorStateList.valueOf(palette.accent)
        setOnClickListener { action() }
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    override fun onSaveInstanceState(outState:Bundle) {
        if (::session.isInitialized) writeTarget(outState,session.state.value.target)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { confirmation?.dismiss();if(::results.isInitialized) results.dispose();super.onDestroy() }

    companion object {
        internal fun open(context:Context,target:SongIdentity) {
            if(target.title.isBlank()) return
            context.startActivity(Intent(context,LyricsSearchActivity::class.java).putExtras(Bundle().apply { writeTarget(this,target) }))
        }
        private fun writeTarget(bundle:Bundle,target:SongIdentity) {
            bundle.putString("search.title",target.title);bundle.putString("search.artist",target.artist)
            bundle.putString("search.album",target.album);bundle.putLong("search.duration",target.durationMs)
        }
        private fun readTarget(bundle:Bundle?):SongIdentity? {
            val title=bundle?.getString("search.title")?.takeIf { it.isNotBlank() } ?: return null
            return SongIdentity(title,bundle.getString("search.artist").orEmpty(),bundle.getString("search.album").orEmpty(),bundle.getLong("search.duration"))
        }
    }
}
