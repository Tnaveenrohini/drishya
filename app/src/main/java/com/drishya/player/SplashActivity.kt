package com.drishya.player

import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/** 5 second opening animation: kk logo draw → FLIKK → tricolor → Made in India. */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.splash_logo)
        val name = findViewById<View>(R.id.splash_name)
        val flag = findViewById<View>(R.id.splash_flag)
        val tagline = findViewById<View>(R.id.splash_tagline)
        val content = findViewById<View>(R.id.splash_content)

        // Tap chesthe skip
        findViewById<View>(R.id.splash_root).setOnClickListener { openHome() }

        // 0.0s – logo strokes draw avthayi + konchem zoom
        logo.scaleX = 0.8f
        logo.scaleY = 0.8f
        logo.animate().scaleX(1f).scaleY(1f).setDuration(900)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        (logo.drawable as? Animatable)?.start()

        // 1.1s – fast-forward "swoosh"
        handler.postDelayed({
            logo.animate().translationX(28f).setDuration(180).withEndAction {
                logo.animate().translationX(0f).setDuration(260)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }, 1100)

        // 1.3s – FLIKK
        name.translationY = 30f
        handler.postDelayed({
            name.animate().alpha(1f).translationY(0f).setDuration(500).start()
        }, 1300)

        // 1.9s – tricolor line
        handler.postDelayed({
            flag.animate().scaleX(1f).setDuration(550).start()
        }, 1900)

        // 2.5s – MADE IN INDIA
        tagline.translationY = 16f
        handler.postDelayed({
            tagline.animate().alpha(1f).translationY(0f).setDuration(500).start()
        }, 2500)

        // 4.4s – fade out, 5.0s – home
        handler.postDelayed({
            content.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(500).start()
        }, 4400)
        handler.postDelayed({ openHome() }, 4950)
    }

    private fun openHome() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
