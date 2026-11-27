package com.drishya.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_ARTISTS = "artists"
        const val EXTRA_SIZES = "sizes"
        private const val RESUME_MIN_MS = 5_000L
        private const val KEY_SIZE = "size"
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
    }

    // ---- views
    private lateinit var root: View
    private lateinit var playerView: PlayerView
    private lateinit var playerView360: PlayerView
    private lateinit var controls: View
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var btnLock: View
    private lateinit var bottomBox: View
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var timeBar: DefaultTimeBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var hud: TextView
    private lateinit var levelLeft: View
    private lateinit var levelLeftFill: View
    private lateinit var levelLeftText: TextView
    private lateinit var levelRight: View
    private lateinit var levelRightFill: View
    private lateinit var levelRightText: TextView
    private lateinit var unlockPill: View
    private lateinit var panelDim: View
    private lateinit var panelSettings: View
    private lateinit var panelSettingsContent: LinearLayout
    private lateinit var panelQueue: View
    private lateinit var queueList: RecyclerView
    private lateinit var audio: AudioManager
    private lateinit var seekPreview: View
    private lateinit var seekPreviewImg: ImageView
    private lateinit var seekPreviewTime: TextView

    // Seek preview thumbnails
    private val thumbExec = Executors.newSingleThreadExecutor()
    @Volatile private var retriever: MediaMetadataRetriever? = null
    private var thumbBusy = false
    @Volatile private var thumbWanted = -1L

    // Long-press 2x speed
    private var fastHold = false
    private var holdSpeed = 1f

    // Cast to TV
    private var castCtx: CastContext? = null
    private var castSession: CastSession? = null
    private lateinit var btnCast: ImageButton
    private val castListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = startCasting(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = startCasting(session)
        override fun onSessionEnded(session: CastSession, error: Int) = stopCasting()
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            showHud(getString(R.string.fp_cast_failed), 2000)
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    // A-B repeat
    private var abA = C.TIME_UNSET
    private var abB = C.TIME_UNSET
    private val abTick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && abA != C.TIME_UNSET && abB != C.TIME_UNSET && p.currentPosition >= abB) {
                p.seekTo(abA)
            }
            if (abB != C.TIME_UNSET) handler.postDelayed(this, 150)
        }
    }

    // ---- state
    private var player: ExoPlayer? = null
    private val resumePrefs by lazy { getSharedPreferences(Prefs.RESUME, MODE_PRIVATE) }
    private val settings by lazy { getSharedPreferences(Prefs.SETTINGS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var pendingItems: List<MediaItem>? = null
    private var pendingIndex = 0
    private var lastMediaId: String? = null
    private var manualOrientation = false
    private var locked = false
    private var scrubbing = false
    private var audioOnly = false
    private var muted = false
    private var savedVolume = 1f
    private var is360 = false
    private val activeView get() = if (is360) playerView360 else playerView
    private var zoom = 1f
    private var zooming = false

    private var redirected = false
    private var subOriginal: Uri? = null
    private var subName = ""
    private var subDelayMs = 0L

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

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                locked -> showUnlock()
                panelOpen() -> closePanels()
            }
        }
    }

    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5_000)
        }
    }
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }
    private val autoHide = Runnable { hideControls() }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val hideLevels = Runnable {
        levelLeft.visibility = View.INVISIBLE
        levelRight.visibility = View.INVISIBLE
    }
    private val hideUnlock = Runnable { unlockPill.visibility = View.GONE }

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        root = findViewById(R.id.root)
        playerView = findViewById(R.id.player_view)
        playerView360 = findViewById(R.id.player_view_360)
        controls = findViewById(R.id.controls)
        topBar = findViewById(R.id.top_bar)
        titleView = findViewById(R.id.player_title)
        btnLock = findViewById(R.id.btn_lock)
        bottomBox = findViewById(R.id.bottom_box)
        posText = findViewById(R.id.pos_text)
        durText = findViewById(R.id.dur_text)
        timeBar = findViewById(R.id.time_bar)
        btnPlay = findViewById(R.id.btn_play)
        btnMute = findViewById(R.id.btn_mute)
        hud = findViewById(R.id.hud)
        levelLeft = findViewById(R.id.level_left)
        levelLeftFill = findViewById(R.id.level_left_fill)
        levelLeftText = findViewById(R.id.level_left_text)
        levelRight = findViewById(R.id.level_right)
        levelRightFill = findViewById(R.id.level_right_fill)
        levelRightText = findViewById(R.id.level_right_text)
        unlockPill = findViewById(R.id.unlock_pill)
        panelDim = findViewById(R.id.panel_dim)
        panelSettings = findViewById(R.id.panel_settings)
        panelSettingsContent = findViewById(R.id.panel_settings_content)
        panelQueue = findViewById(R.id.panel_queue)
        queueList = findViewById(R.id.queue_list)
        seekPreview = findViewById(R.id.seek_preview)
        seekPreviewImg = findViewById(R.id.seek_preview_img)
        seekPreviewTime = findViewById(R.id.seek_preview_time)
        queueList.layoutManager = LinearLayoutManager(this)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val pad = dp(8)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { _, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(pad + c.left, pad + c.top, pad + c.right, pad * 3)
            (bottomBox.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
                lp.setMargins(dp(12) + c.left, 0, dp(12) + c.right, dp(12) + c.bottom)
                bottomBox.layoutParams = lp
            }
            insets
        }

        // top bar
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_pip).setOnClickListener { enterPip() }
        btnCast = findViewById(R.id.btn_cast)
        castCtx = Cast.context(this)
        btnCast.visibility = if (castCtx != null) View.VISIBLE else View.GONE
        btnCast.setOnClickListener { castClicked() }
        findViewById<View>(R.id.btn_queue).setOnClickListener { openQueue() }
        findViewById<View>(R.id.btn_more).setOnClickListener { openSettings() }
        // middle + bottom
        btnLock.setOnClickListener { lock() }
        findViewById<View>(R.id.btn_rotate).setOnClickListener { rotate(); bumpHide() }
        findViewById<View>(R.id.btn_fit).setOnClickListener { cycleResize(); bumpHide() }
        findViewById<View>(R.id.btn_prev).setOnClickListener { previous(); bumpHide() }
        findViewById<View>(R.id.btn_next).setOnClickListener { player?.seekToNextMediaItem(); bumpHide() }
        btnPlay.setOnClickListener { togglePlay(); bumpHide() }
        btnMute.setOnClickListener { toggleMute(); bumpHide() }
        unlockPill.setOnLongClickListener { unlock(); true }
        unlockPill.setOnClickListener { showUnlock() }
        panelDim.setOnClickListener { closePanels() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubbing = true
                handler.removeCallbacks(autoHide)
                startPreview(position)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                posText.text = formatTime(position)
                movePreview(position)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                stopPreview()
                val remote = castSession?.remoteMediaClient
                if (!canceled && remote != null) {
                    remote.seek(MediaSeekOptions.Builder().setPosition(position).build())
                } else if (!canceled) player?.seekTo(position)
                bumpHide()
            }
        })

        val panelWidth = min(dp(400), (resources.displayMetrics.widthPixels * 0.86f).toInt())
        panelSettings.layoutParams = panelSettings.layoutParams.apply { width = panelWidth }
        panelQueue.layoutParams = panelQueue.layoutParams.apply { width = panelWidth }

        setupGestures()
        handleIntent(intent)
        PlayerHolder.applyPlayMode(settings.getInt("play_mode", PlayerHolder.MODE_ALL))
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
        showControls()
        castCtx?.sessionManager?.let { sm ->
            sm.addSessionManagerListener(castListener, CastSession::class.java)
            val cur = sm.currentCastSession
            if (cur != null && cur.isConnected) {
                castSession = cur
                btnCast.setImageResource(R.drawable.ic_cast_connected)
            } else if (castSession != null) {
                castSession = null
                btnCast.setImageResource(R.drawable.ic_cast)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(abTick)
        castCtx?.sessionManager?.removeSessionManagerListener(castListener, CastSession::class.java)
        if (fastHold) endFastHold()
        val p = player
        if (p != null) {
            val keep = (bgPlay || audioOnly || playingAudio()) && p.isPlaying
            p.removeListener(listener)
            if (!keep) p.pause()
        }
        playerView.player = null
        playerView360.player = null
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        val r = retriever
        retriever = null
        thumbExec.execute { try { r?.release() } catch (_: Exception) {} }
        thumbExec.shutdown()
        if (isFinishing && !redirected) {
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ================================================================ media

    private fun handleIntent(i: Intent) {
        val uris = i.getStringArrayListExtra(EXTRA_URIS)
        val data = i.data
        when {
            !uris.isNullOrEmpty() -> {
                val titles = i.getStringArrayListExtra(EXTRA_TITLES)
                val artists = i.getStringArrayListExtra(EXTRA_ARTISTS)
                val sizes = i.getLongArrayExtra(EXTRA_SIZES)
                val audioFlag = i.getBooleanExtra(EXTRA_AUDIO, false)
                pendingItems = uris.mapIndexed { idx, u ->
                    buildItem(
                        Uri.parse(u), titles?.getOrNull(idx) ?: "Video",
                        audio = audioFlag || isAudioName(u),
                        artist = artists?.getOrNull(idx),
                        size = sizes?.getOrNull(idx) ?: 0L,
                    )
                }
                pendingIndex = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.size - 1)
            }
            data != null -> {
                val name = displayName(data)
                val audioFlag = isAudioName(name) || i.type?.startsWith("audio/") == true
                pendingItems = listOf(buildItem(data, name, audio = audioFlag))
                pendingIndex = 0
            }
            // Notification nunchi vachhamu: paata aithe music screen, video aithe ide
            PlayerHolder.peek()?.currentMediaItem?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC -> {
                redirected = true
                startActivity(Intent(this, MusicActivity::class.java))
                finish()
            }
            (PlayerHolder.peek()?.mediaItemCount ?: 0) > 0 -> pendingItems = null
            else -> finish()
        }
        // Intent ni malli process cheyyakunda (rotation / notification)
        i.removeExtra(EXTRA_URIS)
        i.data = null
    }

    private fun buildItem(
        uri: Uri,
        title: String,
        audio: Boolean = false,
        artist: String? = null,
        size: Long = 0L,
    ): MediaItem {
        val meta = MediaMetadata.Builder().setTitle(title)
        if (audio) meta.setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (!artist.isNullOrBlank()) meta.setArtist(artist)
        if (size > 0) meta.setExtras(Bundle().apply { putLong(KEY_SIZE, size) })
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
        // Mundu select chesina Flikk Surround mode malli on
        val sm = settings.getInt("surround", 0)
        if (sm > 0 && PlayerHolder.surround != sm) PlayerHolder.setSurround(sm)
        p.currentMediaItem?.let { titleView.text = it.mediaMetadata.title }
        updatePlayIcon()
        updateMarkers()
        if (abB != C.TIME_UNSET) {
            handler.removeCallbacks(abTick)
            handler.post(abTick)
        }
        handler.removeCallbacks(saveTick)
        handler.post(saveTick)
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
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
        PlayerHolder.applyPlayMode(PlayerHolder.playMode)
        p.prepare()
        p.play()
        Library.markPlayed(this, first.mediaId)
        showControls()
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
            if (mediaItem?.mediaId != lastMediaId) {
                clearAb(silent = true)
                releaseRetriever()
            }
            lastMediaId = mediaItem?.mediaId
            updateMarkers()
            if (panelQueue.visibility == View.VISIBLE) fillQueue()
            if (playingAudio()) showControls()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player?.currentMediaItem?.let { resumePrefs.edit().remove(it.mediaId).apply() }
                showControls()
            }
            updateProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcon()
            if (isPlaying) bumpHide() else showControls()
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

    // ================================================================ controls overlay

    private fun showControls() {
        if (locked || isInPictureInPictureMode) return
        controls.visibility = View.VISIBLE
        updateProgress()
        updatePlayIcon()
        bumpHide()
    }

    private fun hideControls() {
        if (scrubbing || panelOpen()) return
        if (playingAudio() || player?.isPlaying != true) return
        controls.visibility = View.GONE
    }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) {
            handler.removeCallbacks(autoHide)
            controls.visibility = View.GONE
        } else {
            showControls()
        }
    }

    private fun bumpHide() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 4000)
    }

    private fun updateProgress() {
        val p = player ?: return
        if (controls.visibility != View.VISIBLE) return
        val remote = castSession?.remoteMediaClient
        if (remote != null && remote.hasMediaSession()) {
            val rd = remote.streamDuration
            val rp = remote.approximateStreamPosition
            if (rd > 0) {
                timeBar.setDuration(rd)
                durText.text = formatTime(rd)
            }
            if (!scrubbing) {
                timeBar.setPosition(rp)
                posText.text = formatTime(rp)
            }
            btnPlay.setImageResource(if (remote.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            return
        }
        val dur = p.duration
        val pos = p.currentPosition
        if (dur != C.TIME_UNSET && dur > 0) {
            timeBar.setDuration(dur)
            durText.text = formatTime(dur)
        }
        if (!scrubbing) {
            timeBar.setPosition(pos)
            posText.text = formatTime(pos)
        }
        timeBar.setBufferedPosition(p.bufferedPosition)
    }

    private fun updatePlayIcon() {
        val playing = player?.isPlaying == true
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun togglePlay() {
        castSession?.remoteMediaClient?.let {
            it.togglePlayback()
            return
        }
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(p.currentMediaItemIndex, 0)
            p.play()
        }
    }

    private fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    private fun toggleMute() {
        val p = player ?: return
        muted = !muted
        if (muted) {
            savedVolume = p.volume
            p.volume = 0f
        } else {
            p.volume = if (savedVolume > 0f) savedVolume else 1f
        }
        btnMute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
    }

    // ================================================================ panels

    private fun panelOpen() =
        panelSettings.visibility == View.VISIBLE || panelQueue.visibility == View.VISIBLE

    private fun openPanel(panel: View) {
        closePanels()
        controls.visibility = View.GONE
        panelDim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.translationX = panel.layoutParams.width.toFloat()
        panel.animate().translationX(0f).setDuration(180).start()
        backCallback.isEnabled = true
    }

    private fun closePanels() {
        panelSettings.visibility = View.GONE
        panelQueue.visibility = View.GONE
        panelDim.visibility = View.GONE
        backCallback.isEnabled = locked
    }

    private fun openQueue() {
        fillQueue()
        openPanel(panelQueue)
        player?.currentMediaItemIndex?.let { queueList.scrollToPosition(it) }
    }

    private fun fillQueue() {
        val p = player ?: return
        val items = (0 until p.mediaItemCount).map { i ->
            val m = p.getMediaItemAt(i)
            val size = m.mediaMetadata.extras?.getLong(KEY_SIZE) ?: 0L
            val sub = listOfNotNull(
                m.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() },
                if (size > 0) formatSize(size) else null,
            ).joinToString(" · ")
            QueueItem(Uri.parse(m.mediaId), m.mediaMetadata.title?.toString() ?: "", sub)
        }
        queueList.adapter = QueueAdapter(items, p.currentMediaItemIndex, getString(R.string.now_playing)) { i ->
            if (i in 0 until p.mediaItemCount && i != p.currentMediaItemIndex) {
                savePosition()
                p.seekTo(i, C.TIME_UNSET)
                p.play()
            }
            closePanels()
        }
    }

    private fun panelTitle(text: String, big: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(if (big) 0xFFBBBBBB.toInt() else 0xFFFFFFFF.toInt())
        textSize = if (big) 22f else 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(if (big) 4 else 22), 0, dp(12))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x26FFFFFF)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(14)
        }
    }

    /** Icon paina, label kinda — Share / Audio only / Info / Trim laga. */
    private fun iconTile(icon: Int, label: String, active: Boolean = false, onClick: () -> Unit): View {
        val tile = LinearLayout(this)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER
        tile.setPadding(0, dp(8), 0, dp(8))
        tile.isClickable = true
        tile.setOnClickListener { onClick() }
        val img = ImageView(this)
        img.setImageResource(icon)
        img.imageTintList = ColorStateList.valueOf(if (active) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        if (active) img.background = ContextCompat.getDrawable(this, R.drawable.bg_mode_on)
        img.setPadding(dp(9), dp(9), dp(9), dp(9))
        tile.addView(img, LinearLayout.LayoutParams(dp(44), dp(44)))
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 12.5f
        tv.gravity = Gravity.CENTER
        tv.maxLines = 2
        tv.setPadding(0, dp(6), 0, 0)
        tile.addView(tv)
        return tile
    }

    private fun tileRows(tiles: List<View>, perRow: Int = 4): List<LinearLayout> =
        tiles.chunked(perRow).map { chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEach { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(perRow - chunk.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            row
        }

    private fun openSettings() {
        buildSettings()
        openPanel(panelSettings)
    }

    // ---------------------------------------------------------------- settings panel building blocks

    /** Chinna grey CAPS label: SPEED, SOUND … */
    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(0xFF8A8A8A.toInt())
        textSize = 11.5f
        letterSpacing = 0.14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(22), 0, dp(8))
    }

    /** Rounded dark card — prathi section okka card lo. */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_card)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
        if (on) setTypeface(typeface, Typeface.BOLD)
        background = ContextCompat.getDrawable(this@PlayerActivity, if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Chips ni samanga grid ga (perRow prathi line). */
    private fun chipGrid(chips: List<View>, perRow: Int): List<LinearLayout> =
        chips.chunked(perRow).mapIndexed { r, chunk ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                chunk.forEachIndexed { i, v ->
                    addView(v, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        if (i > 0) marginStart = dp(8)
                        if (r > 0) topMargin = dp(8)
                    })
                }
                repeat(perRow - chunk.size) {
                    addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
                }
            }
        }

    private fun surroundName(m: Int) = getString(
        when (m) {
            PlayerHolder.SURROUND_MOVIE -> R.string.surround_movie
            PlayerHolder.SURROUND_MUSIC -> R.string.surround_music
            PlayerHolder.SURROUND_VOICE -> R.string.surround_voice
            PlayerHolder.SURROUND_NIGHT -> R.string.surround_night
            PlayerHolder.SURROUND_3D -> R.string.surround_3d
            else -> R.string.surround_off
        }
    )

    private fun applySurround(m: Int) {
        val ok = PlayerHolder.setSurround(m)
        if (!ok && m != PlayerHolder.SURROUND_OFF) {
            showHud(getString(R.string.surround_unsupported), 1800)
            return
        }
        settings.edit().putInt("surround", m).apply()
        showHud(if (m == PlayerHolder.SURROUND_OFF) getString(R.string.surround_title) + " · " + surroundName(m)
        else getString(R.string.surround_title) + " · " + surroundName(m), 1200)
    }

    /** Phone lo unna Dolby Atmos / sound effects screen open cheyyi. */
    private fun openPhoneEffects() {
        val i = Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, PlayerHolder.audioSession())
            putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MOVIE)
        }
        try {
            startActivity(i)
            return
        } catch (_: Exception) {
        }
        for (pkg in listOf("com.dolby.daxappui", "com.dolby.atmos", "com.dolby.daxappUI", "com.dolby.ds1appUI")) {
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                startActivity(launch)
                return
            } catch (_: Exception) {
            }
        }
        showHud(getString(R.string.phone_effects_none), 1800)
    }

    private fun buildSettings() {
        val c = panelSettingsContent
        c.removeAllViews()
        c.addView(TextView(this).apply {
            text = getString(R.string.playback_settings)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), 0, 0)
        })
        c.addView(TextView(this).apply {
            text = player?.currentMediaItem?.mediaMetadata?.title ?: ""
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(2), 0, 0)
        })

        // 1 · SPEED
        c.addView(sectionLabel(getString(R.string.speed)))
        val speedCard = card()
        val current = player?.playbackParameters?.speed ?: 1f
        chipGrid(SPEEDS.map { sp ->
            val label = if (sp == sp.toInt().toFloat()) "${sp.toInt()}x" else "${sp}x"
            chip(label, abs(sp - current) < 0.01f) {
                player?.playbackParameters = PlaybackParameters(sp)
                showHud(label)
                buildSettings()
            }
        }, 4).forEach { speedCard.addView(it) }
        c.addView(speedCard)

        // 2 · SOUND — Flikk Surround hero + audio tools
        c.addView(sectionLabel(getString(R.string.section_sound)))
        val sound = card()
        val sm = PlayerHolder.surround
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(4), dp(10))
        }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_surround)
            imageTintList = ColorStateList.valueOf(if (sm > 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            background = ContextCompat.getDrawable(this@PlayerActivity, if (sm > 0) R.drawable.bg_mode_on else R.drawable.bg_chip_off)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val headText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        headText.addView(TextView(this).apply {
            text = getString(R.string.surround_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        headText.addView(TextView(this).apply {
            text = if (sm > 0) getString(R.string.surround_on, surroundName(sm)) else getString(R.string.surround_desc_off)
            setTextColor(0xFFA3A3A3.toInt())
            textSize = 12.5f
        })
        head.addView(headText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = if (sm > 0) "ON" else "OFF"
            setTextColor(if (sm > 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@PlayerActivity, if (sm > 0) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
            isClickable = true
            setOnClickListener {
                applySurround(if (sm > 0) PlayerHolder.SURROUND_OFF else PlayerHolder.SURROUND_MOVIE)
                buildSettings()
            }
        }, LinearLayout.LayoutParams(dp(56), dp(32)))
        sound.addView(head)
        val modes = listOf(PlayerHolder.SURROUND_MOVIE, PlayerHolder.SURROUND_MUSIC, PlayerHolder.SURROUND_VOICE,
            PlayerHolder.SURROUND_NIGHT, PlayerHolder.SURROUND_3D)
        chipGrid(modes.map { m ->
            chip(surroundName(m), m == sm) {
                applySurround(if (m == sm) PlayerHolder.SURROUND_OFF else m)
                buildSettings()
            }
        }, 5).forEach { sound.addView(it) }
        sound.addView(View(this).apply { setBackgroundColor(0xFF1E1E1E.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(4) })
        tileRows(listOf(
            iconTile(R.drawable.ic_music, getString(R.string.audio_track)) {
                trackDialog(C.TRACK_TYPE_AUDIO, R.string.audio_track)
            },
            iconTile(R.drawable.ic_equalizer, getString(R.string.equalizer), active = PlayerHolder.eqPreset >= 0) { equalizerDialog() },
            iconTile(R.drawable.ic_volume, getString(R.string.volume_boost), active = PlayerHolder.boostLevel > 0) { boostDialog() },
            iconTile(R.drawable.ic_timer, getString(R.string.audio_delay)) { audioDelayDialog() },
        )).forEach { sound.addView(it) }
        // Phone Dolby Atmos / sound effects
        val phoneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(6))
            isClickable = true
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_chip_off)
            setOnClickListener { openPhoneEffects() }
        }
        phoneRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tune)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(6) })
        val pt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, dp(6)) }
        pt.addView(TextView(this).apply {
            text = getString(R.string.phone_effects)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        })
        pt.addView(TextView(this).apply {
            text = getString(R.string.phone_effects_sub)
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 11.5f
        })
        phoneRow.addView(pt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        phoneRow.addView(TextView(this).apply { text = "›"; setTextColor(0xFFFFFFFF.toInt()); textSize = 22f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        sound.addView(phoneRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        c.addView(sound)

        // 3 · SUBTITLES
        c.addView(sectionLabel(getString(R.string.section_subtitles)))
        val subs = card()
        tileRows(listOf(
            iconTile(R.drawable.ic_subtitle, getString(R.string.subtitle_track)) {
                trackDialog(C.TRACK_TYPE_TEXT, R.string.subtitle_track)
            },
            iconTile(R.drawable.ic_add, getString(R.string.load_subtitles)) { pickSubtitle.launch(arrayOf("*/*")) },
            iconTile(R.drawable.ic_timer, getString(R.string.subtitle_delay)) { subtitleDelayDialog() },
        )).forEach { subs.addView(it) }
        c.addView(subs)

        // 4 · PLAYBACK
        c.addView(sectionLabel(getString(R.string.playback_mode)))
        val play = card()
        val mode = PlayerHolder.playMode
        val playModes = listOf(
            Triple(PlayerHolder.MODE_ALL, R.drawable.ic_repeat, R.string.mode_all),
            Triple(PlayerHolder.MODE_ONE, R.drawable.ic_repeat_one, R.string.mode_one),
            Triple(PlayerHolder.MODE_ONCE, R.drawable.ic_play_once, R.string.mode_once),
            Triple(PlayerHolder.MODE_SHUFFLE, R.drawable.ic_shuffle, R.string.mode_shuffle),
        )
        tileRows(playModes.map { (m, icon, label) ->
            iconTile(icon, getString(label), active = m == mode) {
                PlayerHolder.applyPlayMode(m)
                settings.edit().putInt("play_mode", m).apply()
                showHud(getString(label))
                buildSettings()
            }
        } + listOf(
            iconTile(R.drawable.ic_moon, getString(R.string.sleep_timer), active = PlayerHolder.sleepActive()) {
                sleepDialog()
            },
            iconTile(R.drawable.ic_headphones, getString(R.string.background_play), active = bgPlay) {
                toggleBackground()
                buildSettings()
            },
            iconTile(R.drawable.ic_music, getString(R.string.audio_only), active = audioOnly) {
                toggleAudioOnly()
                buildSettings()
            },
        )).forEach { play.addView(it) }
        c.addView(play)

        // 5 · VIDEO
        c.addView(sectionLabel(getString(R.string.section_video)))
        val video = card()
        tileRows(listOf(
            iconTile(R.drawable.ic_resize, getString(R.string.resize)) { cycleResize() },
            iconTile(R.drawable.ic_360, getString(R.string.mode_360), active = is360) {
                toggle360()
                buildSettings()
            },
            iconTile(R.drawable.ic_lock, getString(R.string.lock)) {
                closePanels()
                lock()
            },
            iconTile(R.drawable.ic_info, getString(R.string.info)) { showInfo() },
        )).forEach { video.addView(it) }
        c.addView(video)

        // 6 · TOOLS
        c.addView(sectionLabel(getString(R.string.tools)))
        val tools = card()
        tileRows(listOf(
            iconTile(R.drawable.ic_ab, getString(R.string.ab_repeat), active = abA != C.TIME_UNSET) {
                abStep()
                buildSettings()
            },
            iconTile(R.drawable.ic_bookmark, getString(R.string.add_bookmark)) { addBookmark() },
            iconTile(R.drawable.ic_bookmarks, getString(R.string.bookmarks)) { bookmarksDialog() },
            iconTile(R.drawable.ic_cut, getString(R.string.trim)) { trimDialog() },
            iconTile(R.drawable.ic_share, getString(R.string.share)) { shareCurrent() },
        )).forEach { tools.addView(it) }
        c.addView(tools)
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
    }

    // ================================================================ A-B repeat + bookmarks

    private fun abStep() {
        val p = player ?: return
        when {
            abA == C.TIME_UNSET -> {
                abA = p.currentPosition
                showHud(getString(R.string.ab_set_a, formatTime(abA)))
            }
            abB == C.TIME_UNSET -> {
                val pos = p.currentPosition
                if (pos <= abA + 500) {
                    // B, A kanna mundu undakudadu
                    abA = pos
                    showHud(getString(R.string.ab_set_a, formatTime(abA)))
                } else {
                    abB = pos
                    p.seekTo(abA)
                    showHud(getString(R.string.ab_set_b, formatTime(abA), formatTime(abB)))
                    handler.removeCallbacks(abTick)
                    handler.post(abTick)
                }
            }
            else -> clearAb(silent = false)
        }
        updateMarkers()
    }

    private fun clearAb(silent: Boolean) {
        val had = abA != C.TIME_UNSET
        abA = C.TIME_UNSET
        abB = C.TIME_UNSET
        handler.removeCallbacks(abTick)
        if (had && !silent) showHud(getString(R.string.ab_cleared))
        updateMarkers()
    }

    private fun currentId(): String? = player?.currentMediaItem?.mediaId

    private fun addBookmark() {
        val p = player ?: return
        val id = currentId() ?: return
        Bookmarks.add(this, id, p.currentPosition)
        showHud(getString(R.string.bookmark_added, formatTime(p.currentPosition)))
        updateMarkers()
    }

    private fun bookmarksDialog() {
        val id = currentId() ?: return
        val list = Bookmarks.list(this, id)
        if (list.isEmpty()) {
            showHud(getString(R.string.no_bookmarks))
            return
        }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks)
            .setItems(list.map { formatTime(it) }.toTypedArray()) { _, which ->
                player?.seekTo(list[which])
                closePanels()
            }
            .setNegativeButton(R.string.bookmark_clear) { _, _ ->
                Bookmarks.clear(this, id)
                updateMarkers()
            }
            .setPositiveButton(android.R.string.cancel, null)
        b.show()
    }

    /** Time bar meeda bookmark + A/B gurthulu. */
    private fun updateMarkers() {
        val id = currentId()
        val marks = ArrayList<Long>()
        if (id != null) marks += Bookmarks.list(this, id)
        if (abA != C.TIME_UNSET) marks += abA
        if (abB != C.TIME_UNSET) marks += abB
        marks.sort()
        timeBar.setAdGroupTimesMs(marks.toLongArray(), BooleanArray(marks.size), marks.size)
    }

    // ================================================================ seek preview

    private fun previewUri(): Uri? {
        val id = currentId() ?: return null
        val u = Uri.parse(id)
        if (playingAudio() || is360) return null
        return if (u.scheme == "content" || u.scheme == "file" || u.scheme == null) u else null
    }

    private fun releaseRetriever() {
        val r = retriever
        retriever = null
        thumbWanted = -1L
        if (r != null && !thumbExec.isShutdown) thumbExec.execute { try { r.release() } catch (_: Exception) {} }
    }

    private fun startPreview(position: Long) {
        val uri = previewUri()
        seekPreviewImg.visibility = if (uri != null) View.VISIBLE else View.GONE
        if (uri == null) seekPreviewImg.setImageDrawable(null)
        seekPreview.visibility = View.VISIBLE
        movePreview(position)
    }

    private fun movePreview(position: Long) {
        seekPreviewTime.text = formatTime(position)
        // Time bar meeda, thumb unna chota
        val bar = IntArray(2)
        val rootLoc = IntArray(2)
        timeBar.getLocationInWindow(bar)
        root.getLocationInWindow(rootLoc)
        val dur = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 1L
        val frac = (position.toFloat() / dur).coerceIn(0f, 1f)
        val w = if (seekPreview.width > 0) seekPreview.width else dp(168)
        val h = if (seekPreview.height > 0) seekPreview.height else dp(120)
        val x = bar[0] - rootLoc[0] + frac * timeBar.width - w / 2f
        seekPreview.translationX = x.coerceIn(dp(8).toFloat(), (root.width - w - dp(8)).toFloat().coerceAtLeast(0f))
        seekPreview.translationY = (bar[1] - rootLoc[1] - h - dp(10)).toFloat()
        if (seekPreviewImg.visibility == View.VISIBLE) requestThumb(position)
    }

    private fun requestThumb(position: Long) {
        thumbWanted = position
        if (thumbBusy || thumbExec.isShutdown) return
        val uri = previewUri() ?: return
        thumbBusy = true
        val ctx = applicationContext
        thumbExec.execute {
            var bmp: Bitmap? = null
            val pos = thumbWanted
            try {
                val r = retriever ?: MediaMetadataRetriever().also {
                    it.setDataSource(ctx, uri)
                    retriever = it
                }
                val us = pos * 1000
                bmp = if (Build.VERSION.SDK_INT >= 27) {
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                } else {
                    r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } catch (_: Exception) {
            }
            handler.post {
                thumbBusy = false
                if (isDestroyed) return@post
                if (bmp != null && seekPreview.visibility == View.VISIBLE) seekPreviewImg.setImageBitmap(bmp)
                // Kotha position adigithe malli teeyi
                if (scrubbing && thumbWanted != pos && thumbWanted >= 0) requestThumb(thumbWanted)
            }
        }
    }

    private fun stopPreview() {
        seekPreview.visibility = View.GONE
        thumbWanted = -1L
    }

    // ================================================================ panel actions

    private fun shareCurrent() {
        val item = player?.currentMediaItem ?: return
        val uri = Uri.parse(item.mediaId)
        val intent = if (uri.scheme == "content" || uri.scheme == "file") {
            Intent(Intent.ACTION_SEND).setType(if (playingAudio()) "audio/*" else "video/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri.toString())
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: Exception) {
        }
    }

    private fun toggleAudioOnly() {
        val p = player ?: return
        audioOnly = !audioOnly
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .build()
        showHud(getString(if (audioOnly) R.string.audio_only_on else R.string.audio_only_off), 1500)
    }

    private fun showInfo() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val uri = Uri.parse(item.mediaId)
        val rows = ArrayList<Pair<Int, String>>()
        rows += R.string.info_file to (item.mediaMetadata.title?.toString() ?: "")
        val size = item.mediaMetadata.extras?.getLong(KEY_SIZE) ?: 0L
        if (size > 0) rows += R.string.info_size to formatSize(size)
        if (p.duration > 0) rows += R.string.info_duration to formatTime(p.duration)
        p.videoFormat?.let { f ->
            if (f.width > 0) rows += R.string.info_resolution to "${f.width} × ${f.height}"
            val fps = if (f.frameRate > 0) String.format(Locale.US, " · %.0f fps", f.frameRate) else ""
            rows += R.string.info_video to "${f.sampleMimeType?.substringAfter('/') ?: "?"}$fps"
        }
        p.audioFormat?.let { f ->
            val ch = if (f.channelCount > 0) " · ${f.channelCount} ch" else ""
            val hz = if (f.sampleRate > 0) " · ${f.sampleRate / 1000.0} kHz" else ""
            rows += R.string.info_audio to "${f.sampleMimeType?.substringAfter('/') ?: "?"}$ch$hz"
        }
        rows += R.string.info_location to Uri.decode(uri.toString())
        val title = rows.firstOrNull()?.second ?: getString(R.string.info)
        val view = UiKit.infoView(this, title, rows.drop(1).map { (k, v) -> getString(k) to v })
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private class TrackChoice(val label: String, val group: Tracks.Group, val index: Int)

    private fun trackDialog(type: Int, titleRes: Int) {
        val p = player ?: return
        val list = ArrayList<TrackChoice>()
        var n = 0
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val lang = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
                val base = f.label ?: lang ?: getString(R.string.track_n, n)
                val extra = if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) " · ${f.channelCount} ch" else ""
                list += TrackChoice(base + extra, g, i)
            }
        }
        if (list.isEmpty()) {
            showHud(getString(R.string.no_tracks), 1500)
            return
        }
        val withOff = type == C.TRACK_TYPE_TEXT
        val labels = (if (withOff) listOf(getString(R.string.off)) else emptyList()) + list.map { it.label }
        val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        var checked = list.indexOfFirst { it.group.isTrackSelected(it.index) }
        if (withOff) checked = if (textOff || checked < 0) 0 else checked + 1
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                val params = p.trackSelectionParameters.buildUpon()
                if (withOff && which == 0) {
                    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val t = list[if (withOff) which - 1 else which]
                    params.setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                }
                p.trackSelectionParameters = params.build()
            }
            .show()
    }

    // ---- Trim (Edit): current video lo oka part ni kotha clip ga save
    private fun trimDialog() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val uri = Uri.parse(item.mediaId)
        if (uri.scheme != "content" && uri.scheme != "file") {
            showHud(getString(R.string.trim_unsupported), 2000)
            return
        }
        val dur = p.duration.takeIf { it > 0 } ?: return
        val start = p.currentPosition
        val end = min(dur, start + 30_000)
        val startField = EditText(this)
        startField.setText(formatTime(start))
        val endField = EditText(this)
        endField.setText(formatTime(end))
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val l1 = TextView(this)
        l1.text = getString(R.string.trim_start)
        l1.setTextColor(0xFFA3A3A3.toInt())
        val l2 = TextView(this)
        l2.text = getString(R.string.trim_end)
        l2.setTextColor(0xFFA3A3A3.toInt())
        box.addView(l1)
        box.addView(startField)
        box.addView(l2)
        box.addView(endField)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trim)
            .setView(box)
            .setPositiveButton(R.string.trim_save) { _, _ ->
                val a = parseTime(startField.text.toString())
                val b = parseTime(endField.text.toString())
                if (a == null || b == null || b <= a || b > dur + 1000) {
                    showHud(getString(R.string.trim_bad_time), 1800)
                } else {
                    exportClip(uri, item.mediaMetadata.title?.toString() ?: "clip", a, min(b, dur))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun parseTime(s: String): Long? {
        val parts = s.trim().split(':').map { it.trim().toLongOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3) return null
        var t = 0L
        parts.forEach { t = t * 60 + it }
        return t * 1000
    }

    private var transformer: Transformer? = null
    private val progressHolder = ProgressHolder()
    private val exportPoll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                showHud(getString(R.string.trim_saving, progressHolder.progress), 1200)
            }
            handler.postDelayed(this, 700)
        }
    }

    private fun exportClip(uri: Uri, title: String, startMs: Long, endMs: Long) {
        if (transformer != null) return
        val out = File(cacheDir, "flikk_clip_${System.currentTimeMillis()}.mp4")
        val clip = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()
        val t = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(exportPoll)
                    transformer = null
                    val name = title.substringBeforeLast('.') + "_clip_" + (startMs / 1000) + ".mp4"
                    if (saveToMovies(out, name)) showHud(getString(R.string.trim_saved), 2500)
                    else showHud(getString(R.string.trim_failed, ""), 2500)
                    out.delete()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacks(exportPoll)
                    transformer = null
                    out.delete()
                    showHud(getString(R.string.trim_failed, exportException.errorCodeName), 3000)
                }
            })
            .build()
        transformer = t
        showHud(getString(R.string.trim_saving, 0), 1200)
        t.start(EditedMediaItem.Builder(clip).build(), out.absolutePath)
        handler.postDelayed(exportPoll, 700)
    }

    /** Clip ni Movies/Flikk lo save chesi gallery lo kanipinchela chestham. */
    private fun saveToMovies(src: File, name: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Flikk")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            val dest = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(dest)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(dest, values, null, null)
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Flikk")
            dir.mkdirs()
            val f = File(dir, name)
            src.copyTo(f, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
        }
        true
    } catch (_: Exception) {
        false
    }

    // ================================================================ lock, sleep, background

    private fun lock() {
        locked = true
        backCallback.isEnabled = true
        handler.removeCallbacks(autoHide)
        controls.visibility = View.GONE
        showUnlock()
    }

    private fun unlock() {
        locked = false
        backCallback.isEnabled = panelOpen()
        handler.removeCallbacks(hideUnlock)
        unlockPill.visibility = View.GONE
        showControls()
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
                if (panelSettings.visibility == View.VISIBLE) buildSettings()
            }
            .show()
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
    }

    // ================================================================ equalizer / boost / delays

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

    private fun delayDialog(
        title: Int,
        hint: Int,
        stepMs: Long,
        current: () -> Long,
        onChange: (Long) -> Unit,
    ) {
        val pad = dp(20)
        val value = TextView(this)
        value.textSize = 30f
        value.setTypeface(value.typeface, Typeface.BOLD)
        value.gravity = Gravity.CENTER
        value.setTextColor(0xFFFFFFFF.toInt())
        val refresh = { value.text = String.format(Locale.US, "%+.2f s", current() / 1000.0) }
        fun btn(label: String, delta: Long): MaterialButton {
            val b = MaterialButton(this)
            b.text = label
            b.setOnClickListener {
                onChange(if (delta == 0L) 0L else current() + delta)
                refresh()
            }
            return b
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        listOf(btn("−", -stepMs), btn(getString(R.string.reset), 0L), btn("+", stepMs)).forEach {
            row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = pad / 4
                marginEnd = pad / 4
            })
        }
        val hintView = TextView(this)
        hintView.text = getString(hint)
        hintView.gravity = Gravity.CENTER
        hintView.setTextColor(0xFFA3A3A3.toInt())
        hintView.textSize = 13f
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(value)
        box.addView(row)
        box.addView(hintView)
        refresh()
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

    // ================================================================ subtitles

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
                    String.format(Locale.US, "%d:%02d:%02d.%02d",
                        t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
                }
            } else {
                Regex("(\\d{1,2}):(\\d{2}):(\\d{2})([,.])(\\d{3})").replace(text) { m ->
                    val (h, mi, se, sep, mil) = m.destructured
                    val t = ((h.toLong() * 3600 + mi.toLong() * 60 + se.toLong()) * 1000 + mil.toLong() + ms)
                        .coerceAtLeast(0L)
                    String.format(Locale.US, "%02d:%02d:%02d%s%03d",
                        t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
                }
            }
            val ext = subName.substringAfterLast('.', "srt")
            cacheDir.listFiles { f -> f.name.startsWith("sub_shift_") }?.forEach { it.delete() }
            val out = File(cacheDir, "sub_shift_${System.currentTimeMillis()}.$ext")
            out.writeText(shifted, Charsets.UTF_8)
            applySubtitle(Uri.fromFile(out), subName)
        } catch (_: Exception) {
        }
    }

    // ================================================================ view modes

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
        playerView.videoSurfaceView?.let {
            it.scaleX = zoom
            it.scaleY = zoom
        }
    }

    private fun resetZoom() {
        zoom = 1f
        applyZoom()
    }

    private fun playingAudio() =
        player?.currentMediaItem?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC

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

    // ================================================================ picture-in-picture

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
        // Background play / audio unte vinipistune untundi; ledante PiP window
        if (!bgPlay && !audioOnly && !playingAudio() && !locked && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            closePanels()
            controls.visibility = View.GONE
            hud.visibility = View.GONE
            unlockPill.visibility = View.GONE
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // PiP window close chesaru
            finishAndRemoveTask()
        } else {
            showControls()
        }
    }

    // ================================================================ gestures

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
                toggleControls()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val p = player ?: return
                if (mode != Mode.NONE || zooming || !p.isPlaying || castSession != null) return
                holdSpeed = p.playbackParameters.speed
                p.playbackParameters = PlaybackParameters(2f)
                fastHold = true
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showHud("2x  ▶▶", 60_000)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return true
                val w = root.width
                when {
                    e.x < w * 0.35f -> {
                        p.seekBack()
                        showHud(getString(R.string.seek_back))
                    }
                    e.x > w * 0.65f -> {
                        p.seekForward()
                        showHud(getString(R.string.seek_forward))
                    }
                    else -> togglePlay()
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
                val w = root.width.toFloat()
                val h = root.height.toFloat()
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
                return !is360
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(0.5f, 4f)
                if (abs(zoom - 1f) < 0.04f) zoom = 1f
                applyZoom()
                showHud(getString(R.string.zoom_level, (zoom * 100).roundToInt()))
                return true
            }
        })

        val touch = View.OnTouchListener { _, ev ->
            if (locked) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) showUnlock()
                return@OnTouchListener true
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
                if (fastHold) endFastHold()
                if (mode == Mode.SEEK) player?.seekTo(seekTarget)
                mode = Mode.NONE
                zooming = false
            }
            true
        }
        // Controls overlay khaali chotu + video rendu meedha gestures
        controls.setOnTouchListener(touch)
        playerView.setOnTouchListener(touch)
    }

    private fun endFastHold() {
        fastHold = false
        player?.playbackParameters = PlaybackParameters(holdSpeed)
        handler.removeCallbacks(hideHud)
        hud.visibility = View.GONE
    }

    // ================================================================ cast to TV

    private fun castClicked() {
        val ctx = castCtx ?: return
        val session = ctx.sessionManager.currentCastSession
        try {
            CastSheet(this, ctx,
                onPrev = { castSkip(-1) },
                onNext = { castSkip(1) },
            ).show()
        } catch (_: Exception) {
            showHud(getString(R.string.fp_cast_failed), 2000)
        }
    }

    /** TV lo next / previous video: local playlist lo maari, TV ki kotha video pampu. */
    private fun castSkip(dir: Int) {
        val p = player ?: return
        val s = castSession ?: return
        if (dir > 0 && p.hasNextMediaItem()) p.seekToNextMediaItem()
        else if (dir < 0 && p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
        else return
        p.pause()
        startCasting(s)
    }

    private fun mimeOf(uri: Uri): String {
        val t = try { contentResolver.getType(uri) } catch (_: Exception) { null }
        if (!t.isNullOrBlank() && t != "application/octet-stream") return t
        val s = uri.toString().lowercase()
        return when {
            ".m3u8" in s -> "application/x-mpegURL"
            ".mpd" in s -> "application/dash+xml"
            s.endsWith(".mkv") -> "video/x-matroska"
            s.endsWith(".webm") -> "video/webm"
            s.endsWith(".mp3") -> "audio/mpeg"
            else -> "video/mp4"
        }
    }

    private fun startCasting(session: CastSession) {
        castSession = session
        btnCast.setImageResource(R.drawable.ic_cast_connected)
        val p = player
        val item = p?.currentMediaItem ?: PlayerHolder.peek()?.currentMediaItem ?: return
        val remote = session.remoteMediaClient ?: return
        // TV lo ippatike ide play avuthunte malli load cheyyoddu
        if (remote.hasMediaSession() && remote.mediaInfo?.customData?.optString("id") == item.mediaId) {
            p?.pause()
            return
        }
        val uri = Uri.parse(item.mediaId)
        val mime = mimeOf(uri)
        val url = when (uri.scheme) {
            "http", "https" -> uri.toString()
            "content", "file" -> LocalServer.serve(this, uri, mime)
            else -> {
                showHud(getString(R.string.fp_cast_unsupported), 2500)
                return
            }
        }
        if (url == null) {
            showHud(getString(R.string.fp_cast_no_wifi), 2500)
            return
        }
        val meta = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE)
        meta.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, item.mediaMetadata.title?.toString() ?: "Flikk Play")
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(meta)
            .setCustomData(org.json.JSONObject().put("id", item.mediaId))
            .build()
        val pos = p?.currentPosition ?: 0L
        remote.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime(pos)
                .build()
        )
        p?.pause()
        val name = session.castDevice?.friendlyName ?: "TV"
        showHud(getString(R.string.fp_casting_to, name), 2500)
        findViewById<View>(R.id.cast_overlay).visibility = View.VISIBLE
        findViewById<TextView>(R.id.cast_label).text = getString(R.string.fp_casting_to, name)
        com.bumptech.glide.Glide.with(this).load(uri).centerCrop().into(findViewById<ImageView>(R.id.cast_bg))
        showControls()
    }

    private fun stopCasting() {
        val last = castSession?.remoteMediaClient?.approximateStreamPosition ?: -1L
        castSession = null
        LocalServer.stop()
        btnCast.setImageResource(R.drawable.ic_cast)
        findViewById<View>(R.id.cast_overlay).visibility = View.GONE
        if (last > 0) player?.seekTo(last)
        showHud(getString(R.string.fp_cast_stopped), 1500)
        updatePlayIcon()
        showControls()
    }

    private fun showHud(text: CharSequence, ms: Long = 700) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }
}
