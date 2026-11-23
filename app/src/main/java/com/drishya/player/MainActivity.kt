package com.drishya.player

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bigTitle: TextView
    private lateinit var subTitle: TextView
    private lateinit var tabs: TabLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var allowBtn: Button
    private lateinit var miniBar: LinearLayout
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView

    private var allVideos: List<Video> = emptyList()
    private var allTracks: List<Track> = emptyList()
    private var openServer: SmbServer? = null
    private var smbPath = ""
    private var smbEntries: List<SmbEntry>? = null
    private var smbToken = 0
    private val netIo = Executors.newSingleThreadExecutor()
    private var loaded = false
    private var tab = 0
    private var openFolder: String? = null
    private var openPlaylist: String? = null
    private var query = ""
    private val io = Executors.newSingleThreadExecutor()
    private val resumePrefs by lazy { getSharedPreferences(Prefs.RESUME, MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }

    // ---- delete (Android 10 kinda permission, 11+ system confirm dialog)
    private var pendingDelete: Video? = null
    private var retryDeleteAfterGrant = false

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val v = pendingDelete
            if (res.resultCode == RESULT_OK && v != null) {
                if (retryDeleteAfterGrant) deleteNow(v) else onDeleted(v)
            }
            retryDeleteAfterGrant = false
        }

    private val writePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val v = pendingDelete
            if (ok && v != null) deleteNow(v)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        bigTitle = findViewById(R.id.big_title)
        subTitle = findViewById(R.id.sub_title)
        tabs = findViewById(R.id.tabs)
        recycler = findViewById(R.id.recycler)
        emptyBox = findViewById(R.id.empty_box)
        emptyText = findViewById(R.id.empty_text)
        allowBtn = findViewById(R.id.allow_btn)
        miniBar = findViewById(R.id.mini_bar)
        miniThumb = findViewById(R.id.mini_thumb)
        miniTitle = findViewById(R.id.mini_title)
        miniSub = findViewById(R.id.mini_sub)
        miniThumb.clipToOutline = true

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { goBack() }
        recycler.layoutManager = LinearLayoutManager(this)
        allowBtn.setOnClickListener { requestPermission() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        listOf(
            R.string.tab_all, R.string.tab_folders, R.string.tab_playlists,
            R.string.tab_recent, R.string.tab_music, R.string.tab_network,
        )
            .forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                render()
            }

            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        if (!hasPermission()) requestPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------ permission + data

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 ->
            granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_VIDEO)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_AUDIO)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun requestPermission() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permLauncher.launch(perms)
    }

    private fun refresh() {
        if (!hasPermission()) {
            if (openServer != null || tab == 5) {
                render()
                return
            }
            showEmpty(getString(R.string.need_permission), showButton = true)
            miniBar.visibility = View.GONE
            return
        }
        io.execute {
            val list = try { VideoRepository.load(this) } catch (e: Exception) { emptyList() }
            val tracks = if (hasAudioPermission()) {
                try { AudioRepository.load(this) } catch (e: Exception) { emptyList() }
            } else emptyList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allVideos = list
                allTracks = tracks
                loaded = true
                render()
            }
        }
    }

    // ------------------------------------------------------------ rendering

    private fun goBack() {
        val server = openServer
        if (server != null) {
            if (smbPath.isNotEmpty()) openSmb(server, smbPath.substringBeforeLast('/', ""))
            else {
                openServer = null
                smbEntries = null
                render()
            }
            return
        }
        openFolder = null
        openPlaylist = null
        render()
    }

    private fun showEmpty(text: String, showButton: Boolean) {
        recycler.visibility = View.GONE
        emptyBox.visibility = View.VISIBLE
        emptyText.text = text
        allowBtn.visibility = if (showButton) View.VISIBLE else View.GONE
    }

    private fun hideEmpty() {
        emptyBox.visibility = View.GONE
        recycler.visibility = View.VISIBLE
    }

    private fun playlistLabel(key: String) =
        if (key == Library.FAVOURITES) getString(R.string.favourites) else key

    private fun render() {
        val server = openServer
        val inside = openFolder != null || openPlaylist != null || server != null
        backCallback.isEnabled = inside
        toolbar.navigationIcon =
            if (inside) ContextCompat.getDrawable(this, R.drawable.ic_back) else null
        tabs.visibility = if (inside) View.GONE else View.VISIBLE
        bigTitle.text = when {
            server != null -> if (smbPath.isEmpty()) server.share else smbPath.substringAfterLast('/')
            openFolder != null -> openFolder
            openPlaylist != null -> playlistLabel(openPlaylist!!)
            tab == 4 -> getString(R.string.tab_music)
            tab == 5 -> getString(R.string.tab_network)
            else -> getString(R.string.title_videos)
        }
        if (server != null || (tab == 5 && !inside)) {
            miniBar.visibility = View.GONE
            renderNetwork()
            return
        }
        if (!loaded) return
        updateMiniBar(inside)
        if (tab == 4 && !inside) {
            renderMusic(query.trim().lowercase())
            return
        }

        if (allVideos.isEmpty()) {
            subTitle.text = ""
            showEmpty(getString(R.string.no_videos), showButton = false)
            return
        }

        val q = query.trim().lowercase()
        val folder = openFolder
        val playlist = openPlaylist
        when {
            folder != null -> showVideos(allVideos.filter { it.folder == folder }, q)
            playlist != null -> {
                val vids = Library.videosOf(this, playlist, allVideos)
                if (vids.isEmpty()) {
                    subTitle.text = ""
                    showEmpty(getString(R.string.empty_playlist), false)
                } else {
                    showVideos(vids, q)
                }
            }
            q.isNotEmpty() -> showVideos(allVideos, q)
            tab == 1 -> showFolders()
            tab == 2 -> showPlaylists()
            tab == 3 -> {
                val r = Library.recent(this, allVideos)
                if (r.isEmpty()) {
                    subTitle.text = ""
                    showEmpty(getString(R.string.no_recent), false)
                } else {
                    showVideos(r, q)
                }
            }
            else -> showVideos(allVideos, q)
        }
    }

    private fun showVideos(source: List<Video>, q: String) {
        val list = if (q.isEmpty()) source else source.filter { it.name.lowercase().contains(q) }
        hideEmpty()
        subTitle.text = getString(R.string.count_videos, list.size)
        recycler.adapter = VideoCardAdapter(
            list, resumePrefs,
            onClick = { i -> openPlayer(list, i) },
            onLongClick = { i -> videoMenu(list[i]) },
        )
    }

    private fun showFolders() {
        hideEmpty()
        val groups = allVideos.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
        subTitle.text = getString(R.string.count_summary, allVideos.size, groups.size)
        recycler.adapter = RowAdapter(groups.map { (name, vids) ->
            Row(name, getString(R.string.count_videos, vids.size), R.drawable.ic_folder, onClick = {
                openFolder = name
                render()
            })
        })
    }

    private fun showPlaylists() {
        hideEmpty()
        val lists = Library.playlists(this)
        val available = allVideos.map { it.uri.toString() }.toHashSet()
        subTitle.text = ""
        val rows = lists.map { pl ->
            val count = pl.uris.count { it in available }
            Row(
                name = playlistLabel(pl.key),
                sub = getString(R.string.count_videos, count),
                icon = if (pl.isFavourites) R.drawable.ic_star else R.drawable.ic_playlist,
                onClick = {
                    openPlaylist = pl.key
                    render()
                },
                onLongClick = if (pl.isFavourites) null else ({ confirmDelete(pl.key) }),
            )
        } + Row(getString(R.string.new_playlist), "", R.drawable.ic_add, onClick = {
            askPlaylistName { name -> if (Library.create(this, name)) render() }
        })
        recycler.adapter = RowAdapter(rows)
    }

    // ------------------------------------------------------------ music

    private fun renderMusic(q: String) {
        if (!hasAudioPermission()) {
            subTitle.text = ""
            showEmpty(getString(R.string.need_audio_permission), showButton = true)
            return
        }
        val list = if (q.isEmpty()) allTracks
        else allTracks.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        if (list.isEmpty()) {
            subTitle.text = ""
            showEmpty(getString(R.string.no_music), showButton = false)
            return
        }
        hideEmpty()
        subTitle.text = getString(R.string.count_songs, list.size)
        recycler.adapter = RowAdapter(list.mapIndexed { i, t ->
            val artist = t.artist.ifBlank { getString(R.string.unknown_artist) }
            Row(t.title, "$artist · ${formatTime(t.durationMs)}", R.drawable.ic_music, onClick = {
                launchPlayer(
                    list.map { it.uri.toString() }, list.map { it.title }, i,
                    audio = true, artists = list.map { it.artist },
                )
            })
        })
    }

    // ------------------------------------------------------------ network (PC / NAS)

    private fun renderNetwork() {
        val server = openServer
        if (server == null) {
            hideEmpty()
            subTitle.text = getString(R.string.add_server_sub)
            val rows = SmbStore.list(this).map { s ->
                Row(
                    s.label, s.user.ifBlank { "guest" }, R.drawable.ic_computer,
                    onClick = { openSmb(s, "") },
                    onLongClick = { confirmRemoveServer(s) },
                )
            } + Row(getString(R.string.add_server), "", R.drawable.ic_add, onClick = { addServerDialog() })
            recycler.adapter = RowAdapter(rows)
            return
        }
        subTitle.text = server.label
        val entries = smbEntries
        if (entries == null) {
            showEmpty(getString(R.string.connecting), showButton = false)
            return
        }
        if (entries.isEmpty()) {
            showEmpty(getString(R.string.folder_empty), showButton = false)
            return
        }
        hideEmpty()
        subTitle.text = "${server.label} · ${getString(R.string.items_count, entries.size)}"
        val media = entries.filter { !it.isDir }
        recycler.adapter = RowAdapter(entries.map { e ->
            if (e.isDir) {
                Row(e.name, getString(R.string.folder), R.drawable.ic_folder, onClick = {
                    openSmb(server, if (smbPath.isEmpty()) e.name else "$smbPath/${e.name}")
                })
            } else {
                Row(
                    e.name, formatSize(e.size),
                    if (isAudioName(e.name)) R.drawable.ic_music else R.drawable.ic_play,
                    onClick = {
                        launchPlayer(
                            media.map { Smb.uriFor(server, smbPath, it.name).toString() },
                            media.map { it.name },
                            media.indexOf(e),
                            audio = isAudioName(e.name),
                        )
                    },
                )
            }
        })
    }

    private fun openSmb(server: SmbServer, path: String, saveOnSuccess: Boolean = false) {
        openServer = server
        smbPath = path
        smbEntries = null
        render()
        val token = ++smbToken
        netIo.execute {
            try {
                val list = Smb.list(server, path)
                runOnUiThread {
                    if (token != smbToken || isDestroyed) return@runOnUiThread
                    if (saveOnSuccess) SmbStore.save(this, server)
                    smbEntries = list
                    render()
                }
            } catch (e: Exception) {
                Smb.forget(server)
                runOnUiThread {
                    if (token != smbToken || isDestroyed) return@runOnUiThread
                    toast(getString(R.string.connect_failed, e.message ?: e.javaClass.simpleName))
                    openServer = null
                    smbPath = ""
                    smbEntries = null
                    render()
                }
            }
        }
    }

    private fun addServerDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun field(hintRes: Int, password: Boolean = false) = EditText(this).apply {
            hint = getString(hintRes)
            isSingleLine = true
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val help = TextView(this).apply {
            text = getString(R.string.server_help)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
        }
        val host = field(R.string.server_host)
        val share = field(R.string.server_share)
        val user = field(R.string.server_user)
        val pass = field(R.string.server_pass, password = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(help, host, share, user, pass).forEach { addView(it) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_server)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.connect) { _, _ ->
                // "\\192.168.1.5\\Movies" / "smb://192.168.1.5/Movies" kuda ardham chesukuntam
                var h = host.text.toString().trim().removePrefix("smb://").trim('\\', '/')
                var sh = share.text.toString().trim().trim('\\', '/')
                val parts = h.split('/', '\\').filter { it.isNotEmpty() }
                if (parts.size > 1) {
                    h = parts[0]
                    if (sh.isEmpty()) sh = parts[1]
                }
                if (h.isEmpty() || sh.isEmpty()) return@setPositiveButton
                val server = SmbServer(h, sh, user.text.toString().trim(), pass.text.toString())
                openSmb(server, "", saveOnSuccess = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveServer(s: SmbServer) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.remove_server, s.label))
            .setPositiveButton(R.string.remove) { _, _ ->
                SmbStore.delete(this, s.id)
                Smb.forget(s)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateMiniBar(inside: Boolean) {
        val last = if (inside) null else Library.recent(this, allVideos)
            .firstOrNull { resumePrefs.getLong(it.uri.toString(), 0L) > 5000 }
        if (last == null) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = last.name
        miniSub.text = getString(
            R.string.paused_at,
            formatTime(resumePrefs.getLong(last.uri.toString(), 0L))
        )
        Glide.with(miniThumb).load(last.uri).centerCrop().into(miniThumb)
        val open = View.OnClickListener { openPlayer(listOf(last), 0) }
        miniBar.setOnClickListener(open)
        findViewById<ImageButton>(R.id.mini_play).setOnClickListener(open)
    }

    // ------------------------------------------------------------ playlists UI

    private fun videoMenu(v: Video) {
        val uri = v.uri.toString()
        val fav = Library.isFavourite(this, uri)
        val inPlaylist = openPlaylist?.takeIf { it != Library.FAVOURITES }
        val options = mutableListOf(
            getString(if (fav) R.string.remove_favourite else R.string.add_favourite),
            getString(R.string.add_to_playlist),
        )
        if (inPlaylist != null) options += getString(R.string.remove_from_playlist)
        options += getString(R.string.delete_video)
        val deleteIndex = options.size - 1
        MaterialAlertDialogBuilder(this)
            .setTitle(v.name)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == deleteIndex -> askDelete(v)
                    which == 0 -> {
                        Library.toggleFavourite(this, uri)
                        if (!fav) toast(getString(R.string.added_to, getString(R.string.favourites)))
                        render()
                    }
                    which == 1 -> choosePlaylist(uri)
                    which == 2 && inPlaylist != null -> {
                        Library.remove(this, inPlaylist, uri)
                        render()
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------ delete

    private fun askDelete(v: Video) {
        pendingDelete = v
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: system ye "Allow Flikk to delete?" ani adugutundi
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(v.uri))
                retryDeleteAfterGrant = false
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (_: Exception) {
                toast(getString(R.string.delete_failed))
            }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, v.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (Build.VERSION.SDK_INT <= 28 &&
                    !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                ) {
                    writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    deleteNow(v)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteNow(v: Video) {
        try {
            val rows = contentResolver.delete(v.uri, null, null)
            if (rows > 0) onDeleted(v) else toast(getString(R.string.delete_failed))
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                retryDeleteAfterGrant = true
                deleteLauncher.launch(
                    IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                )
            } else {
                toast(getString(R.string.delete_failed))
            }
        } catch (_: Exception) {
            toast(getString(R.string.delete_failed))
        }
    }

    private fun onDeleted(v: Video) {
        pendingDelete = null
        Library.forget(this, v.uri.toString())
        allVideos = allVideos.filter { it.uri != v.uri }
        toast(getString(R.string.deleted))
        render()
    }

    // ------------------------------------------------------------ network stream (URL)

    private fun openUrlDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_url)
            .setView(box)
            .setPositiveButton(R.string.play) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    startActivity(Intent(this, PlayerActivity::class.java).setData(Uri.parse(url)))
                } else {
                    toast(getString(R.string.bad_url))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun choosePlaylist(uri: String) {
        val names = Library.userPlaylistNames(this)
        val items = (names + getString(R.string.new_playlist)).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(items) { _, which ->
                if (which < names.size) {
                    Library.add(this, names[which], uri)
                    toast(getString(R.string.added_to, names[which]))
                } else {
                    askPlaylistName { name ->
                        if (Library.create(this, name)) {
                            Library.add(this, name.trim(), uri)
                            toast(getString(R.string.added_to, name.trim()))
                            render()
                        }
                    }
                }
            }
            .show()
    }

    private fun askPlaylistName(onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.playlist_name)
            isSingleLine = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_playlist)
            .setView(box)
            .setPositiveButton(R.string.create) { _, _ -> onDone(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(key: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_playlist, key))
            .setPositiveButton(R.string.delete) { _, _ ->
                Library.delete(this, key)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ language

    private fun chooseLanguage() {
        val tags = arrayOf("", "en", "te")
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_telugu),
        )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checked = tags.indexOfFirst { it.isNotEmpty() && current.startsWith(it) }.let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[which]))
            }
            .show()
    }

    // ------------------------------------------------------------ player

    private fun openPlayer(list: List<Video>, index: Int) =
        launchPlayer(list.map { it.uri.toString() }, list.map { it.name }, index, audio = false)

    private fun launchPlayer(
        uris: List<String>,
        titles: List<String>,
        index: Int,
        audio: Boolean,
        artists: List<String>? = null,
    ) {
        if (index !in uris.indices) return
        // Intent size limit dhaatakunda, chuttupakkala items matrame playlist lo pampistham
        val from = (index - 100).coerceAtLeast(0)
        val to = (index + 200).coerceAtMost(uris.size)
        val intent = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(uris.subList(from, to)))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(titles.subList(from, to)))
            .putExtra(PlayerActivity.EXTRA_INDEX, index - from)
            .putExtra(PlayerActivity.EXTRA_AUDIO, audio)
        if (artists != null) {
            intent.putStringArrayListExtra(PlayerActivity.EXTRA_ARTISTS, ArrayList(artists.subList(from, to)))
        }
        startActivity(intent)
    }

    // ------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        val sv = menu.findItem(R.id.action_search).actionView as SearchView
        sv.queryHint = getString(R.string.search_hint)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean = true
            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty()
                render()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> refresh()
            R.id.action_language -> chooseLanguage()
            R.id.action_open_url -> openUrlDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        netIo.shutdown()
    }
}
