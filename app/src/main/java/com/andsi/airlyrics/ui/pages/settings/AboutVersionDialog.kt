package com.andsi.airlyrics.ui.pages.settings

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnAttach
import androidx.core.view.doOnDetach
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.layout.WindowLayoutSpec
import com.andsi.airlyrics.ui.model.AppUpdateCheckStatus
import com.andsi.airlyrics.ui.model.AppUpdateDownloadState
import com.andsi.airlyrics.ui.model.AppUpdateReleaseInfo
import com.andsi.airlyrics.ui.model.AppUpdateUi
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.forgetAuxiliaryDialog
import com.andsi.airlyrics.ui.state.isAuxiliaryDialogShowing
import com.andsi.airlyrics.ui.state.rememberAuxiliaryDialog
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlinx.coroutines.flow.StateFlow

internal const val APP_UPDATE_DIALOG = "appUpdate"
private const val VERSION_DIALOG_HEIGHT_DP = 352
private const val DIALOG_STATE = "aux.$APP_UPDATE_DIALOG"
private const val PLANE_LIFT_MS = 210L

internal data class AboutVersionUpdate(
    val release: AppReleaseNotes,
    val download: StateFlow<AppUpdateDownloadState>,
    val onCancel: (() -> Unit)? = null,
    val onInstall: (() -> Unit)? = null,
    val onUpdate: () -> Unit
)

private fun versionUpdate(updates: AppUpdateUi, release: AppUpdateReleaseInfo) = AboutVersionUpdate(
    release = AppReleaseNotes(release.versionName, release.publishedOn, formatGitHubReleaseNotes(release.notes)),
    download = updates.state,
    onCancel = updates::reset,
    onInstall = { updates.install(release) },
    onUpdate = { updates.download(release) }
)

internal fun MainUiHost.showAppUpdateDialog(update: AboutVersionUpdate? = null) {
    if (isAuxiliaryDialogShowing(APP_UPDATE_DIALOG)) return
    val installedVersion = getAppVersionName()
    val bundled = loadBundledReleaseNotes()
    val installed = bundled.firstOrNull { it.version == installedVersion } ?: AppReleaseNotes(installedVersion)
    val updates = if (update == null) appUpdates else null
    var liveRelease = updates?.let { it.activeRelease ?: it.availableUpdate.value }
    var availableUpdate = update ?: liveRelease?.let { versionUpdate(requireNotNull(updates), it) }
    fun releaseList() = (listOfNotNull(availableUpdate?.release, installed) + bundled)
        .distinctBy(AppReleaseNotes::version)
    var releases = releaseList()
    fun releaseIndex(version: String?) = releases.indexOfFirst { it.version == version }
        .takeIf { it >= 0 } ?: releases.indexOf(installed)

    val selectedIndex = releaseIndex(interactions.read(DIALOG_STATE)?.getString("version"))
    val selected = releases[selectedIndex]
    rememberAuxiliaryDialog(APP_UPDATE_DIALOG, Bundle().apply { putString("version", selected.version) })

    var closing = false
    var replacingReleases = false
    val versionPicker = AboutVersionPicker(this, releases).apply { showRelease(selected) }
    val notesPager = ViewPager2(this)
    fun jumpTo(version: String) {
        notesPager.setCurrentItem(releaseIndex(version), ValueAnimator.areAnimatorsEnabled())
    }

    fun updatePager(position: Int) {
        val candidate = availableUpdate
        replacingReleases = true
        notesPager.adapter = ReleaseNotesAdapter(this, releases) { release ->
            when {
                candidate?.release == release -> AboutVersionDownloadFooter(this, candidate) { closing }
                release == installed && candidate != null -> availableUpdateShortcut(candidate.release.version) {
                    jumpTo(candidate.release.version)
                }
                release == installed && (updates != null || bundled.firstOrNull()?.version == installedVersion) ->
                    currentReleaseStatus(updates)
                else -> null
            }
        }
        notesPager.isUserInputEnabled = releases.size > 1
        notesPager.setCurrentItem(position, false)
        val release = releases[position]
        versionPicker.replaceReleases(releases, release)
        interactions.write(DIALOG_STATE) { putString("version", release.version) }
        replacingReleases = false
    }

    updatePager(selectedIndex)
    val pageChanges = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (replacingReleases || closing) return
            val release = releases.getOrNull(position) ?: return
            versionPicker.showRelease(release)
            interactions.write(DIALOG_STATE) { putString("version", release.version) }
        }
    }
    notesPager.registerOnPageChangeCallback(pageChanges)
    notesPager.doOnAttach { attached ->
        attached.doOnDetach { notesPager.unregisterOnPageChangeCallback(pageChanges) }
    }
    versionPicker.setOnReleaseSelected { jumpTo(it.version) }

    if (updates != null) {
        bindAppUpdateState(notesPager, updates.availableUpdate) { release ->
            val next = updates.activeRelease ?: release
            if (!closing && liveRelease != next) {
                val version = releases.getOrNull(notesPager.currentItem)?.version
                liveRelease = next
                availableUpdate = next?.let { versionUpdate(updates, it) }
                releases = releaseList()
                updatePager(releaseIndex(version))
            }
        }
    }

    auxiliaryDialogs[APP_UPDATE_DIALOG] = showAirDialog(
        title = null,
        titleView = versionPicker,
        positiveText = null,
        useOuterScroll = false,
        maxWidthDp = WindowLayoutSpec.DIALOG_MAX,
        preferredHeightDp = VERSION_DIALOG_HEIGHT_DP,
        coordinatedDim = true,
        showCloseButton = true,
        onUserDismiss = {
            closing = true
            updates?.reset()
            forgetAuxiliaryDialog(APP_UPDATE_DIALOG)
        },
        body = {
            addView(notesPager, LinearLayout.LayoutParams(MATCH_PARENT, dp(VERSION_DIALOG_HEIGHT_DP), 1f).apply {
                topMargin = dp(AirUiTokens.Space.Xl)
            })
        }
    )
}

private fun MainUiHost.currentReleaseStatus(updates: AppUpdateUi?): TextView = TextView(this).apply {
    setText(R.string.ui_app_up_to_date)
    setTextColor(colorText)
    textSize = AirUiTokens.TextSize.Body
    if (updates != null) {
        setOnClickListener { updates.checkNow() }
        bindAppUpdateState(this, updates.checkStatus) { status ->
            val canRetry = status == AppUpdateCheckStatus.Failed
            val textRes = when (status) {
                AppUpdateCheckStatus.Unchecked, AppUpdateCheckStatus.Checking -> R.string.ui_app_update_checking
                AppUpdateCheckStatus.Complete -> R.string.ui_app_up_to_date
                AppUpdateCheckStatus.Failed -> R.string.ui_app_update_check_retry
            }
            setText(textRes)
            setTextColor(if (canRetry) colorAccentText else colorText)
            isClickable = canRetry
            isFocusable = canRetry
            minimumHeight = if (canRetry) dp(AirUiTokens.Layout.IconTouchSize) else 0
            gravity = Gravity.CENTER_VERTICAL
        }
    }
}

private fun MainUiHost.availableUpdateShortcut(version: String, onJump: () -> Unit): View {
    val label = TextView(this).apply {
        setText(R.string.ui_app_update_available_label)
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorAccentText)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val plane = airIconView(R.drawable.ic_air_paper_plane, colorAccentText)
    val iconSize = dp(AirUiTokens.Layout.IconSize)
    val travel = dp(AirUiTokens.Space.Sm).toFloat()
    var lift: ObjectAnimator? = null

    return object : LinearLayout(this) {
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
        setPaddingRelative(0, 0, travel.toInt(), 0)
        isFocusable = true
        contentDescription = getString(R.string.ui_app_update_available, version)
        addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(plane, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginStart = dp(AirUiTokens.Space.Lg)
        })
        setOnClickListener {
            if (ValueAnimator.areAnimatorsEnabled() && lift?.isStarted != true) {
                val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
                lift = ObjectAnimator.ofPropertyValuesHolder(
                    plane,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, travel * direction, 0f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -travel, 0f),
                    PropertyValuesHolder.ofFloat(View.ROTATION, 0f, -12f * direction, 0f)
                ).apply {
                    duration = PLANE_LIFT_MS
                    interpolator = aboutVersionMotionEase
                    start()
                }
            }
            onJump()
        }
        doOnDetach { lift?.end() }
    }
}

private class ReleaseNotesAdapter(
    private val host: MainUiHost,
    private val releases: List<AppReleaseNotes>,
    private val footerFor: (AppReleaseNotes) -> View?
) : RecyclerView.Adapter<ReleaseNotesAdapter.Page>() {
    override fun getItemCount(): Int = releases.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Page = with(host) {
        val notes = TextView(this).apply {
            textSize = AirUiTokens.TextSize.Body
            setTextColor(colorText)
            setLineSpacing(dp(AirUiTokens.Space.Sm).toFloat(), 1f)
            setPadding(0, 0, 0, dp(AirUiTokens.Space.Sm))
        }
        val scroll = NestedScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(notes)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        Page(content, scroll, notes)
    }

    override fun onBindViewHolder(holder: Page, position: Int) {
        val release = releases[position]
        holder.notes.text = if (release.notes.isBlank()) host.getString(R.string.ui_no_changelog_yet)
            else host.formatNotes(release.notes)
        holder.scroll.scrollTo(0, 0)
        holder.footer?.let(holder.content::removeView)
        holder.footer = footerFor(release)?.also { footer ->
            holder.content.addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = host.dp(AirUiTokens.Space.Xl)
            })
        }
    }

    override fun onViewAttachedToWindow(holder: Page) {
        val release = releases.getOrNull(holder.bindingAdapterPosition) ?: return
        val versionKey = release.version.replace('.', '_')
        holder.releaseScroll = host.bindInteractionScroll(holder.scroll, "$DIALOG_STATE.scroll-$versionKey")
    }

    override fun onViewDetachedFromWindow(holder: Page) {
        holder.releaseScroll?.invoke()
        holder.releaseScroll = null
    }

    class Page(val content: LinearLayout, val scroll: NestedScrollView, val notes: TextView) :
        RecyclerView.ViewHolder(content) {
        var footer: View? = null
        var releaseScroll: (() -> Unit)? = null
    }
}

private val changelogHeadings = setOf("Added", "Improved", "Changed", "Fixed")

private fun MainUiHost.formatNotes(text: String): CharSequence = SpannableStringBuilder().apply {
    val lines = text.lines()
    for ((index, line) in lines.withIndex()) {
        val start = length
        append(line.removePrefix("- "))
        if (index != lines.lastIndex) append('\n')
        if (line.startsWith("- ")) {
            setSpan(BulletSpan(dp(AirUiTokens.Space.Lg), colorText), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (line in changelogHeadings) {
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(colorTextStrong), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
