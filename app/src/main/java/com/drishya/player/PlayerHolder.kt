package com.drishya.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * Okate player app motham ki. Activity close ayina (background play) idi
 * PlaybackService lo bratike untundi.
 */
@OptIn(UnstableApi::class)
object PlayerHolder {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var sleepAt = 0L
    var sleepAtEnd = false
        private set

    private val sleepRunnable = Runnable {
        player?.pause()
        sleepAt = 0L
    }

    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        // FFmpeg: phone decode cheyyaleni audio (AC3, DTS…) ki
        val renderers = DefaultRenderersFactory(app)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(app, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) {
                    cancelSleep()
                }
            }
        })
        player = p
        return p
    }

    fun peek(): ExoPlayer? = player

    fun release() {
        cancelSleep()
        player?.release()
        player = null
    }

    fun setSleep(minutes: Int) {
        cancelSleep()
        sleepAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(sleepRunnable, minutes * 60_000L)
    }

    fun setSleepAtEnd() {
        cancelSleep()
        sleepAtEnd = true
        player?.pauseAtEndOfMediaItems = true
    }

    fun cancelSleep() {
        handler.removeCallbacks(sleepRunnable)
        sleepAt = 0L
        if (sleepAtEnd) {
            sleepAtEnd = false
            player?.pauseAtEndOfMediaItems = false
        }
    }

    fun sleepActive() = sleepAt > 0L || sleepAtEnd
}
