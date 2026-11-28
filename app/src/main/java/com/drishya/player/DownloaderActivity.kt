package com.drishya.player

import android.app.Dialog
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.Executors

/** Link downloader + WhatsApp Status saver (+ Torrents). */
class DownloaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_LINK = 0
        const val TAB_STATUS = 1
        const val TAB_TORRENT = 2
        private val URL_RE = Regex("https?://\\S+")
    }

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var urlBox: EditText
    private lateinit var probeBar: View
    private lateinit var result: View
    private lateinit var list: LinearLayout
    private lateinit var none: View
    private lateinit var pageLink: View
    private lateinit var pageStatus: View
    private lateinit var grid: RecyclerView
    private lateinit var stEmpty: View
    private lateinit var pageTorrent: View
    private lateinit var trList: LinearLayout
    private lateinit var trMagnet: EditText
    private var info: LinkInfo? = null
    private var quality = 0
    private val prefs by lazy { getSharedPreferences("downloads", MODE_PRIVATE) }

    private val refresher = object : Runnable {
        override fun run() {
            if (pageLink.visibility == View.VISIBLE) renderDownloads()
            if (pageTorrent.visibility == View.VISIBLE) renderTorrents()
            handler.postDelayed(this, 1000)
        }
    }

    private var pickingBusiness = false
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            prefs.edit().putString(if (pickingBusiness) "tree_wb" else "tree_wa", uri.toString()).apply()
            loadStatuses()
        }
    }

    private val pickTorrent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addTorrentUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloader)
        UiKit.fitSystemBars(findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
        findViewById<View>(R.id.dl_back).setOnClickListener { finish() }
        urlBox = findViewById(R.id.dl_url)
        probeBar = findViewById(R.id.dl_probe)
        result = findViewById(R.id.dl_result)
        list = findViewById(R.id.dl_list)
        none = findViewById(R.id.dl_none)
        pageLink = findViewById(R.id.page_link)
        pageStatus = findViewById(R.id.page_status)
        grid = findViewById(R.id.st_grid)
        stEmpty = findViewById(R.id.st_empty)
        pageTorrent = findViewById(R.id.page_torrent)
        trList = findViewById(R.id.tr_list)
        trMagnet = findViewById(R.id.tr_magnet)
        findViewById<View>(R.id.tr_paste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            trMagnet.setText(Regex("magnet:\\?\\S+").find(text)?.value ?: text.trim())
        }
        findViewById<View>(R.id.tr_add).setOnClickListener { addMagnet(trMagnet.text.toString().trim()) }
        trMagnet.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                addMagnet(trMagnet.text.toString().trim())
                true
            } else false
        }
        findViewById<View>(R.id.tr_open).setOnClickListener {
            try {
                pickTorrent.launch(arrayOf("application/x-bittorrent", "application/octet-stream"))
            } catch (_: Exception) {
            }
        }
        grid.layoutManager = GridLayoutManager(this, 3)
        findViewById<View>(R.id.dl_thumb_box).clipToOutline = true

        val tabs = findViewById<TabLayout>(R.id.dl_tabs)
        tabs.addTab(tabs.newTab().setText(R.string.dl_tab_link))
        tabs.addTab(tabs.newTab().setText(R.string.dl_tab_status))
        tabs.addTab(tabs.newTab().setText(R.string.dl_tab_torrent))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) = showTab(t.position)
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        findViewById<View>(R.id.dl_paste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val url = URL_RE.find(text)?.value ?: text
            urlBox.setText(url.trim())
            urlBox.setSelection(urlBox.text.length)
        }
        findViewById<View>(R.id.dl_check).setOnClickListener { probe() }
        urlBox.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                probe()
                true
            } else false
        }
        findViewById<View>(R.id.dl_go).setOnClickListener { startDownload() }
        findViewById<View>(R.id.st_allow_wa).setOnClickListener { askStatusAccess(business = false) }
        findViewById<View>(R.id.st_allow_wb).setOnClickListener { askStatusAccess(business = true) }

        val tab = intent.getIntExtra(EXTRA_TAB, TAB_LINK)
        tabs.getTabAt(tab)?.select()
        showTab(tab)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
        if (pageStatus.visibility == View.VISIBLE) loadStatuses()
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Vere app nunchi "Share → Flikk Play" chesina link, magnet, .torrent file. */
    private fun handleShare(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_VIEW) {
            val data = i.data ?: return
            selectTab(TAB_TORRENT)
            if (data.scheme == "magnet") addMagnet(data.toString()) else addTorrentUri(data)
            return
        }
        if (i.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("magnet:\\?\\S+").find(text)?.value?.let {
            selectTab(TAB_TORRENT)
            addMagnet(it)
            return
        }
        val url = URL_RE.find(text)?.value ?: return
        urlBox.setText(url)
        probe()
    }

    private fun selectTab(t: Int) {
        findViewById<TabLayout>(R.id.dl_tabs).getTabAt(t)?.select()
        showTab(t)
    }

    private fun showTab(t: Int) {
        pageLink.visibility = if (t == TAB_LINK) View.VISIBLE else View.GONE
        pageStatus.visibility = if (t == TAB_STATUS) View.VISIBLE else View.GONE
        pageTorrent.visibility = if (t == TAB_TORRENT) View.VISIBLE else View.GONE
        if (t == TAB_STATUS) loadStatuses()
        if (t == TAB_TORRENT) {
            io.execute { TorrentEngine.ensure(applicationContext) }
            renderTorrents()
        }
    }

    // ================================================================ Torrents

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    private fun addMagnet(m: String) {
        if (!m.startsWith("magnet:?")) {
            toast(getString(R.string.tr_bad))
            return
        }
        askNotif()
        io.execute {
            val ok = TorrentEngine.addMagnet(applicationContext, m)
            handler.post {
                toast(getString(if (ok) R.string.tr_added else R.string.tr_bad))
                if (ok) trMagnet.setText("")
                renderTorrents()
            }
        }
    }

    private fun addTorrentUri(uri: Uri) {
        askNotif()
        io.execute {
            val ok = TorrentEngine.addTorrentFile(applicationContext, uri)
            handler.post {
                toast(getString(if (ok) R.string.tr_added else R.string.tr_bad))
                renderTorrents()
            }
        }
    }

    private fun renderTorrents() {
        val hs = TorrentEngine.handles.filter { try { it.isValid } catch (_: Throwable) { false } }
        findViewById<View>(R.id.tr_none).visibility = if (hs.isEmpty()) View.VISIBLE else View.GONE
        trList.removeAllViews()
        hs.forEach { h -> trList.addView(torrentRow(h)) }
    }

    private fun torrentRow(h: org.libtorrent4j.TorrentHandle): View {
        val st = try { h.status() } catch (_: Throwable) { null }
        val name = (try { h.name } catch (_: Throwable) { null }) ?: st?.name() ?: "Torrent"
        val paused = st != null && st.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).to_int() != 0
        val finished = st?.isFinished == true
        val pct = ((st?.progress() ?: 0f) * 100).toInt()
        val stateText = when {
            st == null -> ""
            finished -> getString(R.string.dl_done) + " · " + formatSize(st.totalWanted())
            paused -> getString(R.string.tr_paused) + " · $pct%"
            st.state() == org.libtorrent4j.TorrentStatus.State.DOWNLOADING_METADATA -> getString(R.string.tr_meta)
            st.state() == org.libtorrent4j.TorrentStatus.State.CHECKING_FILES -> getString(R.string.tr_checking)
            else -> "$pct% · ${formatSize(st.downloadPayloadRate().toLong())}/s · " +
                getString(R.string.tr_peers, st.numPeers())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(6), dp(12))
            background = ContextCompat.getDrawable(this@DownloaderActivity, R.drawable.bg_card)
            isClickable = true
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = stateText
            setTextColor(0xFFA3A3A3.toInt())
            textSize = 12f
        })
        if (!finished) {
            col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = pct
                progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btn = android.widget.ImageButton(this).apply {
            background = null
            setImageResource(
                when {
                    finished -> R.drawable.ic_play
                    paused -> R.drawable.ic_download
                    else -> R.drawable.ic_pause
                }
            )
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnClickListener {
                when {
                    finished -> playTorrent(h)
                    paused -> {
                        h.resume()
                        TorrentService.start(this@DownloaderActivity)
                    }
                    else -> h.pause()
                }
                renderTorrents()
            }
        }
        row.addView(btn, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.setOnClickListener { if (finished) playTorrent(h) }
        row.setOnLongClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(name)
                .setItems(arrayOf(getString(R.string.tr_remove_keep), getString(R.string.tr_remove_delete))) { _, which ->
                    TorrentEngine.remove(this, h, deleteFiles = which == 1)
                    renderTorrents()
                }
                .show()
            true
        }
        return LinearLayout(this).apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun playTorrent(h: org.libtorrent4j.TorrentHandle) {
        val f = TorrentEngine.mainMedia(h)
        if (f == null || !f.exists()) {
            toast(getString(R.string.tr_no_media))
            return
        }
        play(Uri.fromFile(f))
    }

    // ================================================================ Link downloader

    private fun probe() {
        val url = urlBox.text.toString().trim()
        if (url.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBox.windowToken, 0)
        result.visibility = View.GONE
        probeBar.visibility = View.VISIBLE
        io.execute {
            val found = LinkProbe.probe(url)
            handler.post {
                if (isDestroyed) return@post
                probeBar.visibility = View.GONE
                if (found == null) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dl_not_video)
                        .setMessage(R.string.dl_not_video_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    info = found
                    quality = 0
                    showResult(found)
                }
            }
            if (found != null && !found.hls) loadThumb(found.url)
        }
    }

    private fun loadThumb(url: String) {
        val bmp = try {
            MediaMetadataRetriever().run {
                setDataSource(url, HashMap())
                val b = getFrameAtTime(2_000_000)
                release()
                b
            }
        } catch (_: Exception) {
            null
        }
        handler.post {
            if (!isDestroyed && bmp != null && info?.url == url) findViewById<ImageView>(R.id.dl_thumb).setImageBitmap(bmp)
        }
    }

    private fun showResult(i: LinkInfo) {
        result.visibility = View.VISIBLE
        findViewById<ImageView>(R.id.dl_thumb).setImageDrawable(null)
        findViewById<TextView>(R.id.dl_name).text = i.name
        findViewById<TextView>(R.id.dl_meta).text = listOfNotNull(
            if (i.hls) getString(R.string.dl_stream) else null,
            if (i.size > 0) formatSize(i.size) else null,
        ).joinToString(" · ").ifBlank { getString(R.string.dl_ready) }
        renderQualities()
    }

    private fun renderQualities() {
        val box = findViewById<LinearLayout>(R.id.dl_quality)
        box.removeAllViews()
        val q = info?.qualities ?: return
        box.visibility = if (q.size > 1 || info?.hls == true) View.VISIBLE else View.GONE
        q.take(4).forEachIndexed { idx, (label, _) ->
            val on = idx == quality
            box.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 16f
                setTypeface(typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                background = ContextCompat.getDrawable(this@DownloaderActivity,
                    if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
                setOnClickListener {
                    quality = idx
                    renderQualities()
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (idx > 0) marginStart = dp(8) })
        }
    }

    private fun startDownload() {
        val i = info ?: return
        val (_, url) = i.qualities.getOrNull(quality) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        try {
            if (i.hls) {
                val label = i.qualities[quality].first
                val name = i.name.substringBeforeLast('.') + (if (label.endsWith("P")) "_$label" else "") + ".mp4"
                ContextCompat.startForegroundService(this, Intent(this, FetchService::class.java)
                    .putExtra(FetchService.EXTRA_URL, url)
                    .putExtra(FetchService.EXTRA_NAME, name))
            } else {
                DownloadStore.startDirect(this, i.copy(url = url))
            }
            toast(getString(R.string.dl_started))
            result.visibility = View.GONE
            urlBox.setText("")
            renderDownloads()
        } catch (e: Exception) {
            toast(getString(R.string.dl_failed))
        }
    }

    private data class Item(
        val key: String,
        val name: String,
        val status: String,
        val progress: Int,
        val running: Boolean,
        val uri: Uri?,
        val dmId: Long?,
    )

    private fun collect(): List<Item> {
        val out = ArrayList<Item>()
        FetchService.jobs.values.sortedByDescending { it.id }.forEach { j ->
            val st = when (j.state) {
                FetchService.DONE -> getString(R.string.dl_done)
                FetchService.FAILED -> getString(R.string.dl_failed)
                else -> "${j.progress}%"
            }
            out += Item("j" + j.id, j.name, st, j.progress, j.state == FetchService.RUNNING, j.uri, null)
        }
        val ids = DownloadStore.ids(this)
        if (ids.isNotEmpty()) {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            try {
                dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                        val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: ""
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        val text = when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.dl_done) + " · " + formatSize(total)
                            DownloadManager.STATUS_FAILED -> getString(R.string.dl_failed)
                            DownloadManager.STATUS_PAUSED -> getString(R.string.dl_waiting)
                            DownloadManager.STATUS_PENDING -> getString(R.string.dl_waiting)
                            else -> "$pct% · ${formatSize(done)}" + (if (total > 0) " / ${formatSize(total)}" else "")
                        }
                        val uri = if (status == DownloadManager.STATUS_SUCCESSFUL) dm.getUriForDownloadedFile(id) else null
                        out += Item("d$id", title, text, pct,
                            status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING ||
                                status == DownloadManager.STATUS_PAUSED, uri, id)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun renderDownloads() {
        val items = collect()
        none.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        items.forEach { it -> list.addView(row(it)) }
    }

    private fun row(it: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ContextCompat.getDrawable(this@DownloaderActivity, R.drawable.bg_card)
            isClickable = true
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(this@DownloaderActivity, R.drawable.bg_thumb)
            clipToOutline = true
        }
        if (it.uri != null) Glide.with(this).load(it.uri).centerCrop().into(thumb)
        else {
            thumb.setImageResource(R.drawable.ic_download)
            thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(72), dp(44)))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        col.addView(TextView(this).apply {
            text = it.name
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = it.status
            setTextColor(0xFFA3A3A3.toInt())
            textSize = 12f
        })
        if (it.running) {
            col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = it.progress
                progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { _ -> it.uri?.let { u -> play(u) } }
        row.setOnLongClickListener { _ ->
            MaterialAlertDialogBuilder(this)
                .setTitle(it.name)
                .setItems(arrayOf(getString(R.string.dl_remove))) { _, _ ->
                    if (it.dmId != null) {
                        if (it.running) (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).remove(it.dmId)
                        DownloadStore.remove(this, it.dmId)
                    } else FetchService.jobs.remove(it.key.removePrefix("j"))
                    renderDownloads()
                }
                .show()
            true
        }
        return LinearLayout(this).apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun play(uri: Uri) {
        try {
            startActivity(Intent(this, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
        }
    }

    // ================================================================ WhatsApp Status

    private fun statusDocUri(business: Boolean): Uri {
        val pkg = if (business) "com.whatsapp.w4b" else "com.whatsapp"
        val app = if (business) "WhatsApp Business" else "WhatsApp"
        val path = if (Build.VERSION.SDK_INT >= 30) "Android/media/$pkg/$app/Media/.Statuses"
        else "$app/Media/.Statuses"
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
    }

    private fun askStatusAccess(business: Boolean) {
        pickingBusiness = business
        try {
            pickTree.launch(statusDocUri(business))
        } catch (_: Exception) {
            pickTree.launch(null)
        }
    }

    private data class StatusFile(val uri: Uri, val name: String, val video: Boolean, val time: Long)

    private fun loadStatuses() {
        val trees = listOfNotNull(prefs.getString("tree_wa", null), prefs.getString("tree_wb", null))
            .map { Uri.parse(it) }
            .filter { t -> contentResolver.persistedUriPermissions.any { it.uri == t && it.isReadPermission } }
        if (trees.isEmpty()) {
            stEmpty.visibility = View.VISIBLE
            grid.adapter = null
            return
        }
        io.execute {
            val files = trees.flatMap { t ->
                try {
                    DocumentFile.fromTreeUri(this, t)?.listFiles()?.toList().orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            }.mapNotNull { f ->
                val n = f.name ?: return@mapNotNull null
                val lower = n.lowercase()
                val video = lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".mkv")
                val image = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
                if (!video && !image) null else StatusFile(f.uri, n, video, f.lastModified())
            }.sortedByDescending { it.time }
            handler.post {
                if (isDestroyed) return@post
                stEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                findViewById<TextView>(R.id.st_msg).setText(
                    if (files.isEmpty()) R.string.st_empty else R.string.st_allow_msg
                )
                grid.adapter = StatusAdapter(files)
            }
        }
    }

    private fun saveStatus(f: StatusFile) {
        io.execute {
            val mime = if (f.video) "video/mp4" else (if (f.name.lowercase().endsWith(".png")) "image/png" else "image/jpeg")
            val uri = Saver.save(this, "Status_" + f.name, mime, "FlikkPlay Status") {
                contentResolver.openInputStream(f.uri)!!
            }
            handler.post { toast(getString(if (uri != null) R.string.st_saved else R.string.dl_failed)) }
        }
    }

    private fun openStatus(f: StatusFile) {
        if (f.video) {
            play(f.uri)
            return
        }
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            setOnClickListener { d.dismiss() }
        }
        Glide.with(this).load(f.uri).into(img)
        d.setContentView(img)
        d.show()
    }

    private inner class StatusAdapter(val files: List<StatusFile>) : RecyclerView.Adapter<StatusAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val box: View = v.findViewById(R.id.st_box)
            val thumb: ImageView = v.findViewById(R.id.st_thumb)
            val play: View = v.findViewById(R.id.st_play)
            val save: View = v.findViewById(R.id.st_save)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_status, parent, false))
            vh.box.clipToOutline = true
            return vh
        }

        override fun getItemCount() = files.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = files[position]
            Glide.with(h.thumb).load(f.uri).centerCrop().into(h.thumb)
            h.play.visibility = if (f.video) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { openStatus(f) }
            h.save.setOnClickListener { saveStatus(f) }
        }
    }
}
