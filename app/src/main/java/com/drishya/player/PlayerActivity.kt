package com.drishya.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_ARTISTS = "artists"
        private const val RESUME_MIN_MS = 5_000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var playerView360: PlayerView
    private var is360 = false
    private val activeView get() = if (is360) playerView360 else playerView
    private var zoom = 1f
    private var zooming = false

    // External subtitle (delay/sync kosam original file gurtupettukuntam)
    private var subOriginal: Uri? = null
    private var subName = ""
    private var subDelayMs = 0L
    private lateinit var hud: TextView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var levelLeft: View
    private lateinit var levelLeftFill: View
    private lateinit var levelLeftText: TextView
    private lateinit var levelRight: View
    private lateinit var levelRightFill: View
    private lateinit var levelRightText: TextView
    private lateinit var unlockPill: View
    private lateinit var btnSleep: ImageButton
    private lateinit var btnBg: ImageButton
    private lateinit var audio: AudioManager

    private var player: ExoPlayer? = null
    private val resumePrefs by lazy { getSharedPreferences(Prefs.RESUME, MODE_PRIVATE) }
    private val settings by lazy { getSharedPreferences(Prefs.SETTINGS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var pendingItems: List<MediaItem>? = null
    private var pendingIndex = 0
    private var lastMediaId: String? = null
    private var manualOrientation = false
    private var locked = false

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val resizeNames = intArrayOf(R.string.resize_fit, R.string.resize_zoom, R.string.resize_stretch)
    private var resizeIdx = 0

    private val bgPlay get() = settings.getBoolean(Prefs.BG_PLAY, false)

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) attachSubtitle(uri)
        }
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val lockBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showUnlock()
    }

    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            updateSleepIcon()
            handler.postDelayed(this, 5_000)
        }
    }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideLevels = Runnable {
        levelLeft.visibility = View.INVISIBLE
        levelRight.visibility = View.INVISIBLE
    }
    private val hideUnlock = Runnable { unlockPill.visibility = View.GONE }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        playerView360 = findViewById(R.id.player_view_360)
        hud = findViewById(R.id.hud)
        topBar = findViewById(R.id.top_bar)
        titleView = findViewById(R.id.player_title)
        levelLeft = findViewById(R.id.level_left)
        levelLeftFill = findViewById(R.id.level_left_fill)
        levelLeftText = findViewById(R.id.level_left_text)
        levelRight = findViewById(R.id.level_right)
        levelRightFill = findViewById(R.id.level_right_fill)
        levelRightText = findViewById(R.id.level_right_text)
        unlockPill = findViewById(R.id.unlock_pill)
        btnSleep = findViewById(R.id.btn_sleep)
        btnBg = findViewById(R.id.btn_bg)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val pad = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(pad + c.left, pad + c.top, pad + c.right, pad)
            insets
        }

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_lock).setOnClickListener { lock() }
        btnSleep.setOnClickListener { sleepDialog() }
        btnBg.setOnClickListener { toggleBackground() }
        findViewById<View>(R.id.btn_subtitle).setOnClickListener { pickSubtitle.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMore(it) }
        unlockPill.setOnLongClickListener { unlock(); true }
        unlockPill.setOnClickListener { showUnlock() }
        onBackPressedDispatcher.addCallback(this, lockBack)

        val visListener = PlayerView.ControllerVisibilityListener { visibility ->
            if (!isInPictureInPictureMode && !locked) topBar.visibility = visibility
        }
        playerView.setControllerVisibilityListener(visListener)
        playerView360.setControllerVisibilityListener(visListener)
        setupGestures()
        handleIntent(intent)
        updateBgIcon()
        applyAccent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePosition()
        handleIntent(intent)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) applyPending()
    }

    override fun onStart() {
        super.onStart()
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {
        }
        attachPlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        handler.removeCallbacks(saveTick)
        val p = player
        if (p != null) {
            val keep = (bgPlay || playingAudio()) && p.isPlaying
            p.removeListener(listener)
            if (!keep) p.pause()
        }
        playerView.player = null
        playerView360.player = null
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            PlayerHolder.peek()?.let {
                it.stop()
                it.clearMediaItems()
            }
            stopService(Intent(this, PlaybackService::class.java))
            PlayerHolder.release()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------- media

    private fun handleIntent(i: Intent) {
        val uris = i.getStringArrayListExtra(EXTRA_URIS)
        val data = i.data
        when {
            !uris.isNullOrEmpty() -> {
                val titles = i.getStringArrayListExtra(EXTRA_TITLES)
                val artists = i.getStringArrayListExtra(EXTRA_ARTISTS)
                val audio = i.getBooleanExtra(EXTRA_AUDIO, false)
                pendingItems = uris.mapIndexed { idx, u ->
                    buildItem(
                        Uri.parse(u), titles?.getOrNull(idx) ?: "Video",
                        audio = audio || isAudioName(u), artist = artists?.getOrNull(idx),
                    )
                }
                pendingIndex = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.size - 1)
            }
            data != null -> {
                val name = displayName(data)
                val audio = isAudioName(name) || i.type?.startsWith("audio/") == true
                pendingItems = listOf(buildItem(data, name, audio = audio))
                pendingIndex = 0
            }
            // Notification nunchi vachhamu: already playing video ne chupistham
            (PlayerHolder.peek()?.mediaItemCount ?: 0) > 0 -> pendingItems = null
            else -> finish()
        }
        // Intent ni malli process cheyyakunda (rotation / notification)
        i.removeExtra(EXTRA_URIS)
        i.data = null
    }

    private fun buildItem(uri: Uri, title: String, audio: Boolean = false, artist: String? = null): MediaItem {
        val meta = MediaMetadata.Builder().setTitle(title)
        if (audio) meta.setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (!artist.isNullOrBlank()) meta.setArtist(artist)
        val b = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(meta.build())
        // Network streams: link lo .m3u8 / .mpd unte HLS / DASH ani cheppali
        val s = uri.toString().lowercase()
        if (uri.scheme == "http" || uri.scheme == "https") {
            when {
                ".m3u8" in s -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
                ".mpd" in s -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }
        return b.build()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Video"
    }

    private fun attachPlayer() {
        if (player != null) return
        val p = PlayerHolder.get(this)
        player = p
        activeView.player = p
        playerView.resizeMode = resizeModes[resizeIdx]
        p.addListener(listener)
        applyPending()
        updateAudioUi()
        p.currentMediaItem?.let { titleView.text = it.mediaMetadata.title }
        handler.removeCallbacks(saveTick)
        handler.post(saveTick)
    }

    private fun applyPending() {
        val p = player ?: return
        val items = pendingItems ?: return
        pendingItems = null
        val first = items[pendingIndex]
        val saved = resumePrefs.getLong(first.mediaId, 0L)
        val pos = if (saved > RESUME_MIN_MS) saved else 0L
        if (saved > RESUME_MIN_MS) showHud(getString(R.string.resumed_from, formatTime(saved)), 1500)
        lastMediaId = first.mediaId
        titleView.text = first.mediaMetadata.title
        subOriginal = null
        subDelayMs = 0L
        p.setMediaItems(items, pendingIndex, pos)
        p.prepare()
        p.play()
        Library.markPlayed(this, first.mediaId)
        updateAudioUi()
    }

    private fun savePosition() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val pos = p.currentPosition
        val dur = p.duration
        val edit = resumePrefs.edit()
        if (dur != C.TIME_UNSET && dur > 0 && pos > dur - RESUME_MIN_MS) edit.remove(item.mediaId)
        else if (pos > RESUME_MIN_MS) edit.putLong(item.mediaId, pos)
        edit.apply()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            titleView.text = mediaItem?.mediaMetadata?.title
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                subOriginal = null
                subDelayMs = 0L
            }
            updateAudioUi()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                lastMediaId?.let { resumePrefs.edit().remove(it).apply() }
            }
            if (mediaItem != null &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            ) {
                Library.markPlayed(this@PlayerActivity, mediaItem.mediaId)
                val saved = resumePrefs.getLong(mediaItem.mediaId, 0L)
                if (saved > RESUME_MIN_MS) {
                    player?.seekTo(saved)
                    showHud(getString(R.string.resumed_from, formatTime(saved)), 1500)
                }
            }
            lastMediaId = mediaItem?.mediaId
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player?.currentMediaItem?.let { resumePrefs.edit().remove(it.mediaId).apply() }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateSleepIcon()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            updatePipParams()
            if (manualOrientation) return
            val w = videoSize.width * videoSize.pixelWidthHeightRatio
            requestedOrientation =
                if (w >= videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        override fun onPlayerError(error: PlaybackException) {
            showHud(getString(R.string.play_error, error.errorCodeName), 3500)
        }
    }

    // ---------------------------------------------------------------- first-release features

    private fun lock() {
        locked = true
        lockBack.isEnabled = true
        activeView.hideController()
        activeView.useController = false
        topBar.visibility = View.GONE
        showUnlock()
    }

    private fun unlock() {
        locked = false
        lockBack.isEnabled = false
        handler.removeCallbacks(hideUnlock)
        unlockPill.visibility = View.GONE
        activeView.useController = true
        activeView.showController()
    }

    private fun showUnlock() {
        unlockPill.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 2500)
    }

    private fun sleepDialog() {
        val minutes = intArrayOf(15, 30, 45, 60)
        val labels = arrayOf(getString(R.string.sleep_off)) +
            minutes.map { getString(R.string.sleep_minutes, it) } +
            getString(R.string.sleep_end)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        PlayerHolder.cancelSleep()
                        showHud(getString(R.string.sleep_cancelled))
                    }
                    labels.size - 1 -> {
                        PlayerHolder.setSleepAtEnd()
                        showHud(getString(R.string.sleep_set_end), 1500)
                    }
                    else -> {
                        val m = minutes[which - 1]
                        PlayerHolder.setSleep(m)
                        showHud(getString(R.string.sleep_set, formatTime(m * 60_000L)), 1500)
                    }
                }
                updateSleepIcon()
            }
            .show()
    }

    private fun updateSleepIcon() {
        val on = PlayerHolder.sleepActive()
        btnSleep.imageTintList = ColorStateList.valueOf(
            if (on) ThemeColors.accent(this) else 0xFFFFFFFF.toInt()
        )
    }

    private fun toggleBackground() {
        val on = !bgPlay
        settings.edit().putBoolean(Prefs.BG_PLAY, on).apply()
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        showHud(getString(if (on) R.string.background_on else R.string.background_off), 1800)
        updateBgIcon()
    }

    private fun updateBgIcon() {
        btnBg.imageTintList = ColorStateList.valueOf(
            if (bgPlay) ThemeColors.accent(this) else 0xFFFFFFFF.toInt()
        )
    }

    private fun applyAccent() {
        val a = ThemeColors.accent(this)
        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(a)
            setScrubberColor(a)
        }
    }

    private fun equalizerDialog() {
        val names = PlayerHolder.presetNames()
        if (names.isEmpty()) {
            showHud(getString(R.string.eq_unavailable), 1500)
            return
        }
        val labels = (listOf(getString(R.string.eq_off)) + names).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.equalizer)
            .setSingleChoiceItems(labels, PlayerHolder.eqPreset + 1) { d, which ->
                d.dismiss()
                if (PlayerHolder.setPreset(which - 1)) showHud(labels[which])
                else showHud(getString(R.string.eq_unavailable), 1500)
            }
            .show()
    }

    private fun boostDialog() {
        val labels = arrayOf(
            getString(R.string.boost_normal),
            getString(R.string.boost_mid),
            getString(R.string.boost_high),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.volume_boost)
            .setSingleChoiceItems(labels, PlayerHolder.boostLevel) { d, which ->
                d.dismiss()
                if (PlayerHolder.setBoost(which)) showHud("${getString(R.string.volume_boost)}: ${labels[which]}")
                else showHud(getString(R.string.eq_unavailable), 1500)
            }
            .show()
    }

    private fun showMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.player_more, menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.m_eq -> equalizerDialog()
                    R.id.m_audio_delay -> audioDelayDialog()
                    R.id.m_sub_delay -> subtitleDelayDialog()
                    R.id.m_360 -> toggle360()
                    R.id.m_boost -> boostDialog()
                    R.id.m_resize -> cycleResize()
                    R.id.m_rotate -> rotate()
                    R.id.m_pip -> enterPip()
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- other features

    private fun attachSubtitle(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        subOriginal = uri
        subName = displayName(uri)
        subDelayMs = 0L
        applySubtitle(uri, subName)
        showHud(getString(R.string.subtitles_loaded, subName), 1500)
    }

    private fun applySubtitle(uri: Uri, name: String) {
        val p = player ?: return
        val lower = name.lowercase()
        val mime = when {
            lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("und")
            .setLabel(name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val idx = p.currentMediaItemIndex
        val pos = p.currentPosition
        val list = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }.toMutableList()
        if (idx !in list.indices) return
        list[idx] = list[idx].buildUpon().setSubtitleConfigurations(listOf(sub)).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage("und")
            .build()
        p.setMediaItems(list, idx, pos)
        p.prepare()
    }

    // ---------------------------------------------------------------- audio / subtitle sync (VLC style)

    private fun delayDialog(
        title: Int,
        hint: Int,
        stepMs: Long,
        current: () -> Long,
        onChange: (Long) -> Unit,
    ) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val value = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }
        fun show() {
            val v = current()
            value.text = String.format(java.util.Locale.US, "%+.2f s", v / 1000.0)
        }
        fun btn(label: String, delta: Long) = com.google.android.material.button.MaterialButton(this).apply {
            text = label
            setOnClickListener {
                onChange(if (delta == 0L) 0L else current() + delta)
                show()
            }
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            val lp = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = pad / 4; marginEnd = pad / 4 }
            addView(btn("−", -stepMs), lp)
            addView(btn(getString(R.string.reset), 0L), lp)
            addView(btn("+", stepMs), lp)
        }
        val hintView = TextView(this).apply {
            text = getString(hint)
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFA3A3A3.toInt())
            textSize = 13f
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(value)
            addView(row)
            addView(hintView)
        }
        show()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun audioDelayDialog() {
        delayDialog(R.string.audio_delay, R.string.audio_delay_hint, 50L,
            current = { PlayerHolder.audioDelayMs },
            onChange = { PlayerHolder.setAudioDelay(it.coerceIn(-5_000L, 5_000L)) })
    }

    private fun subtitleDelayDialog() {
        if (subOriginal == null) {
            showHud(getString(R.string.subtitle_delay_need), 2000)
            return
        }
        delayDialog(R.string.subtitle_delay, R.string.subtitle_delay_hint, 250L,
            current = { subDelayMs },
            onChange = { ms ->
                subDelayMs = ms.coerceIn(-60_000L, 60_000L)
                shiftSubtitles()
            })
    }

    /** Subtitle file lo timings anni shift chesi kotha file ga load chestham. */
    private fun shiftSubtitles() {
        val src = subOriginal ?: return
        val ms = subDelayMs
        if (ms == 0L) {
            applySubtitle(src, subName)
            return
        }
        try {
            val text = contentResolver.openInputStream(src)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
            val ass = subName.lowercase().let { it.endsWith(".ass") || it.endsWith(".ssa") }
            val shifted = if (ass) {
                Regex("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})").replace(text) { m ->
                    val (h, mi, se, cs) = m.destructured
                    val t = ((h.toLong() * 3600 + mi.toLong() * 60 + se.toLong()) * 1000 + cs.toLong() * 10 + ms)
                        .coerceAtLeast(0L)
                    String.format(java.util.Locale.US, "%d:%02d:%02d.%02d",
                        t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
                }
            } else {
                Regex("(\\d{1,2}):(\\d{2}):(\\d{2})([,.])(\\d{3})").replace(text) { m ->
                    val (h, mi, se, sep, mil) = m.destructured
                    val t = ((h.toLong() * 3600 + mi.toLong() * 60 + se.toLong()) * 1000 + mil.toLong() + ms)
                        .coerceAtLeast(0L)
                    String.format(java.util.Locale.US, "%02d:%02d:%02d%s%03d",
                        t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
                }
            }
            val ext = subName.substringAfterLast('.', "srt")
            cacheDir.listFiles { f -> f.name.startsWith("sub_shift_") }?.forEach { it.delete() }
            val out = java.io.File(cacheDir, "sub_shift_${System.currentTimeMillis()}.$ext")
            out.writeText(shifted, Charsets.UTF_8)
            applySubtitle(Uri.fromFile(out), subName)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 360° + zoom + audio mode

    private fun toggle360() {
        val p = player ?: return
        val from = activeView
        is360 = !is360
        val to = activeView
        PlayerView.switchTargetView(p, from, to)
        from.visibility = View.GONE
        to.visibility = View.VISIBLE
        resetZoom()
        showHud(getString(if (is360) R.string.on_360 else R.string.off_360), 1800)
    }

    private fun applyZoom() {
        playerView.videoSurfaceView?.apply {
            scaleX = zoom
            scaleY = zoom
        }
    }

    private fun resetZoom() {
        zoom = 1f
        applyZoom()
    }

    private fun playingAudio() =
        player?.currentMediaItem?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC

    /** Music aithe controls eppudu kanipistayi, video aithe 5s tarvata dachestham. */
    private fun updateAudioUi() {
        val audio = playingAudio()
        activeView.controllerShowTimeoutMs = if (audio) 0 else 5000
        if (audio) activeView.showController()
    }

    private fun cycleResize() {
        resetZoom()
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIdx]
        showHud(getString(resizeNames[resizeIdx]))
    }

    private fun rotate() {
        manualOrientation = true
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun pipParams(): PictureInPictureParams {
        val vs = player?.videoSize
        var r = if (vs != null && vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height)
        else Rational(16, 9)
        val f = r.toFloat()
        if (f > 2.39f) r = Rational(239, 100) else if (f < 1f / 2.39f) r = Rational(100, 239)
        val b = PictureInPictureParams.Builder().setAspectRatio(r)
        if (Build.VERSION.SDK_INT >= 31) b.setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePipParams() {
        try {
            setPictureInPictureParams(pipParams())
        } catch (_: Exception) {
        }
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            showHud(getString(R.string.no_pip))
            return
        }
        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: Exception) {
            showHud(getString(R.string.pip_failed))
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Background play on unte audio continue avthundi; ledante PiP window
        if (!bgPlay && !playingAudio() && !locked && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        activeView.useController = !isInPictureInPictureMode && !locked
        if (isInPictureInPictureMode) {
            topBar.visibility = View.GONE
            hud.visibility = View.GONE
            unlockPill.visibility = View.GONE
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // PiP window close chesaru
            finishAndRemoveTask()
        }
    }

    // ---------------------------------------------------------------- gestures

    private enum class Mode { NONE, BRIGHTNESS, VOLUME, SEEK, ZOOM }

    private var mode = Mode.NONE
    private var startBright = 0.5f
    private var startVol = 0
    private var seekStart = 0L
    private var seekTarget = 0L

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    private fun showLevel(left: Boolean, fraction: Float) {
        val track = if (left) levelLeft else levelRight
        val fill = if (left) levelLeftFill else levelRightFill
        val text = if (left) levelLeftText else levelRightText
        track.visibility = View.VISIBLE
        val inner = (track.height - track.paddingTop - track.paddingBottom).coerceAtLeast(1)
        fill.layoutParams = fill.layoutParams.apply {
            height = (inner * fraction.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
        }
        text.text = "${(fraction * 100).roundToInt()}%"
        handler.removeCallbacks(hideLevels)
        handler.postDelayed(hideLevels, 900)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                mode = Mode.NONE
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (activeView.isControllerFullyVisible) activeView.hideController()
                else activeView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return true
                val w = playerView.width
                when {
                    e.x < w * 0.35f -> {
                        p.seekBack()
                        showHud(getString(R.string.seek_back))
                    }
                    e.x > w * 0.65f -> {
                        p.seekForward()
                        showHud(getString(R.string.seek_forward))
                    }
                    else -> if (p.isPlaying) p.pause() else p.play()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val s = e1 ?: return false
                val p = player ?: return false
                val w = playerView.width.toFloat()
                val h = playerView.height.toFloat()
                if (w <= 0f || h <= 0f) return false
                val dx = e2.x - s.x
                val dy = e2.y - s.y

                if (mode == Mode.ZOOM || e2.pointerCount > 1) return false
                if (mode == Mode.NONE) {
                    if (s.y < h * 0.08f || s.y > h * 0.92f) return false
                    mode = when {
                        abs(dx) > abs(dy) -> Mode.SEEK
                        s.x < w / 2 -> Mode.BRIGHTNESS
                        else -> Mode.VOLUME
                    }
                    startBright = currentBrightness()
                    startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    seekStart = p.currentPosition
                    seekTarget = seekStart
                }

                when (mode) {
                    Mode.BRIGHTNESS -> {
                        val b = (startBright - dy / h).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = b }
                        showLevel(true, b)
                    }
                    Mode.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val v = (startVol - dy / h * max * 1.2f).roundToInt().coerceIn(0, max)
                        try {
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        } catch (_: SecurityException) {
                        }
                        showLevel(false, v.toFloat() / max)
                    }
                    Mode.SEEK -> {
                        val dur = p.duration
                        if (dur == C.TIME_UNSET || dur <= 0) return true
                        seekTarget = (seekStart + (dx / w * 90_000f).toLong()).coerceIn(0L, dur)
                        val diff = seekTarget - seekStart
                        val sign = if (diff >= 0) "+" else "−"
                        val big = "$sign${formatTime(diff)}"
                        val span = SpannableString("$big\n${formatTime(seekTarget)} / ${formatTime(dur)}")
                        span.setSpan(RelativeSizeSpan(2f), 0, big.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        showHud(span)
                    }
                    Mode.NONE, Mode.ZOOM -> Unit
                }
                return true
            }
        })

        // Two-finger pinch: zoom in / zoom out (50% – 400%)
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = Mode.ZOOM
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(0.5f, 4f)
                if (abs(zoom - 1f) < 0.04f) zoom = 1f
                applyZoom()
                showHud(getString(R.string.zoom_level, (zoom * 100).roundToInt()))
                return true
            }
        })

        playerView.setOnTouchListener { _, ev ->
            if (locked) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) showUnlock()
                return@setOnTouchListener true
            }
            scaleDetector.onTouchEvent(ev)
            if (!zooming && (ev.pointerCount > 1 || scaleDetector.isInProgress)) {
                zooming = true
                mode = Mode.ZOOM
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                detector.onTouchEvent(cancel)
                cancel.recycle()
            }
            if (!zooming) detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == Mode.SEEK) player?.seekTo(seekTarget)
                mode = Mode.NONE
                zooming = false
            }
            true
        }
    }

    private fun showHud(text: CharSequence, ms: Long = 700) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }
}
