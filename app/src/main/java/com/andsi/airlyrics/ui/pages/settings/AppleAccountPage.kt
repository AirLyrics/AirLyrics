package com.andsi.airlyrics.ui.pages.settings

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.R
import com.andsi.airlyrics.apple.AppleMusicAccount
import com.andsi.airlyrics.apple.AppleMusicSessionStore
import com.andsi.airlyrics.apple.AppleMusicTokens
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.actionButton
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.dangerActionButton
import com.andsi.airlyrics.ui.components.pageContainer
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal fun createAppleAccountPage(activity: MainUiHost): View = with(activity) createAppleAccountPage@ {
    val host = this
    val container = pageContainer(activity)
    container.addView(settingsBackHeader(getString(R.string.ui_apple_account)))

    val statusView = TextView(activity).apply {
        textSize = AirUiTokens.TextSize.Button
        setTextColor(colorTextMuted)
        setPadding(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
    }

    fun renderStatus() {
        val session = AppleMusicSessionStore.load(activity)
        statusView.text = if (session == null) {
            getString(R.string.ui_apple_not_logged_in)
        } else {
            getString(R.string.ui_apple_logged_in_as, session.storefront.uppercase())
        }
        statusView.setTextColor(if (session == null) colorTextMuted else colorAccent)
    }
    renderStatus()

    container.addView(card(activity) {
        addView(bigText(activity, getString(R.string.ui_apple_account)))
        addView(TextView(activity).apply {
            setText(R.string.ui_apple_account_summary)
            textSize = AirUiTokens.TextSize.Body
            setTextColor(colorTextMuted)
            setPadding(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
        })
        addView(statusView)

        addView(actionButton(activity, getString(R.string.ui_apple_login)) {
            uiActions.openSettingsSubPage(SettingsSubPage.APPLE_LOGIN)
        })

        addView(actionButton(activity, getString(R.string.ui_apple_verify)) {
            statusView.text = getString(R.string.ui_apple_verifying)
            activity.activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { AppleMusicAccount.refresh(activity) }
                statusView.text = result.fold(
                    onSuccess = { getString(R.string.ui_apple_logged_in_as, it.storefront.uppercase()) },
                    onFailure = { getString(R.string.ui_apple_failed, it.message.orEmpty()) }
                )
            }
        })

        val logoutButton = dangerActionButton(activity, getString(R.string.ui_apple_logout)) {
            AppleMusicAccount.logout(activity)
            renderStatus()
        }
        logoutButton.layoutParams = (logoutButton.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = host.dp(AirUiTokens.Space.Lg)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(logoutButton)
    })

    return scroll(activity, container)
}

/** Full-screen sign-in page: Apple's own login UI runs inside this WebView. */
@SuppressLint("SetJavaScriptEnabled")
internal fun createAppleLoginPage(activity: MainUiHost): View = with(activity) createAppleLoginPage@ {
    val host = this
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    root.addView(settingsBackHeader(getString(R.string.ui_apple_login)))

    val statusView = TextView(activity).apply {
        text = getString(R.string.ui_apple_login_hint)
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorTextMuted)
        gravity = Gravity.CENTER
        setPadding(
            host.dp(AirUiTokens.Space.Lg),
            host.dp(AirUiTokens.Space.Sm),
            host.dp(AirUiTokens.Space.Lg),
            host.dp(AirUiTokens.Space.Sm)
        )
    }
    root.addView(statusView)

    fun finishWithToken(token: String) {
        statusView.text = getString(R.string.ui_apple_verifying)
        activity.activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppleMusicAccount.completeLogin(activity, token) }
            result.fold(
                onSuccess = {
                    statusView.text = getString(R.string.ui_apple_logged_in_as, it.storefront.uppercase())
                    uiActions.openSettingsSubPage(SettingsSubPage.APPLE_ACCOUNT)
                },
                onFailure = {
                    statusView.text = getString(R.string.ui_apple_failed, it.message.orEmpty())
                }
            )
        }
    }

    val webView = WebView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = AppleMusicLoginCapture.USER_AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (!url.contains("music.apple.com")) return
                activity.activity.lifecycleScope.launch {
                    AppleMusicLoginCapture.capture(view)?.let(::finishWithToken)
                }
            }
        }
        loadUrl("https://music.apple.com/cn/login")
    }
    root.addView(webView)

    root.addView(actionButton(activity, getString(R.string.ui_apple_login_capture)) {
        activity.activity.lifecycleScope.launch {
            val token = AppleMusicLoginCapture.capture(webView)
            if (token == null) {
                statusView.text = getString(R.string.ui_apple_login_not_found)
            } else {
                finishWithToken(token)
            }
        }
    })

    return root
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
