package com.drishya.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/** "Now playing" ki 3 bars equalizer animation (screenshot lo laga). */
class EqBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private var phase = 0f
    var playing = true
        set(value) {
            field = value
            if (value) animator.start() else animator.pause()
            invalidate()
        }

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bars = 3
        val gap = width * 0.12f
        val bw = (width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val f = if (playing) 0.35f + 0.65f * abs(sin(phase + i * 1.3f)) else 0.4f
            val h = height * f
            val left = i * (bw + gap)
            canvas.drawRect(left, height - h, left + bw, height.toFloat(), paint)
        }
    }
}
