package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.transition.TransitionValues
import android.transition.Visibility
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private const val MENU_MAX_HEIGHT_DP = 264
private const val MENU_ENTER_MS = 210L
private const val MENU_EXIT_MS = 135L
// Easing from the sliding layers and character motion on https://yui540.com/.
internal val aboutVersionMotionEase = PathInterpolator(0f, 0.15f, 0.25f, 0.99f)

@SuppressLint("ViewConstructor") // This picker is created in the dialog with its release list.
internal class AboutVersionPicker(
    private val host: MainUiHost,
    private var releases: List<AppReleaseNotes>
) : LinearLayout(host) {
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(host.resources.configuration.locales[0])
    private var selectedIndex = 0
    private val trigger = object : AppCompatTextView(host) {
        override fun getAccessibilityClassName(): CharSequence =
            if (isClickable) Button::class.java.name else super.getAccessibilityClassName()
    }.apply {
        textSize = AirUiTokens.TextSize.DialogTitle
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccent, 60)),
            null,
            surface(Color.WHITE)
        )
        enableSoftPressFeedback()
    }
    private val date = TextView(host).apply {
        textSize = AirUiTokens.TextSize.BodySmall
        setTextColor(host.colorText)
    }
    private val versionsAdapter = object : BaseAdapter() {
        override fun getCount(): Int = releases.size
        override fun getItem(position: Int): AppReleaseNotes = releases[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? VersionRow ?: VersionRow()
            row.bind(getItem(position), position == selectedIndex)
            return row
        }
    }
    private val versions = ListView(host).apply {
        divider = null
        choiceMode = ListView.CHOICE_MODE_SINGLE
        setSelector(RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccent, 64)),
            null,
            surface(Color.WHITE)
        ))
        setDrawSelectorOnTop(true)
    }
    private val menuSurface = FrameLayout(host).apply {
        background = surface(host.colorCard, host.colorStroke)
        clipToOutline = true
        val inset = host.dp(AirUiTokens.Space.Sm)
        setPadding(inset, inset, inset, inset)
        addView(versions, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    private val popup = PopupWindow(menuSurface, WRAP_CONTENT, WRAP_CONTENT, true).apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
    }

    init {
        orientation = VERTICAL
        addView(trigger, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(date, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        trigger.setOnClickListener { if (popup.isShowing) popup.dismiss() else openVersionList() }
    }

    fun replaceReleases(items: List<AppReleaseNotes>, selected: AppReleaseNotes) {
        if (popup.isShowing) popup.dismiss()
        releases = items
        versionsAdapter.notifyDataSetChanged()
        showRelease(selected)
    }

    fun showRelease(release: AppReleaseNotes) {
        selectedIndex = releases.indexOf(release)
        trigger.text = release.version
        date.text = release.date?.format(dateFormat).orEmpty()
        date.isVisible = release.date != null
        val selectable = releases.size > 1
        trigger.isClickable = selectable
        trigger.isFocusable = selectable
        trigger.setTextColor(if (selectable) host.colorAccentText else host.colorTextStrong)
        val touchSize = if (selectable) host.dp(AirUiTokens.Layout.IconTouchSize) else 0
        trigger.minimumWidth = touchSize
        trigger.minimumHeight = touchSize
        trigger.contentDescription = if (selectable) host.getString(R.string.ui_app_version_select, release.version) else null
    }

    fun setOnReleaseSelected(listener: (AppReleaseNotes) -> Unit) {
        versions.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            listener(releases[position])
        }
    }

    override fun onDetachedFromWindow() {
        popup.exitTransition = null
        popup.dismiss()
        super.onDetachedFromWindow()
    }

    private fun openVersionList() {
        val anchor = parent as? View ?: return
        if (!anchor.isAttachedToWindow || anchor.width <= 0) return
        val gap = host.dp(AirUiTokens.Space.Sm)
        val heightLimit = minOf(host.dp(MENU_MAX_HEIGHT_DP), popup.getMaxAvailableHeight(anchor, gap))
        if (heightLimit <= 0) return

        versions.adapter = versionsAdapter
        versions.setItemChecked(selectedIndex, true)
        versions.setSelection(selectedIndex)
        menuSurface.clipBounds = null
        menuSurface.measure(
            MeasureSpec.makeMeasureSpec(anchor.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST)
        )
        popup.width = anchor.width
        popup.height = menuSurface.measuredHeight
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        popup.enterTransition = if (motionEnabled) menuReveal(open = true) else null
        popup.exitTransition = if (motionEnabled) menuReveal(open = false) else null
        popup.showAsDropDown(anchor, 0, gap, Gravity.START)
    }

    private fun menuReveal(open: Boolean) = object : Visibility() {
        override fun onAppear(root: ViewGroup, view: View, start: TransitionValues?, end: TransitionValues?): Animator =
            view.versionClipReveal(popup.isAboveAnchor, true, MENU_ENTER_MS)

        override fun onDisappear(root: ViewGroup, view: View, start: TransitionValues?, end: TransitionValues?): Animator =
            view.versionClipReveal(popup.isAboveAnchor, false, MENU_EXIT_MS)
    }.apply { mode = if (open) Visibility.MODE_IN else Visibility.MODE_OUT }

    private fun surface(fill: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(host.dp(AirUiTokens.Stroke.Hairline), stroke)
    }

    private inner class VersionRow : LinearLayout(host) {
        private val label = TextView(host).apply {
            textSize = AirUiTokens.TextSize.Body
            setTextColor(host.colorTextStrong)
        }
        private val releaseDate = TextView(host).apply {
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(host.colorText)
            gravity = Gravity.END
        }
        private val checkmark = host.airIconView(R.drawable.ic_air_check, host.colorAccentText).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
            val side = host.dp(AirUiTokens.Space.Xxl)
            val vertical = host.dp(AirUiTokens.Space.Sm)
            setPaddingRelative(side, vertical, side, vertical)
            addView(label, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(releaseDate, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = host.dp(AirUiTokens.Space.Xl) })
            val iconSize = host.dp(AirUiTokens.Layout.CompactIconButtonSize / 2)
            addView(checkmark, LayoutParams(iconSize, iconSize).apply { marginStart = host.dp(AirUiTokens.Space.Xl) })
        }

        fun bind(release: AppReleaseNotes, selected: Boolean) {
            label.text = release.version
            label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            releaseDate.text = release.date?.format(dateFormat).orEmpty()
            checkmark.visibility = if (selected) VISIBLE else INVISIBLE
            isActivated = selected
            background = surface(
                if (selected) ColorUtils.blendARGB(host.colorCard, host.colorAccent, 0.16f) else Color.TRANSPARENT
            )
        }
    }
}

private fun View.versionClipReveal(fromBottom: Boolean, open: Boolean, durationMs: Long): ValueAnimator {
    val shown = clipBounds?.height()
    val start = if (shown != null && height > 0) (shown.toFloat() / height).coerceIn(0f, 1f) else if (open) 0f else 1f
    val clip = Rect()
    return ValueAnimator.ofFloat(start, if (open) 1f else 0f).apply {
        duration = durationMs
        interpolator = aboutVersionMotionEase
        addUpdateListener { step ->
            val visibleHeight = (height * (step.animatedValue as Float)).roundToInt()
            val top = if (fromBottom) height - visibleHeight else 0
            clip.set(0, top, width, top + visibleHeight)
            clipBounds = clip
        }
        doOnEnd { clipBounds = null }
    }
}
