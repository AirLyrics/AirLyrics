package com.andsi.airlyrics.app

import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.testutil.awaitUiCondition as await
import com.andsi.airlyrics.app.interaction.LyricsDraftStore
import kotlinx.coroutines.runBlocking
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.viewpager2.widget.ViewPager2
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.update.AppUpdateChecker
import com.andsi.airlyrics.app.update.AppUpdateInstallResult
import com.andsi.airlyrics.app.update.AppUpdateModel
import com.andsi.airlyrics.app.update.AppUpdateRelease
import com.andsi.airlyrics.app.update.AppUpdateVersion
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.showFullText
import com.andsi.airlyrics.ui.state.restoreAuxiliaryDialogs
import com.andsi.airlyrics.ui.state.confirmOperation
import com.andsi.airlyrics.ui.model.AppUpdateCheckStatus
import com.andsi.airlyrics.ui.model.AppUpdateDownloadState
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.pages.settings.APP_UPDATE_DIALOG
import com.andsi.airlyrics.ui.pages.settings.AboutVersionUpdate
import com.andsi.airlyrics.ui.pages.settings.AppReleaseNotes
import com.andsi.airlyrics.ui.pages.settings.loadBundledReleaseNotes
import com.andsi.airlyrics.ui.pages.settings.showAppUpdateDialog
import com.andsi.airlyrics.ui.theme.colorAccentText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.takeWhile
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class MainActivityAuxiliaryRecreationTest {
    @Test
    @Config(qualifiers = "en-rUS-w400dp-h860dp-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun versionEntryShowsOneReleaseAndRestoresSelection() {
        assumeFalse(BuildConfig.APP_UPDATE_INSTALLER_ENABLED)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val host = activity.graph.uiHost
            val installed = host.getAppVersionName()
            val releases = host.loadBundledReleaseNotes()
            // Use a release with enough notes to exercise scrolling.
            val older = releases.first { it.version == "1.2.9" }
            val entryLabel = host.getString(R.string.ui_app_version_changelog, installed)
            activity.graph.viewModel.selectPage(Page.SETTINGS)
            activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            val about = activity.window.decorView
            await { descendants(about).any { it.contentDescription == entryLabel && it.isShown } }
            previewVersionLayout(about, "about-en")
            val entry = descendants(about).single { it.contentDescription == entryLabel && it.isShown }
            val pill = requireNotNull(visibleText(entry, installed))
            assertTrue(pill.compoundDrawablesRelative.all { it == null })
            val download = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
            val newer = AppReleaseNotes("9.0.0", notes = "Improved\n- Browse release notes by version.")
            host.showAppUpdateDialog(AboutVersionUpdate(newer, download, onCancel = {
                download.value = AppUpdateDownloadState.Idle
            }) {
                download.value = AppUpdateDownloadState.Progress(0)
            })
            val available = host.auxiliaryDialogs.getValue(APP_UPDATE_DIALOG)
            val availableRoot = available.window!!.decorView
            previewVersionLayout(availableRoot, "available-en")
            assertNotNull(visibleText(availableRoot, installed))
            assertNull(visibleText(availableRoot, "there is nothing to do"))
            val hint = requireNotNull(visibleText(availableRoot, "Update available"))
            assertTextFits(hint)
            val shortcut = hint.parent as View
            assertTrue(visibleBounds(shortcut).height() >= host.dp(AirUiTokens.Layout.IconTouchSize))
            assertTrue(shortcut.performClick())
            previewVersionLayout(availableRoot, "latest-en")
            assertNull(visibleText(availableRoot, "Update available"))
            val update = requireNotNull(visibleText(availableRoot, "Update"))
            assertTextFits(update)
            withAnimationFrames {
                val buttonTop = visibleBounds(update).top
                assertTrue(update.performClick())
                previewVersionLayout(availableRoot, "download-compress-en", settleMs = 96)
                assertTrue(update.scaleY < 1f)
                assertTrue(visibleBounds(update).top > buttonTop)
                previewVersionLayout(availableRoot, "download-handoff-en", settleMs = 60)
                previewVersionLayout(availableRoot, "download-start-en", settleMs = 160)
                val bar = descendants(availableRoot).filterIsInstance<ProgressBar>().single { it.isShown }
                assertEquals(0, bar.progress)
                assertFalse(update.isShown)
                val cancel = requireNotNull(visibleText(availableRoot, "Cancel"))
                val startPercent = requireNotNull(visibleText(availableRoot, "0%"))
                assertTrue(visibleBounds(cancel).bottom <= visibleBounds(startPercent).top)
                assertTrue(visibleBounds(cancel).height() >= host.dp(AirUiTokens.Layout.IconTouchSize))
                assertTextFits(cancel)
                assertEquals(0f, cancel.elevation, 0f)
                assertNull(cancel.stateListAnimator)
                assertNull(cancel.outlineProvider)
                val startDrawing = renderedPixels(bar)

                download.value = AppUpdateDownloadState.Progress(37)
                previewVersionLayout(availableRoot, "download-fill-en", settleMs = 240)
                previewVersionLayout(availableRoot, "download-stalled-en", settleMs = 900)
                assertEquals(37f, bar.createAccessibilityNodeInfo().rangeInfo.current, 0f)
                assertTextFits(requireNotNull(visibleText(availableRoot, "37%")))
                val stalledDrawing = renderedPixels(bar)
                assertFalse(startDrawing.contentEquals(stalledDrawing))
                previewVersionLayout(availableRoot, "download-still-en", settleMs = 700)
                assertArrayEquals(stalledDrawing, renderedPixels(bar))

                download.value = AppUpdateDownloadState.Progress(74)
                previewVersionLayout(availableRoot, "download-jump-en", settleMs = 0)
                assertEquals(74, bar.progress)
                assertArrayEquals(stalledDrawing, renderedPixels(bar))
                previewVersionLayout(availableRoot, "download-resume-en", settleMs = 176)
                assertFalse(stalledDrawing.contentEquals(renderedPixels(bar)))

                download.value = AppUpdateDownloadState.Progress(100)
                previewVersionLayout(availableRoot, "download-completed-en", settleMs = 2_200)
                assertEquals(100, bar.progress)
                val percent = requireNotNull(visibleText(availableRoot, "100%"))
                assertTextFits(percent)
                previewVersionLayout(availableRoot, "download-short-en", widthDp = 560, heightDp = 300, settleMs = 0)
                assertTextFits(percent)
            }
            available.dismiss()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)

            host.showAppUpdateDialog()
            val dialog = host.auxiliaryDialogs.getValue(APP_UPDATE_DIALOG)
            val root = dialog.window!!.decorView
            previewVersionLayout(root, "current-en")
            val pager = descendants(root).filterIsInstance<ViewPager2>().single()
            val panel = pager.parent as View
            val panelBounds = visibleBounds(panel)
            assertEquals(host.dp(352), panelBounds.height())
            assertNull(visibleText(root, "Changelog"))
            assertNull(visibleText(root, "Versions"))
            val footer = requireNotNull(visibleText(root, "there is nothing to do"))
            assertEquals(host.dp(AirUiTokens.Space.DialogBottom), panelBounds.bottom - visibleBounds(footer).bottom)
            val selector = requireNotNull(visibleText(root, installed))
            assertEquals(host.colorAccentText, selector.currentTextColor)
            assertEquals(host.getString(R.string.ui_app_version_select, installed), selector.contentDescription)
            val selectorTop = visibleBounds(selector).top

            withAnimationFrames {
                assertTrue(selector.performClick())
                ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)
                val list = awaitVersionList()
                assertEquals(0, list.checkedItemPosition)
                val layers = generateSequence<View>(list) { it.parent as? View }.toList()
                val reveal = awaitRevealFrame(layers)
                previewVersionLayout(root, "picker-motion-en", settleMs = 0, overlay = list.rootView)
                previewVersionLayout(root, "picker-en", overlay = list.parent as View)
                assertNull(reveal.clipBounds)
                selectRelease(list, older.version)
            }
            previewVersionLayout(root, "history-en")
            assertNull(versionList())
            assertNull(visibleText(root, "there is nothing to do"))
            val olderTitle = requireNotNull(visibleText(root, older.version))
            assertEquals(selectorTop, visibleBounds(olderTitle).top)
            assertEquals(panelBounds, visibleBounds(panel))

            val scroll = descendants(pager).filterIsInstance<NestedScrollView>().first {
                it.getGlobalVisibleRect(Rect()) && it.canScrollVertically(1)
            }
            scroll.scrollTo(0, scroll.getChildAt(0).height)
            assertTrue(scroll.scrollY > 0)
            assertEquals(selectorTop, visibleBounds(olderTitle).top)
            previewVersionLayout(root, "history-scrolled-en")

            val adjacent = releases[releases.indexOf(older) + 1]
            assertTrue(pager.beginFakeDrag())
            assertTrue(pager.fakeDragBy(-pager.width.toFloat()))
            assertTrue(pager.endFakeDrag())
            previewVersionLayout(root, "swiped-en")
            assertNotNull(visibleText(root, adjacent.version))
            assertEquals(panelBounds, visibleBounds(panel))

            controller.recreate()
            val restoredHost = controller.get().graph.uiHost
            await { restoredHost.auxiliaryDialogs[APP_UPDATE_DIALOG]?.isShowing == true }
            val restored = restoredHost.auxiliaryDialogs.getValue(APP_UPDATE_DIALOG)
            val restoredRoot = restored.window!!.decorView
            previewVersionLayout(restoredRoot, "restored-en")
            assertNotNull(visibleText(restoredRoot, adjacent.version))
            assertNull(visibleText(restoredRoot, installed))
            val close = descendants(restoredRoot).single {
                it.contentDescription == restoredHost.getString(R.string.ui_close) && it.hasOnClickListeners()
            }
            assertTrue(close.performClick())
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertFalse(restored.isShowing)
            assertNull(restoredHost.interactions.read("aux.$APP_UPDATE_DIALOG"))
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h700dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowChineseUpdaterChecksDownloadsAndInstalls() {
        assumeTrue(BuildConfig.APP_UPDATE_INSTALLER_ENABLED)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.get()
            val release = requireNotNull(AppUpdateRelease.create(
                AppUpdateVersion(9, 0, 0), 3L, "0".repeat(64), LocalDate.of(2026, 11, 20),
                "### Improved\n* Browse release notes by version."
            ))
            val response = AtomicReference(CompletableDeferred<AppUpdateRelease?>().apply { complete(null) })
            val downloaded = MutableStateFlow(0)
            var attempts = 0
            var installs = 0
            val model = ViewModelProvider(activity, viewModelFactory {
                initializer {
                    AppUpdateModel(
                        activity.applicationContext,
                        AppUpdateChecker(activity.applicationContext) { response.get().await() },
                        prepareApk = { candidate, onProgress ->
                            assertEquals(release, candidate)
                            if (++attempts == 1) throw IOException("Offline")
                            downloaded.takeWhile { it < 100 }.collect { onProgress(it) }
                        },
                        installApk = { owner, candidate ->
                            assertEquals(release, candidate)
                            when (++installs) {
                                1 -> AppUpdateInstallResult.PermissionRequired(Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.fromParts("package", owner.packageName, null)
                                ))
                                2 -> AppUpdateInstallResult.Cancelled
                                else -> AppUpdateInstallResult.Installed
                            }
                        }
                    )
                }
            })[AppUpdateModel::class.java]
            controller.setup().visible()
            val host = activity.graph.uiHost
            assertSame(model.availableUpdate, host.appUpdates?.availableUpdate)
            await("The automatic update check should finish") { model.checkStatus.value == AppUpdateCheckStatus.Complete }
            val installed = host.getAppVersionName()
            val entryLabel = host.getString(R.string.ui_app_version_changelog, installed)
            activity.graph.viewModel.selectPage(Page.SETTINGS)
            activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            val about = activity.window.decorView
            await { descendants(about).any { it.contentDescription == entryLabel && it.isShown } }
            previewVersionLayout(about, "about-zh-cn")
            val entry = descendants(about).single { it.contentDescription == entryLabel && it.isShown }
            assertTextFits(requireNotNull(visibleText(entry, installed)))

            host.showAppUpdateDialog()
            val current = host.auxiliaryDialogs.getValue(APP_UPDATE_DIALOG)
            val currentRoot = current.window!!.decorView
            previewVersionLayout(currentRoot, "current-zh-cn")
            assertTextFits(requireNotNull(visibleText(currentRoot, "今日无事可做")))
            assertNotNull(visibleText(currentRoot, installed))
            val chooser = descendants(currentRoot).single {
                it.contentDescription == host.getString(R.string.ui_app_version_select, installed)
            }
            assertTrue(chooser.performClick())
            val list = awaitVersionList()
            previewVersionLayout(currentRoot, "picker-zh-cn", overlay = list.parent as View)
            assertTextFits(requireNotNull(visibleText(list, installed)))
            selectRelease(list, installed)
            current.dismiss()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)

            val newRelease = CompletableDeferred<AppUpdateRelease?>()
            response.set(newRelease)
            assertTrue(entry.performClick())
            val root = host.auxiliaryDialogs.getValue(APP_UPDATE_DIALOG).window!!.decorView
            await { model.checkStatus.value == AppUpdateCheckStatus.Checking }
            previewVersionLayout(root, "checking-zh-cn")
            assertTextFits(requireNotNull(visibleText(root, host.getString(R.string.ui_app_update_checking))))
            newRelease.complete(release)
            await { model.availableUpdate.value == release }
            previewVersionLayout(root, "available-zh-cn")
            assertEquals(host.getString(R.string.ui_app_update_available, installed), entry.contentDescription)
            val pager = descendants(root).filterIsInstance<ViewPager2>().single()
            assertNotNull(visibleText(root, installed))
            assertNull(visibleText(root, "今日无事可做"))
            assertNull(visibleText(root, "更新"))
            val hint = requireNotNull(visibleText(root, "有可用更新"))
            assertTextFits(hint)

            val shortcut = hint.parent as View
            assertTrue(shortcut.performClick())
            previewVersionLayout(root, "latest-zh-cn")
            assertEquals(0, pager.currentItem)
            val latest = release.version.toString()
            assertTrue(descendants(root).filterIsInstance<TextView>().any { "Browse release notes by version." in it.text })
            assertNotNull(visibleText(root, latest))
            assertNull(visibleText(root, installed))
            assertNull(visibleText(root, "有可用更新"))
            val update = requireNotNull(visibleText(root, "更新"))
            assertTextFits(update)
            assertTrue(update.performClick())
            previewVersionLayout(root, "download-retry-zh-cn")
            val retry = requireNotNull(visibleText(root, "重试下载"))
            assertTextFits(retry)
            assertTrue(retry.performClick())
            downloaded.value = 42
            previewVersionLayout(root, "download-zh-cn", settleMs = 1_100)
            val percentage = requireNotNull(visibleText(root, "42%"))
            assertTextFits(percentage)
            assertFalse(update.isShown)
            val cancelLabel = host.getString(R.string.ui_cancel)
            val cancel = requireNotNull(visibleText(root, cancelLabel))
            assertTextFits(cancel)
            assertTrue(visibleBounds(cancel).bottom <= visibleBounds(percentage).top)
            assertTrue(cancel.performClick())
            previewVersionLayout(root, "download-cancelled-zh-cn")
            assertEquals(AppUpdateDownloadState.Idle, model.state.value)
            assertEquals(0, downloaded.subscriptionCount.value)
            assertNull(visibleText(root, "42%"))
            assertTrue(requireNotNull(visibleText(root, "更新")).performClick())
            previewVersionLayout(root, "download-restarted-zh-cn", settleMs = 1_100)
            assertNotNull(visibleText(root, "42%"))
            controller.recreate()
            val restoredHost = controller.get().graph.uiHost
            await { restoredHost.auxiliaryDialogs[APP_UPDATE_DIALOG]?.isShowing == true }
            val restored = restoredHost.auxiliaryDialogs.getValue(APP_UPDATE_DIALOG)
            val restoredRoot = restored.window!!.decorView
            previewVersionLayout(restoredRoot, "progress-restored-zh-cn")
            assertNotNull(visibleText(restoredRoot, latest))
            val bar = descendants(restoredRoot).filterIsInstance<ProgressBar>().single { it.isShown }
            assertEquals(42, bar.progress)
            assertEquals(3, attempts)
            downloaded.value = 100
            previewVersionLayout(restoredRoot, "download-completed-zh-cn", settleMs = 2_200)
            assertTextFits(requireNotNull(visibleText(restoredRoot, "100%")))
            assertNull(visibleText(restoredRoot, cancelLabel))
            val installLabel = restoredHost.getString(R.string.ui_app_update_ready_install)
            val install = requireNotNull(visibleText(restoredRoot, installLabel))
            assertTextFits(install)
            assertTrue(install.performClick())
            previewVersionLayout(restoredRoot, "download-permission-zh-cn")
            assertEquals(AppUpdateDownloadState.PermissionRequired, model.state.value)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(controller.get()).nextStartedActivity.action)
            shadowOf(controller.get().packageManager).setCanRequestPackageInstalls(true)
            controller.pause().resume()
            previewVersionLayout(restoredRoot, "download-install-cancelled-zh-cn")
            assertEquals(2, installs)
            assertEquals(AppUpdateDownloadState.Ready, model.state.value)
            assertTrue(requireNotNull(visibleText(restoredRoot, installLabel)).performClick())
            previewVersionLayout(restoredRoot, "download-installed-zh-cn")
            assertEquals(AppUpdateDownloadState.Installed, model.state.value)
            val close = descendants(restoredRoot).single {
                it.contentDescription == restoredHost.getString(R.string.ui_close) && it.hasOnClickListeners()
            }
            withAnimationFrames {
                assertTrue(close.performClick())
                previewVersionLayout(restoredRoot, "download-closing-zh-cn", settleMs = 64)
                assertTrue(restored.isShowing)
                assertEquals(AppUpdateDownloadState.Idle, model.state.value)
                assertNotNull(visibleText(restoredRoot, "100%"))
                assertNull(visibleText(restoredRoot, "更新"))
                assertEquals(1f, (bar.parent as View).alpha, 0f)
            }
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertFalse(restored.isShowing)
            controller.get().graph.viewModel.openSettingsSubPage(SettingsSubPage.HOME)
            val settings = controller.get().window.decorView
            val hintLabel = restoredHost.getString(R.string.ui_app_update_available_label)
            await { descendants(settings).any { ViewCompat.getStateDescription(it) == hintLabel } }
            previewVersionLayout(settings, "settings-update-dot-zh-cn")
        }
    }

    @Test
    @Config(qualifiers = "es-rES-w320dp-h700dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun SpanishStatusFitsOnANarrowScreen() {
        assumeFalse(BuildConfig.APP_UPDATE_INSTALLER_ENABLED)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val host = controller.get().graph.uiHost
            host.showAppUpdateDialog()
            val root = host.auxiliaryDialogs.getValue(APP_UPDATE_DIALOG).window!!.decorView
            previewVersionLayout(root, "current-es")
            assertTextFits(requireNotNull(visibleText(root, "...el software ya está actualizado.")))
            assertTextFits(requireNotNull(visibleText(root, host.getAppVersionName())))
            previewVersionLayout(root, "short-es", widthDp = 560, heightDp = 300)
            val panel = descendants(root).filterIsInstance<ViewPager2>().single().parent as View
            assertTrue(visibleBounds(panel).height() < host.dp(352))
            assertTextFits(requireNotNull(visibleText(root, "...el software ya está actualizado.")))
        }
    }

    private inline fun withAnimationFrames(block: () -> Unit) {
        val wasPaused = ShadowChoreographer.isPaused()
        val oldFrameDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        try {
            block()
        } finally {
            ShadowChoreographer.setPaused(wasPaused)
            ShadowChoreographer.setFrameDelay(oldFrameDelay)
        }
    }

    private fun awaitRevealFrame(views: List<View>): View {
        repeat(16) {
            views.firstOrNull { view ->
                val clip = view.clipBounds ?: return@firstOrNull false
                clip.height() in 1 until view.height
            }?.let { return it }
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        }
        error("The reveal never showed an intermediate frame")
    }

    private fun versionList(): ListView? = WindowInspector.getGlobalWindowViews()
        .asSequence()
        .flatMap { descendants(it).asSequence() }
        .filterIsInstance<ListView>()
        .firstOrNull { it.isShown && it.adapter?.getItem(0) is AppReleaseNotes }

    private fun awaitVersionList(): ListView {
        await { (versionList()?.childCount ?: 0) > 0 }
        return requireNotNull(versionList())
    }

    private fun selectRelease(list: ListView, version: String) {
        val position = (0 until list.count).first { (list.adapter.getItem(it) as AppReleaseNotes).version == version }
        val row = requireNotNull(list.getChildAt(position - list.firstVisiblePosition))
        assertTrue(list.performItemClick(row, position, list.adapter.getItemId(position)))
    }

    private fun visibleBounds(view: View): Rect = Rect().also { assertTrue(view.getGlobalVisibleRect(it)) }

    private fun visibleText(root: View, value: String): TextView? =
        descendants(root).filterIsInstance<TextView>()
            .firstOrNull { it.isShown && it.text.toString() == value && it.getGlobalVisibleRect(Rect()) }

    private fun assertTextFits(view: TextView) {
        val layout = requireNotNull(view.layout)
        assertTrue(view.height > 0)
        assertEquals(view.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
    }

    private fun renderedPixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun previewVersionLayout(
        root: View,
        name: String,
        settleMs: Long = 350,
        overlay: View? = null,
        widthDp: Int = root.resources.configuration.screenWidthDp,
        heightDp: Int = root.resources.configuration.screenHeightDp
    ) {
        var remaining = settleMs
        do {
            val step = if (ShadowChoreographer.isPaused()) minOf(remaining, 16L) else remaining
            ShadowLooper.idleMainLooper(step, TimeUnit.MILLISECONDS)
            remaining -= step
        } while (remaining > 0)
        val width = View.MeasureSpec.makeMeasureSpec(widthDp, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(heightDp, View.MeasureSpec.EXACTLY)
        root.measure(width, height)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val directory = System.getenv("AIRLYRICS_VERSION_PREVIEWS")?.let(::File) ?: return
        directory.mkdirs()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            root.draw(canvas)
            overlay?.let {
                val origin = IntArray(2).also(root::getLocationOnScreen)
                val position = IntArray(2).also(it::getLocationOnScreen)
                canvas.translate((position[0] - origin[0]).toFloat(), (position[1] - origin[1]).toFloat())
                it.draw(canvas)
            }
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun longReaderRestoresOnlyOnceAndCancelDeletesItsFile() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val text = "Long text 阅读内容 ".repeat(6000)
            controller.get().graph.uiHost.showFullText("Reader", text)
            val id = controller.get().graph.viewModel.interactions.read("aux.reader")!!.getString("file")!!
            val old = ShadowDialog.getLatestDialog()
            controller.recreate()
            val host = controller.get().graph.uiHost
            host.restoreAuxiliaryDialogs()
            await { host.auxiliaryDialogs["reader"]?.isShowing == true }
            val restored = host.auxiliaryDialogs["reader"]!!
            assertFalse(old.isShowing)
            host.restoreAuxiliaryDialogs()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertSame(restored, host.auxiliaryDialogs["reader"])
            assertTrue(descendants(restored.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == text })
            restored.dismiss()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertNull(host.interactions.read("aux.reader"))
            assertNull(runBlocking { host.readerContent.load(id) })
        }
    }

    @Test fun confirmationClickIsConsumedSynchronouslyAndDoesNotRestore() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val host = controller.get().graph.uiHost
            host.confirmOperation(ConfirmationAction.DeleteAll, "Delete", "All lyrics", host.getString(R.string.ui_delete))
            val dialog = ShadowDialog.getLatestDialog()
            val button = descendants(dialog.window!!.decorView).filterIsInstance<TextView>()
                .first { it.text.toString() == host.getString(R.string.ui_delete) && it.hasOnClickListeners() }
            button.performClick()
            assertNull(host.interactions.read("confirmation"))
            button.performClick()
            controller.recreate()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertNull(controller.get().graph.uiHost.activeConfirmationId)
            assertNull(controller.get().graph.viewModel.interactions.read("confirmation"))
        }
    }

    @Test fun sessionDraftPruningCannotDeleteAnotherWindowsDraft() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { first ->
            val firstStore = LyricsDraftStore.forSession(first.get(), first.get().graph.viewModel.interactions, "reader-drafts")
            firstStore.write("draft-one", "first window").get()
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { second ->
                val secondStore = LyricsDraftStore.forSession(second.get(), second.get().graph.viewModel.interactions, "reader-drafts")
                secondStore.write("draft-two", "second window").get()
                secondStore.prune("draft-two").get()
                assertEquals("first window", firstStore.read("draft-one").get())
                assertEquals("second window", secondStore.read("draft-two").get())
            }
        }
    }

}
