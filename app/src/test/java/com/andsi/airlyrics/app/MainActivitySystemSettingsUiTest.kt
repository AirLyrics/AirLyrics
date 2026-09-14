package com.andsi.airlyrics.app

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
class MainActivitySystemSettingsUiTest {
    private var activityController: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        RuntimeEnvironment.setFontScale(1f)
        ShadowDialog.reset()
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun permissions_areOneListWithStatusActionsAndInlineHelp() {
        ShadowSettings.setCanDrawOverlays(false)
        val activity = launchSystemSettings()
        val root = activity.findViewById<View>(android.R.id.content)

        val permissionsTitle = requireNotNull(
            root.findTextView(activity.getString(R.string.ui_permissions))
        )
        val permissionCard = permissionsTitle.parent as ViewGroup
        assertEquals("Header, optional label, four entries and two dividers", 8, permissionCard.childCount)

        val entries = listOf(
            R.string.ui_overlay to R.string.ui_overlay_description,
            R.string.ui_notif_access to R.string.ui_notif_access_description,
            R.string.ui_notify to R.string.ui_notifications_description,
            R.string.ui_usage_access to R.string.ui_usage_access_description
        )
        assertEquals(
            entries.map { activity.getString(it.first) },
            permissionCard.descendantTexts().filter { text ->
                entries.any { (titleRes, _) -> text == activity.getString(titleRes) }
            }
        )
        assertTrue(
            permissionCard.descendantTexts().contains(
                activity.getString(R.string.ui_optional_permissions)
            )
        )
        val statusTexts = setOf(
            activity.getString(R.string.ui_on),
            activity.getString(R.string.ui_off),
            activity.getString(R.string.ui_android_10_required)
        )
        entries.forEach { (titleRes, descriptionRes) ->
            val title = requireNotNull(root.findTextView(activity.getString(titleRes)))
            val description = requireNotNull(root.findTextView(activity.getString(descriptionRes)))
            val row = requireNotNull(title.clickableAncestor()) {
                "Permission title should open its settings: ${title.text}"
            }
            assertSame(row, description.clickableAncestor())
            assertTrue(row.isFocusable)
            val visualStatus = row.findStatusText(statusTexts)?.text
                ?: activity.getString(R.string.ui_on)
            assertEquals(
                activity.getString(
                    R.string.ui_permission_entry_description,
                    title.text,
                    description.text,
                    visualStatus
                ),
                row.contentDescription
            )
        }

        val overlayRow = requireNotNull(
            root.findTextView(activity.getString(R.string.ui_overlay))?.clickableAncestor()
        ) as ViewGroup
        assertEquals(3, overlayRow.childCount)
        assertEquals(
            activity.getString(R.string.ui_off),
            overlayRow.findStatusText(statusTexts)?.text
        )
        val chevron = overlayRow.getChildAt(2) as ImageView
        assertEquals(R.drawable.ic_air_chevron_right, shadowOf(chevron.drawable).createdFromResId)

        val helpButtons = listOf(R.string.ui_notify, R.string.ui_usage_access).map { titleRes ->
            val description = activity.getString(
                R.string.ui_permission_purpose,
                activity.getString(titleRes)
            )
            requireNotNull(root.findView { it.contentDescription == description })
        }
        assertTrue(helpButtons.all { it.isClickable && it.isFocusable })
        assertEquals(2, root.descendantTexts().count { it == "?" })

        helpButtons.first().performClick()
        ShadowLooper.idleMainLooper()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(
            dialog.descendantTexts().contains(
                activity.getString(R.string.ui_notifications_usage_hint)
            )
        )
    }

    @Test
    fun grantedPermission_showsOnlyCheckWithoutStatusTextOrChevron() {
        ShadowSettings.setCanDrawOverlays(true)
        val activity = launchSystemSettings()
        val root = activity.findViewById<View>(android.R.id.content)
        val overlayRow = requireNotNull(
            root.findTextView(activity.getString(R.string.ui_overlay))?.clickableAncestor()
        ) as ViewGroup
        val statusTexts = setOf(
            activity.getString(R.string.ui_on),
            activity.getString(R.string.ui_off),
            activity.getString(R.string.ui_android_10_required)
        )

        assertEquals(2, overlayRow.childCount)
        assertNull(overlayRow.findStatusText(statusTexts))
        val check = overlayRow.getChildAt(1) as ImageView
        assertEquals(R.drawable.ic_air_check, shadowOf(check.drawable).createdFromResId)
        assertTrue(
            overlayRow.contentDescription.contains(activity.getString(R.string.ui_on))
        )
    }

    @Test
    fun permissionList_keepsContentInsideRowsAtLargeFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        val activity = launchSystemSettings()
        val root = activity.findViewById<View>(android.R.id.content)
        val permissionsTitle = requireNotNull(
            root.findTextView(activity.getString(R.string.ui_permissions))
        )
        val permissionCard = permissionsTitle.parent as ViewGroup
        val width = activity.graph.uiHost.dp(280)

        permissionCard.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        permissionCard.layout(0, 0, width, permissionCard.measuredHeight)

        listOf(
            R.string.ui_overlay,
            R.string.ui_notif_access,
            R.string.ui_notify,
            R.string.ui_usage_access
        ).forEach { titleRes ->
            val title = requireNotNull(
                permissionCard.findTextView(activity.getString(titleRes))
            )
            val row = requireNotNull(title.clickableAncestor())
            assertTrue(row.measuredHeight >= activity.graph.uiHost.dp(AirUiTokens.Layout.IconTouchSize))
            row.assertDescendantsWithinWidth()
        }
    }

    private fun launchSystemSettings(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
        return controller.get().also { activity ->
            activity.graph.viewModel.selectPage(Page.SETTINGS)
            activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.SYSTEM)
            activity.graph.uiInvalidator.rebuildCurrentPage(
                animateContent = false,
                animateTabs = false
            )
            ShadowLooper.idleMainLooper()
        }
    }

    private fun View.descendantTexts(): List<String> {
        val ownText = (this as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (this !is ViewGroup) return ownText
        return ownText + (0 until childCount).flatMap { getChildAt(it).descendantTexts() }
    }

    private fun Dialog.descendantTexts(): List<String> {
        return window?.decorView?.descendantTexts().orEmpty()
    }

    private fun View.findTextView(text: String): TextView? {
        if (this is TextView && this.text.toString() == text) return this
        return findView { it is TextView && it.text.toString() == text } as? TextView
    }

    private fun View.findView(predicate: (View) -> Boolean): View? {
        if (predicate(this)) return this
        if (this !is ViewGroup) return null
        return (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findView(predicate) }
    }

    private fun View.clickableAncestor(): View? {
        var candidate: View? = this
        while (candidate != null) {
            if (candidate.isClickable) return candidate
            candidate = candidate.parent as? View
        }
        return null
    }

    private fun View.findStatusText(statusTexts: Set<String>): TextView? {
        return (this as? ViewGroup)?.let { row ->
            (0 until row.childCount)
                .map(row::getChildAt)
                .filterIsInstance<TextView>()
                .firstOrNull { it.text.toString() in statusTexts }
        }
    }

    private fun View.assertDescendantsWithinWidth() {
        if (this !is ViewGroup) return
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            assertTrue(
                "${child.javaClass.simpleName} extends past ${javaClass.simpleName}",
                child.left >= paddingLeft && child.right <= width - paddingRight
            )
            child.assertDescendantsWithinWidth()
        }
    }
}
