package com.drishya.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

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
        val renderers = FlikkRenderers(app)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        // smb:// (PC / NAS) + content / file / http anni okate factory lo
        val dataSources = DataSource.Factory { FlikkDataSource(app) }
        val p = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
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

    // ---------------------------------------------------------------- audio delay (lip-sync)

    @Volatile
    internal var delaySink: DelayAudioSink? = null
    var audioDelayMs = 0L
        private set

    /** + = audio late ga vastundi, − = audio mundu vastundi */
    fun setAudioDelay(ms: Long) {
        audioDelayMs = ms
        delaySink?.offsetUs = ms * 1000
    }

    fun release() {
        cancelSleep()
        delaySink = null
        audioDelayMs = 0L
        releaseEffects()
        player?.release()
        player = null
    }

    // ---------------------------------------------------------------- equalizer + volume boost (VLC style)

    private var eq: Equalizer? = null
    private var boost: LoudnessEnhancer? = null
    var eqPreset = -1
        private set
    var boostLevel = 0
        private set

    private fun equalizer(): Equalizer? {
        val p = player ?: return null
        if (eq == null) eq = try { Equalizer(0, p.audioSessionId) } catch (_: Throwable) { null }
        return eq
    }

    fun presetNames(): List<String> {
        val e = equalizer() ?: return emptyList()
        return try {
            (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setPreset(index: Int): Boolean {
        val e = equalizer() ?: return false
        return try {
            if (index < 0) {
                e.enabled = false
            } else {
                e.usePreset(index.toShort())
                e.enabled = true
            }
            eqPreset = index
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** level 0 = normal, 1 = +50%, 2 = +100% */
    fun setBoost(level: Int): Boolean {
        val p = player ?: return false
        return try {
            val b = boost ?: LoudnessEnhancer(p.audioSessionId).also { boost = it }
            b.setTargetGain(level * 600)
            b.enabled = level > 0
            boostLevel = level
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun releaseEffects() {
        try { eq?.release() } catch (_: Throwable) {}
        try { boost?.release() } catch (_: Throwable) {}
        eq = null
        boost = null
        eqPreset = -1
        boostLevel = 0
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

/** Audio clock ni konchem mundu/venaka chupinchi audio–video sync marchadam. */
@OptIn(UnstableApi::class)
class DelayAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    @Volatile
    var offsetUs = 0L

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else (p + offsetUs).coerceAtLeast(0L)
    }
}

@OptIn(UnstableApi::class)
private class FlikkRenderers(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?: return null
        return DelayAudioSink(base).also {
            it.offsetUs = PlayerHolder.audioDelayMs * 1000
            PlayerHolder.delaySink = it
        }
    }
}
