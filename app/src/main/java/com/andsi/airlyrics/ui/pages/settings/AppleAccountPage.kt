package com.andsi.airlyrics.ui.pages.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.R
import com.andsi.airlyrics.apple.AppleMusicAccount
import com.andsi.airlyrics.apple.AppleMusicSessionStore
import com.andsi.airlyrics.apple.AppleMusicTokens
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorDanger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal fun createAppleAccountPage(activity: MainUiHost): View = with(activity) {
    val container = pageContainer(activity, animateChanges = false)
    container.addView(settingsBackHeader(getString(R.string.ui_apple_account)))
    container.addView(createAppleAccountCard(activity))
    scroll(activity, container)
}

private fun createAppleAccountCard(activity: MainUiHost): View = with(activity) {
    val status = normalText(activity, "").apply {
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val loginContainer = FrameLayout(activity).apply {
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
    }
    var panel: AppleLoginPanel? = null
    var animator: ValueAnimator? = null
    var expanded = false
    var verification: Job? = null
    var logoutDialog: Dialog? = null
    lateinit var setExpanded: (Boolean) -> Unit
    lateinit var renderStatus: () -> Unit
    val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { setExpanded(false) }
    }

    val login = actionButton(activity, getString(R.string.ui_apple_login)) {
        setExpanded(!expanded)
    }
    val verify = actionButton(activity, getString(R.string.ui_apple_verify)) {}
    val logout = TextView(activity).apply {
        setText(R.string.ui_apple_logout)
        textSize = AirUiTokens.TextSize.Body
        setTextColor(ColorUtils.blendARGB(colorTextMuted, colorDanger, 0.55f))
        gravity = Gravity.CENTER
        minimumWidth = dp(AirUiTokens.Layout.IconTouchSize)
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(dp(AirUiTokens.Space.Lg), 0, dp(AirUiTokens.Space.Lg), 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(AirUiTokens.Space.Xl) }
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            if (logoutDialog == null) {
                logoutDialog = showAirDialog(
                    title = getString(R.string.ui_apple_logout_confirm),
                    positiveText = getString(R.string.ui_apple_logout),
                    negativeText = getString(R.string.ui_cancel),
                    onUserDismiss = { logoutDialog = null },
                    onPositive = {
                        verification?.cancel()
                        AppleMusicAccount.logout(activity)
                        renderStatus()
                    }
                )
            }
        }
    }
    renderStatus = {
        val session = AppleMusicSessionStore.load(activity)
        status.text = session?.let { getString(R.string.ui_apple_logged_in_as, it.storefront.uppercase()) }
            ?: getString(R.string.ui_apple_not_logged_in)
        verify.isVisible = session != null
        verify.isEnabled = true
        logout.isVisible = session != null
        login.isVisible = session == null || expanded
    }
    verify.setOnClickListener {
        verify.isEnabled = false
        status.setText(R.string.ui_apple_verifying)
        verification = activity.activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppleMusicAccount.refresh(activity) }
            renderStatus()
            result.exceptionOrNull()?.let { status.text = getString(R.string.ui_apple_failed, it.message.orEmpty()) }
            playTinyPulse(status)
        }
    }

    fun disposePanel() {
        panel?.dispose?.invoke()
        panel = null
        loginContainer.removeAllViews()
    }

    setExpanded = { open ->
        expanded = open
        backCallback.isEnabled = open
        animator?.removeAllListeners()
        animator?.cancel()
        if (open && panel == null) {
            panel = createAppleLoginPanel(activity) {
                renderStatus()
                setExpanded(false)
            }
            loginContainer.addView(panel!!.view)
        }
        login.setText(if (open) R.string.ui_cancel else R.string.ui_apple_login)
        val startHeight = if (loginContainer.isVisible) loginContainer.height else 0
        val targetHeight = if (open) {
            loginContainer.measure(
                View.MeasureSpec.makeMeasureSpec(
                    loginContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels,
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            loginContainer.measuredHeight
        } else 0
        loginContainer.isVisible = true
        val startAlpha = if (startHeight == 0) 0f else loginContainer.alpha
        animator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            duration = AirUiTokens.Motion.ThemePickerExpandMs
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                loginContainer.layoutParams = loginContainer.layoutParams.apply { height = it.animatedValue as Int }
                loginContainer.alpha = startAlpha + ((if (open) 1f else 0f) - startAlpha) * it.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    loginContainer.isVisible = open
                    loginContainer.layoutParams = loginContainer.layoutParams.apply {
                        height = if (open) ViewGroup.LayoutParams.WRAP_CONTENT else 0
                    }
                    if (!open) {
                        disposePanel()
                        renderStatus()
                    }
                }
            })
            start()
        }
    }
    renderStatus()
    card(activity) {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status)
            addView(logout)
        })
        addView(login)
        addView(loginContainer)
        addView(verify)
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                activity.activity.onBackPressedDispatcher.addCallback(activity.activity, backCallback)
            }
            override fun onViewDetachedFromWindow(v: View) {
                backCallback.remove()
                // Wait until detachment completes before disposing the login view.
                activity.activity.window.decorView.post {
                    if (!isAttachedToWindow) {
                        animator?.removeAllListeners()
                        animator?.cancel()
                        verification?.cancel()
                        logoutDialog?.dismissAirDialogImmediately()
                        logoutDialog = null
                        disposePanel()
                    }
                }
            }
        })
    }
}

private data class AppleLoginPanel(val view: View, val dispose: () -> Unit)

/** Created on demand inside the account card; disposed after collapse or leaving the page. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createAppleLoginPanel(activity: MainUiHost, onSignedIn: () -> Unit): AppleLoginPanel = with(activity) {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }
    val status = normalText(activity, getString(R.string.ui_loading))
    var captured = false
    var destroyed = false
    var loginJob: Job? = null
    var captureJob: Job? = null

    fun showStatus(message: String) {
        status.text = message
        status.isVisible = true
    }

    fun finishWithToken(token: String) {
        if (destroyed || captured) return
        captured = true
        showStatus(getString(R.string.ui_apple_verifying))
        loginJob = activity.activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppleMusicAccount.completeLogin(activity, token) }
            if (destroyed) return@launch
            result.fold(
                onSuccess = { onSignedIn() },
                onFailure = {
                    captured = false
                    showStatus(getString(R.string.ui_apple_failed, it.message.orEmpty()))
                }
            )
        }
    }

    val webView = WebView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp((resources.configuration.screenHeightDp * 0.6f).toInt().coerceIn(320, 560))
        )
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = AppleMusicLoginCapture.USER_AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // Let Apple's form scroll independently of the surrounding lyrics settings.
        setOnTouchListener { view, event ->
            view.parent.requestDisallowInterceptTouchEvent(
                event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
            )
            false
        }
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (!destroyed && request.isForMainFrame) {
                    showStatus(getString(R.string.ui_apple_failed, error.description))
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed || captured || url.toUri().host != "music.apple.com") return
                status.isVisible = false
            }
        }
        loadUrl("https://music.apple.com/cn/login")
    }
    root.addView(webView)
    root.addView(status)
    root.addView(actionButton(activity, getString(R.string.ui_apple_login_capture)) {
        if (destroyed || captured) return@actionButton
        captureJob?.cancel()
        captureJob = activity.activity.lifecycleScope.launch {
            val token = AppleMusicLoginCapture.capture(webView)
            if (destroyed) return@launch
            if (token == null) showStatus(getString(R.string.ui_apple_login_not_found)) else finishWithToken(token)
        }
    })
    AppleLoginPanel(root) {
        if (!destroyed) {
            destroyed = true
            captureJob?.cancel()
            loginJob?.cancel()
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
    }
}

/** Best-effort extraction of the media user token after Apple's sign-in page settles. */
internal object AppleMusicLoginCapture {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun capture(webView: WebView): String? = fromCookies() ?: fromPage(webView)

    fun fromCookies(): String? {
        val manager = CookieManager.getInstance()
        val music = AppleMusicTokens.parseMediaUserToken(manager.getCookie("https://music.apple.com"))
        if (music != null) return music
        return AppleMusicTokens.parseMediaUserToken(manager.getCookie("https://amp-api.music.apple.com"))
    }

    suspend fun fromPage(webView: WebView): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val script = """
                (function(){
                  try {
                    if (window.MusicKit && MusicKit.getInstance) {
                      var token = MusicKit.getInstance().musicUserToken;
                      if (token) return token;
                    }
                  } catch (e) {}
                  var match = document.cookie.match(/(?:^|;\s*)media-user-token=([^;]+)/);
                  return match ? decodeURIComponent(match[1]) : '';
                })()
            """.trimIndent()
            webView.evaluateJavascript(script) { raw ->
                val cleaned = raw
                    ?.takeIf { it != "null" }
                    ?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\u003d", "=")
                    ?.trim()
                    .orEmpty()
                continuation.resume(cleaned.ifEmpty { null })
            }
        }
    }
}
