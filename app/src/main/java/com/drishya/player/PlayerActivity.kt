package com.drishya.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        private const val SEEK_MS = 10_000L
        private const val RESUME_MIN_MS = 5_000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var hud: TextView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var audio: AudioManager

    private var player: ExoPlayer? = null
    private val prefs by lazy { getSharedPreferences(Prefs.RESUME, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var items: List<MediaItem> = emptyList()
    private var startIndex = 0
    private var startPos = C.TIME_UNSET
    private var lastMediaId: String? = null
    private var manualOrientation = false

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private val resizeNames = arrayOf("Fit", "Zoom (crop)", "Stretch")
    private var resizeIdx = 0

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) attachSubtitle(uri)
        }

    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5_000)
        }
    }
    private val hideHud = Runnable { hud.visibility = View.GONE }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        hud = findViewById(R.id.hud)
        topBar = findViewById(R.id.top_bar)
        titleView = findViewById(R.id.player_title)
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
        findViewById<View>(R.id.btn_subtitle).setOnClickListener { pickSubtitle.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.btn_resize).setOnClickListener { cycleResize() }
        findViewById<View>(R.id.btn_rotate).setOnClickListener { rotate() }
        findViewById<View>(R.id.btn_pip).setOnClickListener { enterPip() }

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (!isInPictureInPictureMode) topBar.visibility = visibility
            }
        )
        setupGestures()
        readIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        releasePlayer()
        readIntent(intent)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) initPlayer()
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
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

    private fun readIntent(i: Intent) {
        startPos = C.TIME_UNSET
        val uris = i.getStringArrayListExtra(EXTRA_URIS)
        if (!uris.isNullOrEmpty()) {
            val titles = i.getStringArrayListExtra(EXTRA_TITLES)
            items = uris.mapIndexed { idx, u ->
                buildItem(Uri.parse(u), titles?.getOrNull(idx) ?: "Video")
            }
            startIndex = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.size - 1)
        } else {
            val data = i.data
            if (data == null) {
                items = emptyList()
                finish()
                return
            }
            items = listOf(buildItem(data, displayName(data)))
            startIndex = 0
        }
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

    private fun initPlayer() {
        if (player != null || items.isEmpty()) return

        // EXTENSION_RENDERER_MODE_ON: phone decode cheyyaleni audio (AC3, DTS…) ki FFmpeg vadutundi
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val p = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player = p
        playerView.player = p
        playerView.resizeMode = resizeModes[resizeIdx]
        p.addListener(listener)

        val first = items[startIndex]
        var pos = startPos
        if (pos == C.TIME_UNSET) {
            val saved = prefs.getLong(first.mediaId, 0L)
            if (saved > RESUME_MIN_MS) {
                pos = saved
                showHud("Resumed from ${formatTime(saved)}", 1500)
            } else {
                pos = 0L
            }
        }
        lastMediaId = first.mediaId
        titleView.text = first.mediaMetadata.title
        p.setMediaItems(items, startIndex, pos)
        p.prepare()
        p.playWhenReady = true
        handler.post(saveTick)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(saveTick)
        val p = player ?: return
        savePosition()
        items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
        startIndex = p.currentMediaItemIndex
        startPos = p.currentPosition
        p.removeListener(listener)
        p.release()
        player = null
        playerView.player = null
    }

    private fun savePosition() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val pos = p.currentPosition
        val dur = p.duration
        val edit = prefs.edit()
        if (dur != C.TIME_UNSET && dur > 0 && pos > dur - RESUME_MIN_MS) edit.remove(item.mediaId)
        else if (pos > RESUME_MIN_MS) edit.putLong(item.mediaId, pos)
        edit.apply()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            titleView.text = mediaItem?.mediaMetadata?.title
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                lastMediaId?.let { prefs.edit().remove(it).apply() }
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                mediaItem?.let {
                    val saved = prefs.getLong(it.mediaId, 0L)
                    if (saved > RESUME_MIN_MS) {
                        player?.seekTo(saved)
                        showHud("Resumed from ${formatTime(saved)}", 1500)
                    }
                }
            }
            lastMediaId = mediaItem?.mediaId
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player?.currentMediaItem?.let { prefs.edit().remove(it.mediaId).apply() }
            }
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
            showHud("Play cheyyalekapoyam 😕\n${error.errorCodeName}", 3500)
        }
    }

    // ---------------------------------------------------------------- features

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
        list[idx] = list[idx].buildUpon().setSubtitleConfigurations(listOf(sub)).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage("und")
            .build()
        p.setMediaItems(list, idx, pos)
        p.prepare()
        showHud("Subtitles: $name", 1500)
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIdx]
        showHud(resizeNames[resizeIdx])
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
            showHud("Ee phone lo PiP support ledu")
            return
        }
        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: Exception) {
            showHud("PiP start avvaledu")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            topBar.visibility = View.GONE
            hud.visibility = View.GONE
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
                    e.x < w * 0.35f -> { p.seekBack(); showHud("« 10s") }
                    e.x > w * 0.65f -> { p.seekForward(); showHud("10s »") }
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
                    // screen anchula nunchi start ayithe system gestures ki vadileddham
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
                        showHud("☀  ${(b * 100).roundToInt()}%")
                    }
                    Mode.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val v = (startVol - dy / h * max * 1.2f).roundToInt().coerceIn(0, max)
                        try {
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        } catch (_: SecurityException) {
                        }
                        showHud("🔊  ${v * 100 / max}%")
                    }
                    Mode.SEEK -> {
                        val dur = p.duration
                        if (dur == C.TIME_UNSET || dur <= 0) return true
                        seekTarget = (seekStart + (dx / w * 90_000f).toLong()).coerceIn(0L, dur)
                        val diff = seekTarget - seekStart
                        val sign = if (diff >= 0) "+" else "−"
                        showHud("$sign${formatTime(diff)}\n${formatTime(seekTarget)} / ${formatTime(dur)}")
                    }
                    Mode.NONE -> Unit
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == Mode.SEEK) player?.seekTo(seekTarget)
                mode = Mode.NONE
            }
            true
        }
    }

    private fun showHud(text: String, ms: Long = 700) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }
}
