package com.drishya.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Music player screen: blurred album art, queue, Track / Progress pill. */
@OptIn(UnstableApi::class)
class MusicActivity : AppCompatActivity() {

    companion object {
        const val KEY_DUR = "dur"
    }

    private lateinit var bgArt: ImageView
    private lateinit var smallArt: ImageView
    private lateinit var bigArt: ImageView
    private lateinit var vinylBox: View
    private lateinit var vinylRings: View
    private lateinit var vinylHole: View
    private var spin: android.animation.ObjectAnimator? = null
    private val vinylPrefs by lazy { getSharedPreferences(Prefs.SETTINGS, MODE_PRIVATE) }
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var bigTitle: TextView
    private lateinit var bigArtist: TextView
    private lateinit var coverBox: View
    private lateinit var list: RecyclerView
    private lateinit var searchBox: EditText
    private lateinit var queueBtn: ImageButton
    private lateinit var pill: TextView
    private lateinit var pos: TextView
    private lateinit var dur: TextView
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var playBtn: ImageButton
    private lateinit var favBtn: ImageButton
    private var noArt = true
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val settings by lazy { getSharedPreferences(Prefs.SETTINGS, MODE_PRIVATE) }
    private var scrubbing = false
    private var showQueue = false
    private var filter = ""

    private val artCache = LruCache<String, Bitmap>(60)
    private val artIo = Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)
        UiKit.fitSystemBars(findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
        bgArt = findViewById(R.id.bg_art)
        smallArt = findViewById(R.id.small_art)
        bigArt = findViewById(R.id.big_art)
        title = findViewById(R.id.m_title)
        artist = findViewById(R.id.m_artist)
        bigTitle = findViewById(R.id.big_title)
        bigArtist = findViewById(R.id.big_artist)
        coverBox = findViewById(R.id.cover_box)
        list = findViewById(R.id.m_list)
        searchBox = findViewById(R.id.m_search_box)
        queueBtn = findViewById(R.id.m_queue_btn)
        pill = findViewById(R.id.m_pill)
        pos = findViewById(R.id.m_pos)
        dur = findViewById(R.id.m_dur)
        timeBar = findViewById(R.id.m_timebar)
        playBtn = findViewById(R.id.m_play)
        shuffleBtn = findViewById(R.id.m_shuffle)
        repeatBtn = findViewById(R.id.m_repeat)
        list.layoutManager = LinearLayoutManager(this)
        bigArt.clipToOutline = true
        vinylBox = findViewById(R.id.vinyl_box)
        vinylRings = findViewById(R.id.vinyl_rings)
        vinylHole = findViewById(R.id.vinyl_hole)
        applyVinyl()
        smallArt.clipToOutline = true
        title.isSelected = true   // marquee
        artist.isSelected = true

        playBtn.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.m_close).setOnClickListener { finish() }
        findViewById<View>(R.id.m_sleep).setOnClickListener { sleepDialog() }
        findViewById<View>(R.id.m_eq).setOnClickListener { eqDialog() }
        favBtn = findViewById(R.id.m_fav)
        favBtn.setOnClickListener {
            val id = player?.currentMediaItem?.mediaId ?: return@setOnClickListener
            Library.toggleFavourite(this, id)
            updateFav()
        }
        // Record meeda swipe: left = next, right = previous, kindaki = close
        val swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.rawX - start.rawX
                val dy = e2.rawY - start.rawY
                val p = player ?: return false
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > dp(60)) {
                    if (dx < 0) p.seekToNextMediaItem() else p.seekToPreviousMediaItem()
                    return true
                }
                if (dy > dp(120) && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    finish()
                    return true
                }
                return false
            }
        })
        vinylBox.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev) }
        findViewById<View>(R.id.m_prev).setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
        }
        findViewById<View>(R.id.m_next).setOnClickListener { player?.seekToNextMediaItem() }
        shuffleBtn.setOnClickListener {
            setMode(if (PlayerHolder.playMode == PlayerHolder.MODE_SHUFFLE) PlayerHolder.MODE_ALL else PlayerHolder.MODE_SHUFFLE)
        }
        repeatBtn.setOnClickListener {
            setMode(
                when (PlayerHolder.playMode) {
                    PlayerHolder.MODE_ALL, PlayerHolder.MODE_SHUFFLE -> PlayerHolder.MODE_ONE
                    PlayerHolder.MODE_ONE -> PlayerHolder.MODE_OFF
                    else -> PlayerHolder.MODE_ALL
                }
            )
        }
        queueBtn.setOnClickListener {
            showQueue = !showQueue
            refreshPanels()
        }
        findViewById<View>(R.id.m_search).setOnClickListener {
            if (searchBox.visibility == View.VISIBLE) {
                searchBox.setText("")
                searchBox.visibility = View.GONE
            } else {
                searchBox.visibility = View.VISIBLE
                searchBox.requestFocus()
                showQueue = true
                refreshPanels()
            }
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim()?.lowercase().orEmpty()
                fillQueue()
            }
        })
        findViewById<View>(R.id.m_more).setOnClickListener { showMore(it) }
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubbing = true
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                pos.text = formatTime(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player?.seekTo(position)
            }
        })

        attach()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {
        }
        attach()
        handler.removeCallbacks(tick)
        handler.post(tick)
        updateSpin()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        spin?.pause()
        // Music: activity close ayina vinipistune untundi (notification lo controls)
        player?.removeListener(listener)
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        artIo.shutdown()
    }

    // ---------------------------------------------------------------- setup

    private fun attach() {
        if (player != null) return
        val p = PlayerHolder.get(this)
        player = p
        p.addListener(listener)
        refreshAll()
    }

    private fun handleIntent(i: Intent) {
        val uris = i.getStringArrayListExtra(PlayerActivity.EXTRA_URIS) ?: return
        if (uris.isEmpty()) return
        val titles = i.getStringArrayListExtra(PlayerActivity.EXTRA_TITLES)
        val artists = i.getStringArrayListExtra(PlayerActivity.EXTRA_ARTISTS)
        val durations = i.getLongArrayExtra(EXTRA_DURATIONS_KEY)
        val index = i.getIntExtra(PlayerActivity.EXTRA_INDEX, 0).coerceIn(0, uris.size - 1)
        val items = uris.mapIndexed { idx, u ->
            val meta = MediaMetadata.Builder()
                .setTitle(titles?.getOrNull(idx) ?: Uri.parse(u).lastPathSegment)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            artists?.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { meta.setArtist(it) }
            val d = durations?.getOrNull(idx) ?: 0L
            if (d > 0) meta.setExtras(Bundle().apply { putLong(KEY_DUR, d) })
            MediaItem.Builder().setUri(u).setMediaId(u).setMediaMetadata(meta.build()).build()
        }
        i.removeExtra(PlayerActivity.EXTRA_URIS)
        val p = player ?: PlayerHolder.get(this).also { player = it; it.addListener(listener) }
        p.setMediaItems(items, index, 0L)
        PlayerHolder.applyPlayMode(settings.getInt("music_mode", PlayerHolder.MODE_ALL))
        p.prepare()
        p.play()
        Library.markPlayed(this, items[index].mediaId)
        refreshAll()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshAll()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refreshNowPlaying()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcon()
            updateSpin()
            list.adapter?.notifyDataSetChanged()
        }
        override fun onPlaybackStateChanged(playbackState: Int) = updateProgress()
    }

    // ---------------------------------------------------------------- UI

    private fun refreshAll() {
        refreshNowPlaying()
        refreshPanels()
        updateModeIcons()
        updatePlayIcon()
        updateProgress()
    }

    private fun refreshPanels() {
        coverBox.visibility = if (showQueue) View.GONE else View.VISIBLE
        list.visibility = if (showQueue) View.VISIBLE else View.GONE
        queueBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (showQueue) 0xFFFFFFFF.toInt() else 0xFF9A9A9A.toInt()
        )
        if (showQueue) fillQueue()
    }

    private fun refreshNowPlaying() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val m = p.mediaMetadata
        val t = (m.title ?: item.mediaMetadata.title)?.toString().orEmpty()
        val a = (m.artist ?: item.mediaMetadata.artist)?.toString()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown_artist)
        if (title.text.toString() != t) title.text = t
        if (artist.text.toString() != a) artist.text = a
        if (bigTitle.text.toString() != t) bigTitle.text = t
        bigTitle.isSelected = true
        bigArtist.text = a
        updateFav()
        val bytes = m.artworkData
        val bmp = bytes?.let {
            try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (_: Throwable) { null }
        }
        if (bmp != null) {
            noArt = false
            setArt(smallArt, bmp, 0)
            setArt(bigArt, bmp, 0)
            bgArt.setImageBitmap(blur(bmp))
        } else {
            noArt = true
            setArt(smallArt, null, dp(16))
            if (vinylOn()) {
                // Art lekapothe: Flikk label unna vinyl record
                bigArt.setPadding(0, 0, 0, 0)
                bigArt.scaleType = ImageView.ScaleType.FIT_CENTER
                bigArt.imageTintList = null
                bigArt.setImageResource(R.drawable.art_vinyl_label)
            } else {
                setArt(bigArt, null, dp(72))
            }
            bgArt.setImageDrawable(null)
        }
        vinylHole.visibility = if (vinylOn() && !noArt) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateFav() {
        val id = player?.currentMediaItem?.mediaId ?: return
        val on = Library.isFavourite(this, id)
        favBtn.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
    }

    // ---------------------------------------------------------------- vinyl spin

    private fun vinylOn() = vinylPrefs.getBoolean("vinyl", true)

    private val roundOutline = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private fun applyVinyl() {
        val on = vinylOn()
        bigArt.outlineProvider = if (on) roundOutline else android.view.ViewOutlineProvider.BACKGROUND
        bigArt.invalidateOutline()
        vinylRings.visibility = if (on) View.VISIBLE else View.GONE
        vinylHole.visibility = if (on && !noArt) View.VISIBLE else View.GONE
        if (!on) {
            spin?.cancel()
            spin = null
            vinylBox.rotation = 0f
        }
        updateSpin()
    }

    /** Paata play avuthunte record thirugutundi, pause chesthe aagutundi. */
    private fun updateSpin() {
        if (!vinylOn()) return
        val playing = player?.isPlaying == true
        val a = spin ?: android.animation.ObjectAnimator.ofFloat(vinylBox, View.ROTATION, 0f, 360f).apply {
            duration = 16_000L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            spin = this
        }
        when {
            playing && !a.isStarted -> a.start()
            playing && a.isPaused -> a.resume()
            !playing && a.isRunning -> a.pause()
        }
    }

    private fun setArt(view: ImageView, bmp: Bitmap?, placeholderPad: Int) {
        if (bmp != null) {
            view.setPadding(0, 0, 0, 0)
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.imageTintList = null
            view.setImageBitmap(bmp)
        } else {
            view.setPadding(placeholderPad, placeholderPad, placeholderPad, placeholderPad)
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.imageTintList = android.content.res.ColorStateList.valueOf(0xFF6E6E6E.toInt())
            view.setImageResource(R.drawable.ic_music)
        }
    }

    /** Chinna bitmap ni peddhaga chupisthe natural blur vastundi. */
    private fun blur(src: Bitmap): Bitmap {
        val w = 24
        val h = (src.height * w / src.width.coerceAtLeast(1)).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun updatePlayIcon() {
        playBtn.setImageResource(if (player?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(p.currentMediaItemIndex, 0)
            p.play()
        }
    }

    private fun setMode(mode: Int) {
        PlayerHolder.applyPlayMode(mode)
        settings.edit().putInt("music_mode", mode).apply()
        updateModeIcons()
        val label = when (mode) {
            PlayerHolder.MODE_ONE -> R.string.mode_one
            PlayerHolder.MODE_SHUFFLE -> R.string.mode_shuffle
            PlayerHolder.MODE_OFF -> R.string.repeat_off
            else -> R.string.mode_all
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun updateModeIcons() {
        val mode = PlayerHolder.playMode
        val on = 0xFFFFFFFF.toInt()
        val off = 0xFF7A7A7A.toInt()
        shuffleBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (mode == PlayerHolder.MODE_SHUFFLE) on else off
        )
        repeatBtn.setImageResource(if (mode == PlayerHolder.MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatBtn.imageTintList = android.content.res.ColorStateList.valueOf(
            if (mode == PlayerHolder.MODE_OFF || mode == PlayerHolder.MODE_ONCE) off else on
        )
    }

    private fun itemDuration(p: Player, i: Int): Long {
        if (i == p.currentMediaItemIndex && p.duration > 0) return p.duration
        return p.getMediaItemAt(i).mediaMetadata.extras?.getLong(KEY_DUR) ?: 0L
    }

    private fun updateProgress() {
        val p = player ?: return
        val d = p.duration
        val position = p.currentPosition
        if (d != C.TIME_UNSET && d > 0) {
            timeBar.setDuration(d)
            dur.text = formatTime(d)
        }
        if (!scrubbing) {
            timeBar.setPosition(position)
            pos.text = formatTime(position)
        }
        timeBar.setBufferedPosition(p.bufferedPosition)

        // Track: 1 / 2 · Progress: 0:22 / 3:11 (queue motham)
        val count = p.mediaItemCount
        if (count > 0) {
            val cur = p.currentMediaItemIndex
            var total = 0L
            var before = 0L
            for (i in 0 until count) {
                val di = itemDuration(p, i)
                total += di
                if (i < cur) before += di
            }
            pill.text = getString(R.string.track_of, cur + 1, count, formatTime(total))
        }
    }

    // ---------------------------------------------------------------- queue

    private data class QRow(val index: Int, val item: MediaItem)

    private fun fillQueue() {
        val p = player ?: return
        val rows = (0 until p.mediaItemCount).map { QRow(it, p.getMediaItemAt(it)) }
            .filter { filter.isEmpty() || (it.item.mediaMetadata.title?.toString()?.lowercase()?.contains(filter) == true) }
        list.adapter = TrackAdapter(rows)
        if (filter.isEmpty()) list.scrollToPosition(p.currentMediaItemIndex.coerceAtLeast(0))
    }

    private inner class TrackAdapter(private val rows: List<QRow>) : RecyclerView.Adapter<TrackAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val art: ImageView = v.findViewById(R.id.t_art)
            val eq: EqBarsView = v.findViewById(R.id.t_eq)
            val title: TextView = v.findViewById(R.id.t_title)
            val sub: TextView = v.findViewById(R.id.t_sub)
            val more: ImageButton = v.findViewById(R.id.t_more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))
            vh.art.clipToOutline = true
            return vh
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = player ?: return
            val r = rows[position]
            val m = r.item.mediaMetadata
            val current = r.index == p.currentMediaItemIndex
            h.title.text = m.title
            h.title.setTypeface(null, if (current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            val d = itemDuration(p, r.index)
            val a = m.artist?.toString()?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_artist)
            h.sub.text = if (d > 0) "${formatTime(d)} · $a" else a
            if (current) {
                h.eq.visibility = View.VISIBLE
                h.eq.playing = p.isPlaying
                h.art.setImageDrawable(null)
            } else {
                h.eq.visibility = View.GONE
                loadArt(r.item.mediaId, h.art)
            }
            h.itemView.setOnClickListener {
                if (!current) {
                    p.seekTo(r.index, 0)
                    p.play()
                }
            }
            h.more.setOnClickListener { v ->
                PopupMenu(this@MusicActivity, v).apply {
                    menuInflater.inflate(R.menu.track_more, menu)
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            R.id.t_play -> { p.seekTo(r.index, 0); p.play() }
                            R.id.t_share -> share(Uri.parse(r.item.mediaId))
                            R.id.t_remove -> if (p.mediaItemCount > 1) { p.removeMediaItem(r.index); fillQueue() }
                        }
                        true
                    }
                    show()
                }
            }
        }
    }

    /** Song lo unna album art ni background lo chadhivi cache chestham. */
    private fun loadArt(id: String, into: ImageView) {
        into.tag = id
        artCache.get(id)?.let {
            into.setPadding(0, 0, 0, 0)
            into.imageTintList = null
            into.setImageBitmap(it)
            return
        }
        into.setPadding(dp(18), dp(18), dp(18), dp(18))
        into.imageTintList = android.content.res.ColorStateList.valueOf(0xFF6E6E6E.toInt())
        into.setImageResource(R.drawable.ic_music)
        val uri = Uri.parse(id)
        if (uri.scheme != "content" && uri.scheme != "file") return
        artIo.execute {
            val bmp = try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val bytes = r.embeddedPicture
                r.release()
                bytes?.let {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeByteArray(it, 0, it.size, opts)
                }
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) {
                artCache.put(id, bmp)
                runOnUiThread {
                    if (into.tag == id) {
                        into.setPadding(0, 0, 0, 0)
                        into.imageTintList = null
                        into.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- ⋮ menu

    private fun showMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.music_more, menu)
            menu.findItem(R.id.mm_vinyl)?.isChecked = vinylOn()
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.mm_vinyl -> {
                        vinylPrefs.edit().putBoolean("vinyl", !vinylOn()).apply()
                        applyVinyl()
                        refreshNowPlaying()
                    }
                    R.id.mm_sleep -> sleepDialog()
                    R.id.mm_eq -> eqDialog()
                    R.id.mm_boost -> boostDialog()
                    R.id.mm_speed -> speedDialog()
                    R.id.mm_share -> player?.currentMediaItem?.let { m -> share(Uri.parse(m.mediaId)) }
                }
                true
            }
            show()
        }
    }

    private fun share(uri: Uri) {
        val intent = if (uri.scheme == "content" || uri.scheme == "file") {
            Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri.toString())
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: Exception) {
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun sleepDialog() {
        val minutes = intArrayOf(15, 30, 45, 60)
        val labels = arrayOf(getString(R.string.sleep_off)) +
            minutes.map { getString(R.string.sleep_minutes, it) } +
            getString(R.string.sleep_end)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> { PlayerHolder.cancelSleep(); toast(getString(R.string.sleep_cancelled)) }
                    labels.size - 1 -> { PlayerHolder.setSleepAtEnd(); toast(getString(R.string.sleep_set_end)) }
                    else -> {
                        val m = minutes[which - 1]
                        PlayerHolder.setSleep(m)
                        toast(getString(R.string.sleep_set, formatTime(m * 60_000L)))
                    }
                }
            }
            .show()
    }

    private fun eqDialog() {
        val names = PlayerHolder.presetNames()
        if (names.isEmpty()) {
            toast(getString(R.string.eq_unavailable))
            return
        }
        val labels = (listOf(getString(R.string.eq_off)) + names).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.equalizer)
            .setSingleChoiceItems(labels, PlayerHolder.eqPreset + 1) { d, which ->
                d.dismiss()
                if (!PlayerHolder.setPreset(which - 1)) toast(getString(R.string.eq_unavailable))
            }
            .show()
    }

    private fun boostDialog() {
        val labels = arrayOf(
            getString(R.string.boost_normal), getString(R.string.boost_mid), getString(R.string.boost_high),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.volume_boost)
            .setSingleChoiceItems(labels, PlayerHolder.boostLevel) { d, which ->
                d.dismiss()
                if (!PlayerHolder.setBoost(which)) toast(getString(R.string.eq_unavailable))
            }
            .show()
    }

    private fun speedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        val cur = player?.playbackParameters?.speed ?: 1f
        val checked = speeds.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_speed)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                player?.playbackParameters = PlaybackParameters(speeds[which])
            }
            .show()
    }
}

const val EXTRA_DURATIONS_KEY = "durations"
