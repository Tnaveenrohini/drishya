package com.drishya.player

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest

/** whisper.cpp native bridge (arm64 phones matrame). */
object WhisperLib {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("flikkwhisper")
            true
        } catch (_: Throwable) {
            false
        }
    }

    interface Listener {
        fun onProgress(p: Int)
    }

    external fun initContext(path: String): Long
    external fun freeContext(ctx: Long)
    external fun cancel()
    external fun resetCancel()
    external fun transcribe(
        ctx: Long, samples: FloatArray, lang: String, threads: Int, translate: Boolean, listener: Listener?,
    ): Array<ByteArray>?
}

/** AI models (Hugging Face nunchi okkasari download). */
object ModelStore {
    data class Model(val id: String, val sizeMb: Int, val bytes: Long)

    val models = listOf(
        Model("tiny", 75, 77_691_713L),
        Model("base", 142, 147_951_465L),
        Model("small", 466, 487_601_967L),
    )

    fun file(c: Context, id: String) = File(c.getExternalFilesDir("models"), "ggml-$id.bin")

    fun ready(c: Context, m: Model): Boolean {
        val f = file(c, m.id)
        return f.exists() && f.length() >= m.bytes * 95 / 100
    }

    fun url(id: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$id.bin"

    fun startDownload(c: Context, m: Model): Long {
        file(c, m.id).delete()
        val req = DownloadManager.Request(Uri.parse(url(m.id)))
            .setTitle("Flikk Play AI model (${m.sizeMb} MB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(c, "models", "ggml-${m.id}.bin")
        return (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }
}

/** Video audio ni 16 kHz mono float ga, chunks ga ichhe decoder. */
object AudioDecoder {
    /**
     * onChunk(samples, startMs) false return chesthe aapestundi.
     * Returns null on success, error message on failure.
     */
    fun decode(
        c: Context, uri: Uri, chunkSec: Int,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray, Long) -> Boolean,
    ): String? {
        val ex = MediaExtractor()
        try {
            if (uri.scheme == "http" || uri.scheme == "https") ex.setDataSource(uri.toString(), HashMap())
            else ex.setDataSource(c, uri, null)
        } catch (e: Exception) {
            return "open"
        }
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                break
            }
        }
        if (track < 0) {
            ex.release()
            return "noaudio"
        }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = try {
            MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(fmt, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            ex.release()
            return "codec"
        }

        val chunkLen = 16_000 * chunkSec
        var buf = FloatArray(chunkLen)
        var fill = 0
        var chunkStartMs = 0L
        var srcRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        var pcmFloat = false
        var last = 0f
        var frac = 0.0
        var first = true
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var stopped = false

        fun push(x: Float) {
            if (first) {
                last = x
                first = false
                return
            }
            val step = srcRate / 16_000.0
            while (frac <= 1.0) {
                buf[fill++] = last + (x - last) * frac.toFloat()
                if (fill == chunkLen) {
                    if (!onChunk(buf, chunkStartMs)) stopped = true
                    chunkStartMs += chunkSec * 1000L
                    buf = FloatArray(chunkLen)
                    fill = 0
                }
                frac += step
            }
            frac -= 1.0
            last = x
        }

        try {
            while (!outputDone && !stopped) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            if (durUs > 0) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        if (pcmFloat) {
                            val fb = ob.asFloatBuffer()
                            while (fb.remaining() >= channels && !stopped) {
                                var sum = 0f
                                repeat(channels) { sum += fb.get() }
                                push(sum / channels)
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            while (sb.remaining() >= channels && !stopped) {
                                var sum = 0f
                                repeat(channels) { sum += sb.get() / 32768f }
                                push(sum / channels)
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (!stopped && fill > 8_000) onChunk(buf.copyOf(fill), chunkStartMs)
        } catch (e: Exception) {
            return "decode"
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
        return null
    }
}

/** Video ki generate chesina AI subtitle files. */
object AiSubs {
    private fun key(uri: String): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun file(c: Context, uri: String) = File(c.getExternalFilesDir("subtitles"), "ai_${key(uri)}.srt")

    fun has(c: Context, uri: String) = file(c, uri).let { it.exists() && it.length() > 0 }
}

/** AI subtitles generate chese background service. */
class SubtitleService : Service() {
    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MODEL = "model"
        const val EXTRA_LANG = "lang"
        const val EXTRA_TRANSLATE = "translate"
        private const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "ai_subs"
        private const val NOTIF_ID = 4503

        const val IDLE = 0
        const val RUNNING = 1
        const val DONE = 2
        const val FAILED = 3

        @Volatile var state = IDLE
        @Volatile var progress = 0
        @Volatile var jobUri: String? = null
        @Volatile var error: String? = null

        fun start(c: Context, uri: String, title: String, model: String, lang: String, translate: Boolean) {
            ContextCompat.startForegroundService(c, Intent(c, SubtitleService::class.java)
                .putExtra(EXTRA_URI, uri).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_MODEL, model)
                .putExtra(EXTRA_LANG, lang).putExtra(EXTRA_TRANSLATE, translate))
        }
    }

    private var worker: Thread? = null
    private var title = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            try { WhisperLib.cancel() } catch (_: Throwable) {}
            worker?.interrupt()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ai_subs), NotificationManager.IMPORTANCE_LOW))
            }
        }
        val n = notif(getString(R.string.dl_preparing), 0, true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        if (state == RUNNING || intent == null) return START_NOT_STICKY
        val uri = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val model = intent.getStringExtra(EXTRA_MODEL) ?: "base"
        val lang = intent.getStringExtra(EXTRA_LANG) ?: "auto"
        val translate = intent.getBooleanExtra(EXTRA_TRANSLATE, false)
        state = RUNNING
        progress = 0
        error = null
        jobUri = uri
        worker = Thread({ run(uri, model, lang, translate) }, "ai-subs").apply { start() }
        return START_NOT_STICKY
    }

    private fun update(text: String, pct: Int, indeterminate: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(text, pct, indeterminate))
    }

    private fun run(uri: String, modelId: String, lang: String, translate: Boolean) {
        var ctx = 0L
        try {
            if (!WhisperLib.available) throw IllegalStateException(getString(R.string.ai_unsupported))
            val m = ModelStore.models.first { it.id == modelId }
            // 1. Model download (okkasari matrame)
            if (!ModelStore.ready(this, m)) {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val id = ModelStore.startDownload(this, m)
                while (true) {
                    if (Thread.interrupted()) throw InterruptedException()
                    var status = DownloadManager.STATUS_PENDING
                    var pct = 0
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) {
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val d = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val t = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            if (t > 0) pct = (d * 100 / t).toInt()
                        }
                    }
                    if (status == DownloadManager.STATUS_SUCCESSFUL) break
                    if (status == DownloadManager.STATUS_FAILED) throw IllegalStateException(getString(R.string.ai_model_failed))
                    update(getString(R.string.ai_model_dl, pct), pct)
                    Thread.sleep(1500)
                }
            }
            // 2. Model load
            update(getString(R.string.ai_loading), 0, true)
            WhisperLib.resetCancel()
            ctx = WhisperLib.initContext(ModelStore.file(this, m.id).absolutePath)
            if (ctx == 0L) throw IllegalStateException(getString(R.string.ai_model_failed))

            // 3. Audio → text, 2 nimishala chunks
            val out = AiSubs.file(this, uri)
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".part")
            val sb = StringBuilder()
            var index = 1
            var decodePct = 0f
            val threads = minOf(Runtime.getRuntime().availableProcessors(), 4)
            val chunkSec = 120
            val err = AudioDecoder.decode(this, Uri.parse(uri), chunkSec, { decodePct = it }) { samples, startMs ->
                if (Thread.currentThread().isInterrupted) return@decode false
                val segs = WhisperLib.transcribe(ctx, samples, lang, threads.coerceAtLeast(1), translate,
                    object : WhisperLib.Listener {
                        override fun onProgress(p: Int) {
                            progress = (decodePct * 100).toInt().coerceIn(0, 99)
                            update(getString(R.string.ai_working, progress), progress)
                        }
                    }) ?: return@decode false
                segs.forEach { raw ->
                    val line = String(raw, Charsets.UTF_8)
                    val parts = line.split('|', limit = 3)
                    if (parts.size == 3) {
                        val t0 = startMs + (parts[0].toLongOrNull() ?: 0L) * 10
                        val t1 = startMs + (parts[1].toLongOrNull() ?: 0L) * 10
                        val text = parts[2].trim()
                        if (text.isNotEmpty() && !text.startsWith("[") && !text.startsWith("(")) {
                            sb.append(index++).append('\n')
                                .append(srt(t0)).append(" --> ").append(srt(maxOf(t1, t0 + 500))).append('\n')
                                .append(text).append("\n\n")
                        }
                    }
                }
                tmp.writeText(sb.toString())
                progress = (decodePct * 100).toInt().coerceIn(0, 99)
                update(getString(R.string.ai_working, progress), progress)
                true
            }
            if (err != null) throw IllegalStateException(getString(R.string.ai_audio_failed))
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (sb.isEmpty()) throw IllegalStateException(getString(R.string.ai_no_speech))
            tmp.renameTo(out)
            progress = 100
            state = DONE
            finish(getString(R.string.ai_ready), true)
        } catch (e: InterruptedException) {
            state = FAILED
            error = null
            finish(getString(R.string.ai_cancelled), false)
        } catch (e: Throwable) {
            state = FAILED
            error = e.message
            finish(e.message ?: getString(R.string.ai_failed), false)
        } finally {
            if (ctx != 0L) try { WhisperLib.freeContext(ctx) } catch (_: Throwable) {}
        }
    }

    private fun srt(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val f = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, f)
    }

    private fun finish(text: String, ok: Boolean) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_subtitle)
            .setContentTitle(if (ok) getString(R.string.ai_ready) else getString(R.string.ai_subs))
            .setContentText(if (ok) title else text)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notif(text: String, pct: Int, indeterminate: Boolean) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_subtitle)
        .setContentTitle(getString(R.string.ai_subs) + if (title.isNotBlank()) " · $title" else "")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, pct, indeterminate)
        .addAction(
            0, getString(R.string.cancel),
            PendingIntent.getService(
                this, 9, Intent(this, SubtitleService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
}
