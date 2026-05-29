package com.andsi.airlyrics.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.R

class SplashActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val openMainRunnable = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        val splashImage = ImageView(this).apply {
            setImageResource(R.drawable.airlyrics_splash_screen)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF101024.toInt())
        }

        setContentView(splashImage)
        handler.postDelayed(openMainRunnable, SPLASH_DELAY_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openMainRunnable)
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_DELAY_MS = 800L
    }
}
