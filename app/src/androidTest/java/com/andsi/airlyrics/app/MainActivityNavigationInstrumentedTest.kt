package com.andsi.airlyrics.app

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andsi.airlyrics.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationInstrumentedTest {
    @Test
    fun settingsSystemPermissions_canBeOpenedAndReturnedFromUsingVisibleControls() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            clickText(scenario, R.string.ui_settings)
            clickText(scenario, R.string.ui_system)

            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                assertNotNull(root.findTextView(activity.getString(R.string.ui_permissions)))
                listOf(
                    R.string.ui_overlay,
                    R.string.ui_notif_access,
                    R.string.ui_notify,
                    R.string.ui_usage_access
                ).forEach { titleRes ->
                    val title = activity.getString(titleRes)
                    val entry = root.findTextView(title)?.clickableAncestor()
                    assertNotNull("Missing permission entry: $title", entry)
                    assertTrue(entry!!.contentDescription.toString().contains(title))
                }
            }

            clickText(scenario, R.string.ui_settings_back_label)
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                assertNotNull(root.findTextView(activity.getString(R.string.ui_system)))
                assertNotNull(root.findTextView(activity.getString(R.string.ui_lyrics)))
                assertNotNull(root.findTextView(activity.getString(R.string.ui_about)))
            }
        }
    }

    private fun clickText(
        scenario: ActivityScenario<MainActivity>,
        @StringRes textRes: Int
    ) {
        scenario.onActivity { activity ->
            val text = activity.getString(textRes)
            val textView = activity.findViewById<View>(android.R.id.content).findTextView(text)
            assertNotNull("Missing visible control: $text", textView)
            val target = textView!!.clickableAncestor()
            assertNotNull("Control is not clickable: $text", target)
            assertTrue(target!!.performClick())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun View.findTextView(text: String): TextView? {
        if (this is TextView && visibility == View.VISIBLE && this.text.toString() == text) {
            return this
        }
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findTextView(text)?.let { return it }
        }
        return null
    }

    private fun View.clickableAncestor(): View? {
        var candidate: View? = this
        while (candidate != null) {
            if (candidate.isClickable && candidate.visibility == View.VISIBLE) return candidate
            candidate = candidate.parent as? View
        }
        return null
    }
}
