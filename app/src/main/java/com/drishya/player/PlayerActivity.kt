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
        private const val RESUME_MIN_MS = 5_000L
    }

    private lateinit var playerView: PlayerView
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
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

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (!isInPictureInPictureMode && !locked) topBar.visibility = visibility
            }
        )
        setupGestures()
        handleIntent(intent)
        updateBgIcon()
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
            p.removeListener(listener)
            if (!(bgPlay && p.isPlaying)) p.pause()
        }
        playerView.player = null
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
                pendingItems = uris.mapIndexed { idx, u ->
                    buildItem(Uri.parse(u), titles?.getOrNull(idx) ?: "Video")
                }
                pendingIndex = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.size - 1)
            }
            data != null -> {
                pendingItems = listOf(buildItem(data, displayName(data)))
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

    private fun buildItem(uri: Uri, title: String): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()

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
        playerView.player = p
        playerView.resizeMode = resizeModes[resizeIdx]
        p.addListener(listener)
        applyPending()
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
        p.setMediaItems(items, pendingIndex, pos)
        p.prepare()
        p.play()
        Library.markPlayed(this, first.mediaId)
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
        playerView.hideController()
        playerView.useController = false
        topBar.visibility = View.GONE
        showUnlock()
    }

    private fun unlock() {
        locked = false
        lockBack.isEnabled = false
        handler.removeCallbacks(hideUnlock)
        unlockPill.visibility = View.GONE
        playerView.useController = true
        playerView.showController()
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
            if (on) ContextCompat.getColor(this, R.color.accent) else 0xFFFFFFFF.toInt()
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
            if (bgPlay) ContextCompat.getColor(this, R.color.accent) else 0xFFFFFFFF.toInt()
        )
    }

    private fun showMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.player_more, menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
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
        val p = player ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = displayName(uri)
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
        showHud(getString(R.string.subtitles_loaded, name), 1500)
    }

    private fun cycleResize() {
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
        if (!bgPlay && !locked && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode && !locked
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

    private enum class Mode { NONE, BRIGHTNESS, VOLUME, SEEK }

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
                if (playerView.isControllerFullyVisible) playerView.hideController()
                else playerView.showController()
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
                    Mode.NONE -> Unit
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, ev ->
            if (locked) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) showUnlock()
                return@setOnTouchListener true
            }
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == Mode.SEEK) player?.seekTo(seekTarget)
                mode = Mode.NONE
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
