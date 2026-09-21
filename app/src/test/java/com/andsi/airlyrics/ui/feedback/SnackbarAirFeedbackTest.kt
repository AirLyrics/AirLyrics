package com.andsi.airlyrics.ui.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.behavior.SwipeDismissBehavior
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class SnackbarAirFeedbackTest {
    private lateinit var context: Context
    private var activityController: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppSettingsStore.setStatusPopupsMuted(context, false)
        ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        AppSettingsStore.setStatusPopupsMuted(context, false)
        ShadowDialog.reset()
    }

    @Test
    fun snackbarUsesSlideAndSwipeBehavior_thenDialogClearsActiveSnackbar() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
        val feedback = activity.graph.feedback as SnackbarAirFeedback
        val anchor = requireNotNull(activity.graph.viewRefs.feedbackAnchor)

        feedback.showMessage("Loading lyrics")

        val snackbar = requireNotNull(feedback.activeSnackbar)
        val behavior = requireNotNull(snackbar.behavior)
        assertSame(anchor, snackbar.anchorView)
        assertEquals(BaseTransientBottomBar.ANIMATION_MODE_SLIDE, snackbar.animationMode)
        assertTrue(behavior.canSwipeDismissView(snackbar.view))
        assertEquals(
            SwipeDismissBehavior.SWIPE_DIRECTION_ANY,
            ReflectionHelpers.getField<Int>(behavior, "swipeDirection")
        )

        activity.graph.uiHost.showAirDialog(
            title = "Lyrics editor",
            positiveText = "OK"
        )

        assertNull(feedback.activeSnackbar)
        assertTrue(requireNotNull(ShadowDialog.getLatestDialog()).isShowing)
    }

    @Test
    fun spokenServiceWithoutTouchExploration_usesManualMotionLifecycle() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
            .get()
        val anchor = requireNotNull(activity.graph.viewRefs.feedbackAnchor)
        val feedback = SnackbarAirFeedback(
            activity = activity,
            anchorProvider = { anchor },
            fallback = activity.graph.crossWindowFeedback,
            canShow = { true },
            paletteProvider = {
                AirLyricsTheme.palette(isDark = false, accent = ThemeAccent.DEFAULT)
            },
            manualMotionProvider = { true }
        )

        feedback.showMessage("Animated feedback")

        val snackbar = requireNotNull(feedback.activeSnackbar)
        assertEquals(Snackbar.LENGTH_INDEFINITE, snackbar.duration)
        assertEquals(0f, snackbar.view.alpha)
        assertTrue(snackbar.view.translationY > 0f)

        feedback.dismiss()
        assertNull(feedback.activeSnackbar)
    }
}
