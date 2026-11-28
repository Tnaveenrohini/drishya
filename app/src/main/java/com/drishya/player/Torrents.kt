package com.drishya.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** libtorrent session: magnet / .torrent downloads. */
object TorrentEngine {
    private var sm: SessionManager? = null
    val handles = CopyOnWriteArrayList<TorrentHandle>()
    private lateinit var app: Context

    private fun prefs() = app.getSharedPreferences("torrents", Context.MODE_PRIVATE)

    /** Android 11+: Download/FlikkPlay Torrents (gallery lo kanipistayi). Paata phones: app folder. */
    fun saveDir(c: Context): File {
        val dir = if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FlikkPlay Torrents")
        } else {
            File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Torrents")
        }
        dir.mkdirs()
        return dir
    }

    @Synchronized
    fun ensure(c: Context): SessionManager {
        sm?.let { return it }
        app = c.applicationContext
        val s = SessionManager(false)
        s.addListener(object : AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        val h = (alert as AddTorrentAlert).handle()
                        if (handles.none { it == h }) handles += h
                        h.resume()
                    }
                    AlertType.TORRENT_FINISHED -> {
                        val h = (alert as TorrentAlert<*>).handle()
                        scan(h)
                    }
                    else -> {}
                }
            }
        })
        s.start()
        sm = s
        // Mundu add chesinavi malli (files unte check chesi continue avuthayi)
        sources().forEach { src -> addInternal(src) }
        return s
    }

    private fun sources(): List<String> =
        prefs().getString("src", "")!!.split('\n').filter { it.isNotBlank() }

    private fun saveSources(list: List<String>) {
        prefs().edit().putString("src", list.distinct().joinToString("\n")).apply()
    }

    private fun addInternal(src: String) {
        val s = sm ?: return
        try {
            if (src.startsWith("magnet:")) {
                s.download(src, saveDir(app), torrent_flags_t())
            } else {
                val f = File(src)
                if (f.exists()) s.download(TorrentInfo(f), saveDir(app))
            }
        } catch (_: Throwable) {
        }
    }

    /** true = add ayyindi. */
    fun addMagnet(c: Context, magnet: String): Boolean {
        if (!magnet.startsWith("magnet:?")) return false
        ensure(c)
        saveSources(sources() + magnet)
        addInternal(magnet)
        TorrentService.start(c)
        return true
    }

    fun addTorrentFile(c: Context, uri: Uri): Boolean = try {
        ensure(c)
        val bytes = c.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        TorrentInfo.bdecode(bytes) // valid aa ani check
        val dir = File(c.filesDir, "torrents").apply { mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}.torrent")
        f.writeBytes(bytes)
        saveSources(sources() + f.absolutePath)
        addInternal(f.absolutePath)
        TorrentService.start(c)
        true
    } catch (_: Throwable) {
        false
    }

    fun remove(c: Context, h: TorrentHandle, deleteFiles: Boolean) {
        val s = ensure(c)
        val hash = try { h.infoHash().toHex() } catch (_: Throwable) { "" }
        handles.removeAll { it == h }
        try {
            if (deleteFiles) s.remove(h, SessionHandle.DELETE_FILES) else s.remove(h)
        } catch (_: Throwable) {
        }
        // source list nunchi kuda teeseyi
        saveSources(sources().filterNot { src ->
            hash.isNotEmpty() && src.lowercase().contains(hash.lowercase())
        })
    }

    fun active(): List<TorrentHandle> = handles.filter { h ->
        try {
            val st = h.status()
            h.isValid && !st.isFinished && st.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).to_int() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun scan(h: TorrentHandle) {
        try {
            val ti = h.torrentFile() ?: return
            val fs = ti.files()
            val paths = (0 until fs.numFiles()).map { File(h.savePath(), fs.filePath(it)).absolutePath }
            MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null)
        } catch (_: Throwable) {
        }
    }

    /** Torrent lo pedda video / audio file (play cheyyadaniki). */
    fun mainMedia(h: TorrentHandle): File? = try {
        val ti = h.torrentFile()
        val fs = ti.files()
        (0 until fs.numFiles())
            .map { File(h.savePath(), fs.filePath(it)) to fs.fileSize(it) }
            .filter { (f, _) ->
                val n = f.name.lowercase()
                listOf(".mp4", ".mkv", ".avi", ".webm", ".mov", ".m4v", ".ts", ".mp3", ".flac", ".m4a")
                    .any { n.endsWith(it) }
            }
            .maxByOrNull { it.second }?.first
    } catch (_: Throwable) {
        null
    }

    fun state(st: TorrentStatus): TorrentStatus.State = st.state()
}

/** Torrents download avuthunnappudu app close chesina kuda continue. */
class TorrentService : Service() {
    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 4502

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, TorrentService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val act = TorrentEngine.active()
            if (act.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            var done = 0L
            var total = 0L
            var rate = 0
            act.forEach {
                try {
                    val st = it.status()
                    done += st.totalWantedDone()
                    total += st.totalWanted()
                    rate += st.downloadPayloadRate()
                } catch (_: Throwable) {
                }
            }
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            val text = getString(R.string.tr_notif, act.size, pct, formatSize(rate.toLong()))
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text, pct))
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.downloader), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val n = build(getString(R.string.dl_preparing), 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
        TorrentEngine.ensure(this)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 3000)
        return START_NOT_STICKY
    }

    private fun build(text: String, pct: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_download)
        .setContentTitle(getString(R.string.tr_title))
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, pct, false)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 1,
                Intent(this, DownloaderActivity::class.java).putExtra(DownloaderActivity.EXTRA_TAB, DownloaderActivity.TAB_TORRENT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
