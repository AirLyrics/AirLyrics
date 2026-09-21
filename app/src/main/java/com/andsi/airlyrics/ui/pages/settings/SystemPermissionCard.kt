package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

private data class PermissionEntry(
    val title: String,
    val description: String,
    val status: String,
    val granted: Boolean,
    val purpose: String? = null,
    val settingsAvailable: Boolean = true,
    val openSettings: () -> Unit
)

internal fun MainUiHost.systemPermissionCard(): View {
    val host = this
    val usageAccessAvailable = displayScopeSupported()
    val overlayGranted = uiState.overlayPermissionGranted
    val notificationsGranted = hasNotificationPermission()
    val notificationAccessGranted = hasNotificationListenerAccess()
    val usageAccessGranted = usageAccessAvailable && hasUsageStatsAccess()
    val coreEntries = listOf(
        PermissionEntry(
            title = getString(R.string.ui_overlay),
            description = getString(R.string.ui_overlay_description),
            status = getString(if (overlayGranted) R.string.ui_on else R.string.ui_off),
            granted = overlayGranted,
            openSettings = uiActions.requestOverlayPermission
        ),
        PermissionEntry(
            title = getString(R.string.ui_notif_access),
            description = getString(R.string.ui_notif_access_description),
            status = getString(if (notificationAccessGranted) R.string.ui_on else R.string.ui_off),
            granted = notificationAccessGranted,
            openSettings = uiActions.openNotificationListenerSettings
        )
    )
    val optionalEntries = listOf(
        PermissionEntry(
            title = getString(R.string.ui_notify),
            description = getString(R.string.ui_notifications_description),
            status = getString(if (notificationsGranted) R.string.ui_on else R.string.ui_off),
            granted = notificationsGranted,
            purpose = getString(R.string.ui_notifications_usage_hint),
            openSettings = uiActions.requestNotificationPermission
        ),
        PermissionEntry(
            title = getString(R.string.ui_usage_access),
            description = getString(R.string.ui_usage_access_description),
            status = if (usageAccessAvailable) {
                getString(if (usageAccessGranted) R.string.ui_on else R.string.ui_off)
            } else {
                getString(R.string.ui_android_10_required)
            },
            granted = usageAccessGranted,
            purpose = getString(R.string.ui_display_scope_usage_hint),
            settingsAvailable = usageAccessAvailable,
            openSettings = uiActions.openUsageAccessSettings
        )
    )

    return card(host) {
        addView(bigText(host, getString(R.string.ui_permissions)))
        addPermissionEntries(host, coreEntries)
        addView(permissionGroupLabel(host, R.string.ui_optional_permissions))
        addPermissionEntries(host, optionalEntries)
    }
}

private fun LinearLayout.addPermissionEntries(
    activity: MainUiHost,
    entries: List<PermissionEntry>
) {
    entries.forEachIndexed { index, entry ->
        if (index > 0) addView(permissionDivider(activity))
        addView(permissionEntryRow(activity, entry))
    }
}

private fun permissionGroupLabel(activity: MainUiHost, textRes: Int): View = with(activity) {
    return TextView(this).apply {
        setText(textRes)
        textSize = AirUiTokens.TextSize.Caption
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorTextMuted)
        setPadding(
            0,
            dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xl),
            0,
            0
        )
    }
}

private fun permissionEntryRow(
    activity: MainUiHost,
    entry: PermissionEntry
): View = with(activity) {
    var helpButton: View? = null
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(PERMISSION_ROW_MIN_HEIGHT_DP)
        contentDescription = getString(
            R.string.ui_permission_entry_description,
            entry.title,
            entry.description,
            entry.status
        )
        isFocusable = true
        setPadding(
            0,
            dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
            0,
            dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
        )

        if (entry.settingsAvailable) {
            isClickable = true
            enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
            setOnClickListener {
                entry.openSettings()
                playTinyPulse(this)
            }
        } else {
            alpha = UNAVAILABLE_PERMISSION_ALPHA
        }

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Sm), 0)
            }

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(AirUiTokens.Layout.IconSize)

                addView(TextView(activity).apply {
                    text = entry.title
                    textSize = AirUiTokens.TextSize.Button
                    setTextColor(colorTextStrong)
                })

                entry.purpose?.let { purpose ->
                    val button = permissionHelpButton(
                        activity = activity,
                        title = entry.title,
                        purpose = purpose
                    )
                    helpButton = button
                    addView(button)
                }
            })

            addView(TextView(activity).apply {
                text = entry.description
                textSize = AirUiTokens.TextSize.BodySmall
                setTextColor(colorTextMuted)
                setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
            })
        })

        addPermissionState(activity, entry)
    }

    helpButton?.let { installExpandedTouchTarget(activity, row, it) }
    return row
}

private fun LinearLayout.addPermissionState(
    activity: MainUiHost,
    entry: PermissionEntry
) = with(activity) {
    if (entry.granted) {
        addView(
            airIconView(
                iconRes = R.drawable.ic_air_check,
                tint = colorAccent
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(AirUiTokens.Layout.IconSize),
                    dp(AirUiTokens.Layout.IconSize)
                )
            }
        )
    } else {
        addView(TextView(activity).apply {
            text = entry.status
            textSize = AirUiTokens.TextSize.BodySmall
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(if (entry.settingsAvailable) colorAccent else colorTextMuted)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                PERMISSION_STATUS_WEIGHT
            )
        })

        if (entry.settingsAvailable) {
            addView(
                airIconView(
                    iconRes = R.drawable.ic_air_chevron_right,
                    tint = colorAccent
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dp(AirUiTokens.Layout.IconSize),
                        dp(AirUiTokens.Layout.IconSize)
                    ).apply {
                        setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                    }
                }
            )
        }
    }
}

private fun permissionHelpButton(
    activity: MainUiHost,
    title: String,
    purpose: String
): View = with(activity) {
    return FrameLayout(this).apply {
        contentDescription = getString(R.string.ui_permission_purpose, title)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(
            dp(AirUiTokens.Layout.IconSize),
            dp(AirUiTokens.Layout.IconSize)
        ).apply {
            setMargins(dp(AirUiTokens.Space.Xxs), 0, 0, 0)
        }
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            showAirInfoDialog(title = title, message = purpose)
            playTinyPulse(this)
        }

        addView(TextView(activity).apply {
            text = "?"
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = AirUiTokens.TextSize.Tiny
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorSurfaceLight)
                setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
            }
            layoutParams = FrameLayout.LayoutParams(
                dp(PERMISSION_HELP_VISUAL_SIZE_DP),
                dp(PERMISSION_HELP_VISUAL_SIZE_DP),
                Gravity.CENTER
            )
        })
    }
}

private fun permissionDivider(activity: MainUiHost): View = with(activity) {
    return View(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = PERMISSION_DIVIDER_ALPHA
        setBackgroundColor(colorStroke)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(AirUiTokens.Stroke.Hairline)
        )
    }
}

private fun installExpandedTouchTarget(
    activity: MainUiHost,
    parent: ViewGroup,
    target: View
) {
    // Keep the inline marker visually quiet while preserving a full touch target.
    parent.post {
        if (target.parent == null || parent.width == 0 || parent.height == 0) return@post

        val bounds = Rect().also { target.getDrawingRect(it) }
        parent.offsetDescendantRectToMyCoords(target, bounds)
        val minimumSize = activity.dp(AirUiTokens.Layout.IconTouchSize)
        val horizontalInset = ((minimumSize - bounds.width()) / 2).coerceAtLeast(0)
        val verticalInset = ((minimumSize - bounds.height()) / 2).coerceAtLeast(0)
        bounds.inset(-horizontalInset, -verticalInset)
        if (!bounds.intersect(0, 0, parent.width, parent.height)) return@post
        parent.touchDelegate = TouchDelegate(bounds, target)
    }
}

private const val PERMISSION_ROW_MIN_HEIGHT_DP = 64
private const val PERMISSION_HELP_VISUAL_SIZE_DP = 18
private const val UNAVAILABLE_PERMISSION_ALPHA = 0.52f
private const val PERMISSION_DIVIDER_ALPHA = 0.72f
private const val PERMISSION_STATUS_WEIGHT = 0.42f
