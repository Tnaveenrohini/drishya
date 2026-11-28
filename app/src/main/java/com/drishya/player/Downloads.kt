package com.drishya.player

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Gallery lo kanipinchela Movies / Pictures lo file save. */
object Saver {
    /** Returns saved content uri (or file uri on old phones), null on failure. */
    fun save(c: Context, name: String, mime: String, folder: String, open: () -> InputStream): Uri? = try {
        val isVideo = mime.startsWith("video")
        val base = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = c.contentResolver.insert(collection, values)!!
            c.contentResolver.openOutputStream(dest)!!.use { o -> open().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            c.contentResolver.update(dest, values, null, null)
            dest
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(base), folder)
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { o -> open().use { it.copyTo(o) } }
            MediaScannerConnection.scanFile(c, arrayOf(f.absolutePath), arrayOf(mime), null)
            Uri.fromFile(f)
        }
    } catch (_: Exception) {
        null
    }

    fun safeName(raw: String, ext: String): String {
        var n = raw.substringBefore('?').substringAfterLast('/').ifBlank { "video" }
        n = Uri.decode(n).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        if (!n.contains('.')) n += ext
        return n
    }
}

/** Link nunchi telusukunna video vivaralu. */
data class LinkInfo(
    val url: String,
    val name: String,
    val mime: String,
    val size: Long,
    val hls: Boolean,
    val qualities: List<Pair<String, String>>, // label to url
)

object LinkProbe {
    private val videoExt = listOf(".mp4", ".mkv", ".webm", ".mov", ".3gp", ".m4v", ".avi", ".ts", ".flv")

    /** Background thread lo call cheyyali. Direct video / HLS kaakapothe null. */
    fun probe(raw: String): LinkInfo? {
        val url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val lower = url.lowercase().substringBefore('?')
        if (lower.endsWith(".m3u8")) return hls(url)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FlikkPlay/2 (Android)")
                setRequestProperty("Range", "bytes=0-")
            }
            val type = (conn.contentType ?: "").lowercase()
            val finalUrl = conn.url.toString()
            if (type.contains("mpegurl")) return hls(finalUrl)
            val isVideo = type.startsWith("video/") ||
                videoExt.any { finalUrl.lowercase().substringBefore('?').endsWith(it) } ||
                (type.contains("octet-stream") && videoExt.any { lower.endsWith(it) })
            if (!isVideo) return null
            val disp = conn.getHeaderField("Content-Disposition") ?: ""
            val fromDisp = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(disp)?.groupValues?.get(1)
            val ext = when {
                type.contains("webm") -> ".webm"
                type.contains("matroska") -> ".mkv"
                else -> ".mp4"
            }
            val name = Saver.safeName(fromDisp ?: finalUrl, ext)
            val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: conn.contentLengthLong
            return LinkInfo(finalUrl, name, if (type.startsWith("video/")) type.substringBefore(';') else "video/mp4",
                total, false, listOf("Original" to finalUrl))
        } catch (_: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun hls(url: String): LinkInfo? = try {
        val text = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "FlikkPlay/2 (Android)")
            inputStream.bufferedReader().use { it.readText() }
        }
        if (!text.contains("#EXTM3U")) null else {
            val name = Saver.safeName(url.substringBeforeLast(".m3u8"), ".mp4").let {
                if (it.endsWith(".mp4")) it else "$it.mp4"
            }
            val altAudio = text.contains("#EXT-X-MEDIA:TYPE=AUDIO")
            val lines = text.lines()
            val variants = ArrayList<Pair<Int, String>>()
            lines.forEachIndexed { i, l ->
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (next != null) variants += h to URL(URL(url), next.trim()).toString()
                }
            }
            val q = if (variants.isEmpty() || altAudio) listOf("Best" to url)
            else variants.filter { it.first > 0 }.sortedByDescending { it.first }
                .distinctBy { it.first }.map { "${it.first}P" to it.second }.ifEmpty { listOf("Best" to url) }
            LinkInfo(url, name, "video/mp4", -1, true, q)
        }
    } catch (_: Exception) {
        null
    }
}

/** Mana downloads list (system DownloadManager ids + HLS jobs). */
object DownloadStore {
    private fun prefs(c: Context) = c.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun ids(c: Context): List<Long> =
        prefs(c).getString("ids", "")!!.split(',').mapNotNull { it.toLongOrNull() }

    fun add(c: Context, id: Long) {
        prefs(c).edit().putString("ids", (listOf(id) + ids(c)).take(50).joinToString(",")).apply()
    }

    fun remove(c: Context, id: Long) {
        prefs(c).edit().putString("ids", ids(c).filter { it != id }.joinToString(",")).apply()
    }

    /** Direct mp4 lanti files: Android DownloadManager (notification, background, resume). */
    fun startDirect(c: Context, info: LinkInfo): Long {
        val req = DownloadManager.Request(Uri.parse(info.url))
            .setTitle(info.name)
            .setDescription("Flikk Play")
            .setMimeType(info.mime)
            .addRequestHeader("User-Agent", "FlikkPlay/2 (Android)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "FlikkPlay/${info.name}")
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        add(c, id)
        return id
    }
}

/** HLS (.m3u8) ni MP4 ga maarchi save chese foreground service. */
class FetchService : Service() {

    data class Job(
        val id: String,
        val name: String,
        @Volatile var progress: Int = 0,
        @Volatile var state: Int = RUNNING,
        @Volatile var uri: Uri? = null,
    )

    companion object {
        const val RUNNING = 0
        const val DONE = 1
        const val FAILED = 2
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 4501
        val jobs = ConcurrentHashMap<String, Job>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pair<Job, String>>()
    private var current: Pair<Job, Transformer>? = null
    private val holder = ProgressHolder()

    private val poll = object : Runnable {
        override fun run() {
            val (job, t) = current ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                job.progress = holder.progress
                notifyProgress(job)
            }
            handler.postDelayed(this, 800)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "video.mp4"
        createChannel()
        val first = notification(getString(R.string.dl_preparing), 0, true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, first)
        }
        if (url != null) {
            val job = Job(System.currentTimeMillis().toString(), name)
            jobs[job.id] = job
            queue.addLast(job to url)
            if (current == null) next()
        }
        return START_NOT_STICKY
    }

    private fun next() {
        val item = queue.removeFirstOrNull()
        if (item == null) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        val (job, url) = item
        val out = File(cacheDir, "fetch_${job.id}.mp4")
        val t = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(poll)
                    val uri = Saver.save(this@FetchService, job.name, "video/mp4", "FlikkPlay") { out.inputStream() }
                    out.delete()
                    job.uri = uri
                    job.progress = 100
                    job.state = if (uri != null) DONE else FAILED
                    finishNotice(job)
                    current = null
                    next()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    handler.removeCallbacks(poll)
                    out.delete()
                    job.state = FAILED
                    finishNotice(job)
                    current = null
                    next()
                }
            })
            .build()
        current = job to t
        try {
            t.start(EditedMediaItem.Builder(MediaItem.fromUri(url)).build(), out.absolutePath)
            handler.postDelayed(poll, 800)
        } catch (_: Exception) {
            job.state = FAILED
            current = null
            next()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.downloader), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, DownloaderActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String, progress: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.downloader))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(openIntent())
            .build()

    private fun notifyProgress(job: Job) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification("${job.name} · ${job.progress}%", job.progress, false))
    }

    private fun finishNotice(job: Job) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(job.name)
            .setContentText(getString(if (job.state == DONE) R.string.dl_done else R.string.dl_failed))
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(job.id.hashCode(), n)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        current?.second?.cancel()
        super.onDestroy()
    }
}
