package com.drishya.player

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Section { LOCAL, MUSIC, NETWORK, ME }

    private lateinit var header: View
    private lateinit var selHeader: View
    private lateinit var selCount: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnSelect: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var searchBox: EditText
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
    private lateinit var selectBar: View
    private lateinit var bottomNav: BottomNavigationView

    private var rawVideos: List<Video> = emptyList()   // anni (private tho kalipi)
    private var allVideos: List<Video> = emptyList()   // private kakunda
    private var allTracks: List<Track> = emptyList()
    private var loaded = false
    private var section = Section.LOCAL
    private var tab = 0
    private var openFolder: String? = null
    private var openPlaylist: String? = null
    private var query = ""

    // music sub-tabs: 0 Songs, 1 Albums, 2 Artists
    private var musicTab = 0
    private var openAlbum: String? = null
    private var openArtist: String? = null
    private var tabsMusic: Boolean? = null
    private var tabsRebuilding = false

    // private folder
    private var privateOpen = false
    private var launching = false

    // selection mode
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var shownVideos: List<Video> = emptyList()

    // network
    private var openServer: SmbServer? = null
    private var smbPath = ""
    private var smbEntries: List<SmbEntry>? = null
    private var smbToken = 0

    private val io = Executors.newSingleThreadExecutor()
    private val netIo = Executors.newSingleThreadExecutor()
    private val resumePrefs by lazy { getSharedPreferences(Prefs.RESUME, MODE_PRIVATE) }
    private val settings by lazy { getSharedPreferences(Prefs.SETTINGS, MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goBack()
    }

    // ---- delete (Android 10 kinda permission, 11+ system confirm dialog)
    private var pendingDelete: List<Video> = emptyList()
    private var retryDeleteAfterGrant = false

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val list = pendingDelete
            if (res.resultCode == RESULT_OK && list.isNotEmpty()) {
                if (retryDeleteAfterGrant) deleteNow(list) else onDeleted(list)
            }
            retryDeleteAfterGrant = false
        }

    private val writePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok && pendingDelete.isNotEmpty()) deleteNow(pendingDelete)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColors.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        UiKit.fitSystemBars(findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))
        header = findViewById(R.id.header)
        selHeader = findViewById(R.id.sel_header)
        selCount = findViewById(R.id.sel_count)
        btnBack = findViewById(R.id.btn_back)
        btnSearch = findViewById(R.id.btn_search)
        btnSelect = findViewById(R.id.btn_select)
        btnMenu = findViewById(R.id.btn_menu)
        searchBox = findViewById(R.id.search_box)
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
        selectBar = findViewById(R.id.select_bar)
        bottomNav = findViewById(R.id.bottom_nav)
        miniThumb.clipToOutline = true

        recycler.layoutManager = LinearLayoutManager(this)
        allowBtn.setOnClickListener { requestPermission() }
        onBackPressedDispatcher.addCallback(this, backCallback)
        btnBack.setOnClickListener { goBack() }
        btnMenu.setOnClickListener { if (section == Section.MUSIC) showMusicMenu(it) else showHomeMenu(it) }
        btnSelect.setOnClickListener { startSelecting(null) }
        btnSearch.setOnClickListener { toggleSearch() }
        findViewById<View>(R.id.sel_cancel).setOnClickListener { stopSelecting() }
        findViewById<View>(R.id.sel_all).setOnClickListener { selectAll() }
        findViewById<View>(R.id.sel_share).setOnClickListener { shareSelected() }
        findViewById<View>(R.id.sel_add).setOnClickListener { addSelectedToPlaylist() }
        findViewById<View>(R.id.sel_delete).setOnClickListener { askDelete(selectedVideos()) }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })

        setupTabs(music = false)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                if (tabsRebuilding) return
                if (tabsMusic == true) {
                    musicTab = t.position
                    openAlbum = null
                    openArtist = null
                } else {
                    tab = t.position
                }
                stopSelecting(render = false)
                render()
            }

            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        bottomNav.isItemActiveIndicatorEnabled = false
        bottomNav.setOnItemSelectedListener { item ->
            section = when (item.itemId) {
                R.id.nav_music -> Section.MUSIC
                R.id.nav_network -> Section.NETWORK
                R.id.nav_me -> Section.ME
                else -> Section.LOCAL
            }
            openFolder = null
            openPlaylist = null
            openServer = null
            smbEntries = null
            openAlbum = null
            openArtist = null
            privateOpen = false
            stopSelecting(render = false)
            render()
            true
        }

        if (!hasPermission()) requestPermission()
    }

    override fun onResume() {
        super.onResume()
        launching = false
        refresh()
    }

    override fun onStop() {
        super.onStop()
        // App nunchi bayataki vellithe private folder malli lock
        if (!launching && privateOpen) {
            privateOpen = false
            stopSelecting(render = false)
        }
    }

    /** Videos tabs leda Music tabs — okate TabLayout. */
    private fun setupTabs(music: Boolean) {
        if (tabsMusic == music) return
        tabsMusic = music
        tabsRebuilding = true
        tabs.removeAllTabs()
        val labels = if (music) listOf(R.string.tab_songs, R.string.tab_albums, R.string.tab_artists)
        else listOf(R.string.title_videos, R.string.tab_folders, R.string.tab_playlists, R.string.tab_recent)
        labels.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        val sel = if (music) musicTab else tab
        tabs.getTabAt(sel)?.select()
        tabsRebuilding = false
    }

    private fun applyPrivacy() {
        val hidden = Privacy.all(this)
        allVideos = if (hidden.isEmpty()) rawVideos else rawVideos.filter { it.uri.toString() !in hidden }
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
            render()
            return
        }
        io.execute {
            val list = try { VideoRepository.load(this) } catch (e: Exception) { emptyList() }
            val tracks = if (hasAudioPermission()) {
                try { AudioRepository.load(this) } catch (e: Exception) { emptyList() }
            } else emptyList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rawVideos = list
                applyPrivacy()
                allTracks = tracks
                loaded = true
                render()
            }
        }
    }

    // ------------------------------------------------------------ header / navigation

    private fun goBack() {
        if (selecting) {
            stopSelecting()
            return
        }
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
        if (searchBox.visibility == View.VISIBLE && openFolder == null && openPlaylist == null &&
            openAlbum == null && openArtist == null
        ) {
            toggleSearch()
            return
        }
        openFolder = null
        openPlaylist = null
        openAlbum = null
        openArtist = null
        privateOpen = false
        render()
    }

    private fun toggleSearch() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (searchBox.visibility == View.VISIBLE) {
            searchBox.setText("")
            searchBox.visibility = View.GONE
            imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        } else {
            searchBox.visibility = View.VISIBLE
            searchBox.requestFocus()
            imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
        }
        render()
    }

    private fun showHomeMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.home_more, menu)
            val sortId = when (settings.getString("sort", "date")) {
                "name" -> R.id.m_sort_name
                "length" -> R.id.m_sort_length
                "size" -> R.id.m_sort_size
                else -> R.id.m_sort_date
            }
            menu.findItem(sortId)?.isChecked = true
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.m_scan -> scanForVideos()
                    R.id.m_sort_date -> setSort("date")
                    R.id.m_sort_name -> setSort("name")
                    R.id.m_sort_length -> setSort("length")
                    R.id.m_sort_size -> setSort("size")
                    R.id.m_open_url -> openUrlDialog()
                }
                true
            }
            show()
        }
    }

    private fun setSort(key: String) {
        settings.edit().putString("sort", key).apply()
        render()
    }

    private fun sorted(list: List<Video>): List<Video> = when (settings.getString("sort", "date")) {
        "name" -> list.sortedBy { it.name.lowercase() }
        "length" -> list.sortedByDescending { it.durationMs }
        "size" -> list.sortedByDescending { it.sizeBytes }
        else -> list.sortedByDescending { it.dateAdded }
    }

    private fun scanForVideos() {
        // MediaStore ki kotha files chupinchamani adigi, malli load chestham
        try {
            val dirs = listOf(
                android.os.Environment.DIRECTORY_MOVIES,
                android.os.Environment.DIRECTORY_DOWNLOADS,
                android.os.Environment.DIRECTORY_DCIM,
            ).map { android.os.Environment.getExternalStoragePublicDirectory(it).absolutePath }
            android.media.MediaScannerConnection.scanFile(this, dirs.toTypedArray(), null) { _, _ -> }
        } catch (_: Exception) {
        }
        toast(getString(R.string.scan_videos) + "…")
        refresh()
    }

    // ------------------------------------------------------------ rendering

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
        val inside = openFolder != null || openPlaylist != null || server != null ||
            openAlbum != null || openArtist != null || privateOpen
        backCallback.isEnabled = inside || selecting || searchBox.visibility == View.VISIBLE

        header.visibility = if (selecting) View.GONE else View.VISIBLE
        subTitle.visibility = View.VISIBLE
        selHeader.visibility = if (selecting) View.VISIBLE else View.GONE
        selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        btnBack.visibility = if (inside) View.VISIBLE else View.GONE
        val local = section == Section.LOCAL
        btnSelect.visibility = if ((local && (tab == 0 || tab == 3 || inside)) || privateOpen) View.VISIBLE else View.GONE
        btnMenu.visibility = if (local || section == Section.MUSIC) View.VISIBLE else View.GONE
        btnSearch.visibility = if (section == Section.LOCAL || section == Section.MUSIC) View.VISIBLE else View.GONE
        val music = section == Section.MUSIC
        if (local || music) setupTabs(music)
        tabs.visibility = if ((local || music) && !inside && !selecting) View.VISIBLE else View.GONE
        if (selecting) selCount.text = getString(R.string.selected_count, selected.size)

        bigTitle.text = when {
            server != null -> if (smbPath.isEmpty()) server.share else smbPath.substringAfterLast('/')
            openFolder != null -> openFolder
            openPlaylist != null -> playlistLabel(openPlaylist!!)
            openAlbum != null -> openAlbum
            openArtist != null -> openArtist
            privateOpen -> getString(R.string.private_folder)
            section == Section.MUSIC -> getString(R.string.tab_music)
            section == Section.NETWORK -> getString(R.string.tab_network)
            section == Section.ME -> getString(R.string.nav_me)
            else -> getString(R.string.nav_local)
        }

        when (section) {
            Section.NETWORK -> {
                miniBar.visibility = View.GONE
                renderNetwork()
                return
            }
            Section.ME -> {
                miniBar.visibility = View.GONE
                if (privateOpen) renderPrivate() else renderMe()
                return
            }
            else -> Unit
        }

        if (!hasPermission()) {
            subTitle.text = ""
            miniBar.visibility = View.GONE
            showEmpty(getString(R.string.need_permission), showButton = true)
            return
        }
        if (!loaded) return

        if (section == Section.MUSIC) {
            updateMusicBar()
            renderMusic(query.trim().lowercase())
            return
        }

        val showContinue = !inside && !selecting && tab == 0 && query.isBlank() &&
            searchBox.visibility != View.VISIBLE
        updateMiniBar(inside || selecting || showContinue)
        if (allVideos.isEmpty()) {
            subTitle.text = ""
            showEmpty(getString(R.string.no_videos), showButton = false)
            return
        }

        val q = query.trim().lowercase()
        val folder = openFolder
        val playlist = openPlaylist
        when {
            folder != null -> showVideos(sorted(allVideos.filter { it.folder == folder }), q)
            playlist != null -> {
                val vids = Library.videosOf(this, playlist, allVideos)
                if (vids.isEmpty()) {
                    subTitle.text = ""
                    showEmpty(getString(R.string.empty_playlist), false)
                } else {
                    showVideos(vids, q)
                }
            }
            q.isNotEmpty() -> showVideos(sorted(allVideos), q)
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
            else -> if (showContinue) showHome() else showVideos(sorted(allVideos), q)
        }
    }

    // ------------------------------------------------------------ Home: hero + chips + shelves

    private var homeChip = 0   // index into homeChips()

    private enum class Cat { ALL, MOVIES, WHATSAPP, CAMERA, DOWNLOADS, SHORT }

    private fun inCat(v: Video, c: Cat): Boolean = when (c) {
        Cat.ALL -> true
        Cat.MOVIES -> v.durationMs >= 60 * 60_000L
        Cat.WHATSAPP -> v.folder.contains("whatsapp", true)
        Cat.CAMERA -> v.folder.equals("camera", true) || v.folder.equals("dcim", true)
        Cat.DOWNLOADS -> v.folder.contains("download", true)
        Cat.SHORT -> v.durationMs in 1 until 60_000L
    }

    private fun catLabel(c: Cat) = getString(
        when (c) {
            Cat.ALL -> R.string.chip_all
            Cat.MOVIES -> R.string.chip_movies
            Cat.WHATSAPP -> R.string.chip_whatsapp
            Cat.CAMERA -> R.string.chip_camera
            Cat.DOWNLOADS -> R.string.chip_downloads
            Cat.SHORT -> R.string.chip_short
        }
    )

    /** Videos unna categories matrame chips ga. */
    private fun homeChips(): List<Cat> =
        Cat.values().filter { c -> c == Cat.ALL || allVideos.any { inCat(it, c) } }

    private fun showHome() {
        val chips = homeChips()
        if (homeChip !in chips.indices) homeChip = 0
        val cat = chips[homeChip]
        val all = sorted(allVideos)
        showVideos(all.filter { inCat(it, cat) }, "")
        val rows = recycler.adapter ?: return
        val parts = ArrayList<RecyclerView.Adapter<out RecyclerView.ViewHolder>>()
        // 1A: full-bleed hero (sagam chusinavi, lekapothe kotha video)
        val unfinished = Library.recent(this, allVideos)
            .filter { resumePrefs.getLong(it.uri.toString(), 0L) > 5000 }.take(5)
        val heroList = unfinished.ifEmpty { allVideos.sortedByDescending { it.dateAdded }.take(1) }
        val continuing = unfinished.isNotEmpty()
        parts += HeroAdapter(
            heroList,
            label = { v ->
                if (!continuing) getString(R.string.shelf_recent).uppercase()
                else {
                    val pct = if (v.durationMs > 0)
                        (resumePrefs.getLong(v.uri.toString(), 0L) * 100 / v.durationMs).toInt() else 0
                    getString(R.string.hero_continue, pct)
                }
            },
            meta = { v ->
                val saved = resumePrefs.getLong(v.uri.toString(), 0L)
                val first = if (continuing && v.durationMs > 0)
                    getString(R.string.time_left, formatTime((v.durationMs - saved).coerceAtLeast(0L)))
                else formatTime(v.durationMs)
                listOf(first, v.quality.orEmpty(), android.text.format.Formatter.formatShortFileSize(this, v.sizeBytes))
                    .filter { it.isNotBlank() }.joinToString(" · ")
            },
            buttonText = getString(if (continuing) R.string.resume else R.string.play),
            onResume = { i -> if (i in heroList.indices) openPlayer(listOf(heroList[i]), 0) },
            onAdd = { v -> choosePlaylist(listOf(v.uri.toString())) },
            onInfo = { v -> videoInfo(v) },
            onSearch = { toggleSearch() },
            onMenu = { showHomeMenu(it) },
        )
        if (chips.size > 1) {
            parts += ChipsAdapter(chips.map { catLabel(it) }, homeChip) { i ->
                homeChip = i
                render()
            }
        }
        parts += QuickLinksAdapter(
            listOf(
                getString(R.string.tab_folders) + "\n" + allVideos.map { it.folder }.distinct().size,
                getString(R.string.tab_playlists) + "\n" + (Library.userPlaylistNames(this).size + 1),
                getString(R.string.tab_recent) + "\n" + Library.recent(this, allVideos).size,
            )
        ) { i -> tabs.getTabAt(i + 1)?.select() }
        if (cat == Cat.ALL) {
            fun shelf(title: String, list: List<Video>, portrait: Boolean, seeAll: Cat?) {
                if (list.size < 2) return
                val shown = list.take(15)
                parts += ShelfAdapter(
                    title, shown, resumePrefs, portrait,
                    isNew = { isNew(it) },
                    onSeeAll = seeAll?.let { c -> { homeChip = chips.indexOf(c); render() } },
                    onClick = { i -> openPlayer(shown, i) },
                    onLongClick = { i -> videoMenu(shown[i]) },
                )
            }
            shelf(getString(R.string.shelf_recent), allVideos.sortedByDescending { it.dateAdded }, false, null)
            shelf(getString(R.string.shelf_movies), all.filter { inCat(it, Cat.MOVIES) }, true, Cat.MOVIES)
            shelf(getString(R.string.chip_whatsapp), all.filter { inCat(it, Cat.WHATSAPP) }, false, Cat.WHATSAPP)
            shelf(getString(R.string.chip_camera), all.filter { inCat(it, Cat.CAMERA) }, false, Cat.CAMERA)
            parts += SectionTitleAdapter(getString(R.string.all_videos))
        }
        parts += rows
        recycler.adapter = androidx.recyclerview.widget.ConcatAdapter(parts)
        // Hero lo header undi kabatti paina header, tabs dachu
        header.visibility = View.GONE
        tabs.visibility = View.GONE
        subTitle.visibility = View.GONE
    }

    private fun videoInfo(v: Video) {
        val view = UiKit.infoView(
            this, v.name,
            listOf(
                getString(R.string.info_size) to android.text.format.Formatter.formatFileSize(this, v.sizeBytes),
                getString(R.string.info_resolution) to v.quality.orEmpty(),
                getString(R.string.info_duration) to formatTime(v.durationMs),
                getString(R.string.info_location) to v.folder,
                getString(R.string.info_added) to java.text.DateFormat.getDateInstance()
                    .format(java.util.Date(v.dateAdded * 1000)),
            ),
            thumb = v.uri, badge = formatTime(v.durationMs),
        )
        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton(R.string.play) { _, _ -> openPlayer(listOf(v), 0) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun lastUnfinished(): Video? = Library.recent(this, allVideos)
        .firstOrNull { resumePrefs.getLong(it.uri.toString(), 0L) > 5000 }

    private fun isNew(v: Video): Boolean {
        val key = v.uri.toString()
        return Library.lastPlayed(this, key) == 0L && resumePrefs.getLong(key, 0L) == 0L
    }

    private fun showVideos(source: List<Video>, q: String) {
        val list = if (q.isEmpty()) source else source.filter { it.name.lowercase().contains(q) }
        shownVideos = list
        hideEmpty()
        subTitle.text = getString(R.string.count_videos, list.size)
        recycler.adapter = VideoRowAdapter(
            list, resumePrefs,
            isNew = { isNew(it) },
            selected = selected,
            selecting = { selecting },
            onClick = { i ->
                if (selecting) toggleSelected(list[i]) else openPlayer(list, i)
            },
            onLongClick = { i -> if (selecting) toggleSelected(list[i]) else startSelecting(list[i]) },
            onMore = { i -> videoMenu(list[i]) },
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

    private fun updateMiniBar(hide: Boolean) {
        val last = if (hide) null else lastUnfinished()
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
        thumbMode(true)
        Glide.with(miniThumb).load(last.uri).centerCrop().into(miniThumb)
        val open = View.OnClickListener { openPlayer(listOf(last), 0) }
        miniBar.setOnClickListener(open)
        findViewById<ImageButton>(R.id.mini_play).setOnClickListener(open)
    }

    // ------------------------------------------------------------ selection mode

    private fun startSelecting(first: Video?) {
        selecting = true
        selected.clear()
        if (first != null) selected.add(first.uri.toString())
        render()
    }

    private fun stopSelecting(render: Boolean = true) {
        if (!selecting) return
        selecting = false
        selected.clear()
        if (render) render()
    }

    private fun toggleSelected(v: Video) {
        val k = v.uri.toString()
        if (!selected.add(k)) selected.remove(k)
        selCount.text = getString(R.string.selected_count, selected.size)
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun selectAll() {
        val all = shownVideos.map { it.uri.toString() }
        if (selected.containsAll(all)) selected.clear() else selected.addAll(all)
        selCount.text = getString(R.string.selected_count, selected.size)
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun selectedVideos(): List<Video> {
        val byUri = rawVideos.associateBy { it.uri.toString() }
        return selected.mapNotNull { byUri[it] }
    }

    private fun shareSelected() = shareVideos(selectedVideos())

    private fun shareVideos(list: List<Video>) {
        if (list.isEmpty()) return
        val intent = if (list.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, list[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(list.map { it.uri }))
        }
        intent.type = "video/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (_: Exception) {
        }
    }

    private fun addSelectedToPlaylist() {
        val uris = selectedVideos().map { it.uri.toString() }
        if (uris.isEmpty()) return
        if (privateOpen) {
            uris.forEach { Privacy.remove(this, it) }
            afterPrivacyChange()
            return
        }
        choosePlaylist(uris, allowPrivate = true)
    }

    // ------------------------------------------------------------ per-video menu

    private fun videoMenu(v: Video) {
        val uri = v.uri.toString()
        val fav = Library.isFavourite(this, uri)
        val inPlaylist = openPlaylist?.takeIf { it != Library.FAVOURITES }
        val options = mutableListOf(
            getString(if (fav) R.string.remove_favourite else R.string.add_favourite),
            getString(R.string.add_to_playlist),
            getString(R.string.share),
        )
        if (inPlaylist != null) options += getString(R.string.remove_from_playlist)
        val isPriv = Privacy.isPrivate(this, uri)
        options += getString(if (isPriv) R.string.remove_private else R.string.move_private)
        val privIndex = options.size - 1
        options += getString(R.string.delete_video)
        val deleteIndex = options.size - 1
        MaterialAlertDialogBuilder(this)
            .setTitle(v.name)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == deleteIndex -> askDelete(listOf(v))
                    which == privIndex -> {
                        if (isPriv) Privacy.remove(this, uri) else movePrivate(listOf(uri))
                        afterPrivacyChange()
                    }
                    which == 0 -> {
                        Library.toggleFavourite(this, uri)
                        if (!fav) toast(getString(R.string.added_to, getString(R.string.favourites)))
                        render()
                    }
                    which == 1 -> choosePlaylist(listOf(uri))
                    which == 2 -> shareVideos(listOf(v))
                    which == 3 && inPlaylist != null -> {
                        Library.remove(this, inPlaylist, uri)
                        render()
                    }
                }
            }
            .show()
    }

    private fun choosePlaylist(uris: List<String>, allowPrivate: Boolean = false) {
        val names = listOf(Library.FAVOURITES) + Library.userPlaylistNames(this)
        val labels = names.map { playlistLabel(it) } + getString(R.string.new_playlist) +
            (if (allowPrivate) listOf(getString(R.string.move_private)) else emptyList())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(labels.toTypedArray()) { _, which ->
                if (allowPrivate && which == labels.size - 1) {
                    movePrivate(uris)
                    afterPrivacyChange()
                } else if (which < names.size) {
                    uris.forEach { Library.add(this, names[which], it) }
                    toast(getString(R.string.added_to, labels[which]))
                    stopSelecting()
                } else {
                    askPlaylistName { name ->
                        if (Library.create(this, name)) {
                            uris.forEach { Library.add(this, name.trim(), it) }
                            toast(getString(R.string.added_to, name.trim()))
                            stopSelecting()
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

    // ------------------------------------------------------------ delete (one or many)

    private fun askDelete(list: List<Video>) {
        if (list.isEmpty()) return
        pendingDelete = list
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: system ye "Allow Flikk to delete?" ani adugutundi
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, list.map { it.uri })
                retryDeleteAfterGrant = false
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (_: Exception) {
                toast(getString(R.string.delete_failed))
            }
            return
        }
        val label = if (list.size == 1) list[0].name else getString(R.string.selected_count, list.size)
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, label))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (Build.VERSION.SDK_INT <= 28 &&
                    !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                ) {
                    writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    deleteNow(list)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteNow(list: List<Video>) {
        val done = ArrayList<Video>()
        for (v in list) {
            try {
                if (contentResolver.delete(v.uri, null, null) > 0) done += v
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                    pendingDelete = list - done.toSet()
                    retryDeleteAfterGrant = true
                    deleteLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                    if (done.isNotEmpty()) onDeleted(done, keepPending = true)
                    return
                }
            } catch (_: Exception) {
            }
        }
        if (done.isEmpty()) toast(getString(R.string.delete_failed)) else onDeleted(done)
    }

    private fun onDeleted(list: List<Video>, keepPending: Boolean = false) {
        if (!keepPending) pendingDelete = emptyList()
        val gone = list.map { it.uri }.toSet()
        list.forEach { Library.forget(this, it.uri.toString()) }
        rawVideos = rawVideos.filter { it.uri !in gone }
        list.forEach { Privacy.remove(this, it.uri.toString()) }
        applyPrivacy()
        toast(getString(R.string.deleted))
        selecting = false
        selected.clear()
        render()
    }

    // ------------------------------------------------------------ music

    private fun renderMusic(q: String) {
        if (!hasAudioPermission()) {
            subTitle.text = ""
            showEmpty(getString(R.string.need_audio_permission), showButton = true)
            return
        }
        if (allTracks.isEmpty()) {
            subTitle.text = ""
            showEmpty(getString(R.string.no_music), showButton = false)
            return
        }
        val album = openAlbum
        val artist = openArtist
        val tracks = musicTracks()
        when {
            album != null -> showSongs(tracks.filter { albumOf(it) == album }, q)
            artist != null -> showSongs(tracks.filter { artistOf(it) == artist }, q)
            q.isNotEmpty() || musicTab == 0 -> showSongs(tracks, q)
            musicTab == 1 -> showGroups(tracks.groupBy { albumOf(it) }, R.drawable.ic_library) { name ->
                openAlbum = name
                render()
            }
            else -> showGroups(tracks.groupBy { artistOf(it) }, R.drawable.ic_person) { name ->
                openArtist = name
                render()
            }
        }
    }

    private fun showAllAudio() = settings.getBoolean("all_audio", false)

    private val junkWords = listOf("call", "record", "voice", "whatsapp audio", "ptt-", "aud-", "opus")

    /** Call recordings, voice notes, 30 sec kanna chinna clips ni songs list nunchi dachu. */
    private fun musicTracks(): List<Track> {
        if (showAllAudio()) return allTracks
        return allTracks.filter { t ->
            val title = t.title.lowercase()
            val album = t.album.lowercase()
            val phoneLike = title.trimStart('+').take(6).all { it.isDigit() }
            t.durationMs >= 30_000L && !phoneLike &&
                junkWords.none { w -> title.contains(w) || album.contains(w) }
        }
    }

    private fun letterOf(s: String): String =
        s.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"

    private fun showMusicMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.show_all_audio).isCheckable = true
            menu.findItem(1).isChecked = showAllAudio()
            setOnMenuItemClickListener {
                settings.edit().putBoolean("all_audio", !showAllAudio()).apply()
                render()
                true
            }
            show()
        }
    }

    private fun albumOf(t: Track) =
        t.album.takeIf { it.isNotBlank() && it != "<unknown>" } ?: getString(R.string.unknown_album)

    private fun artistOf(t: Track) =
        t.artist.takeIf { it.isNotBlank() && it != "<unknown>" } ?: getString(R.string.unknown_artist)

    private fun showSongs(source: List<Track>, q: String) {
        val list = if (q.isEmpty()) source
        else source.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
        if (list.isEmpty()) {
            subTitle.text = ""
            showEmpty(getString(R.string.no_music), showButton = false)
            return
        }
        hideEmpty()
        subTitle.text = getString(R.string.count_songs, list.size)
        val nowId = PlayerHolder.peek()?.currentMediaItem?.mediaId
        fun play(l: List<Track>, i: Int) = launchPlayer(
            l.map { it.uri.toString() }, l.map { it.title }, i,
            audio = true, artists = l.map { it.artist }, durations = l.map { it.durationMs },
        )
        val rows = RowAdapter(list.mapIndexed { i, t ->
            Row(
                t.title, "${artistOf(t)} · ${formatTime(t.durationMs)}", R.drawable.ic_music,
                onClick = { play(list, i) },
                letter = letterOf(t.title),
                playing = t.uri.toString() == nowId,
            )
        })
        recycler.adapter = if (list.size > 1) {
            androidx.recyclerview.widget.ConcatAdapter(
                ShuffleHeaderAdapter(getString(R.string.shuffle_all, list.size)) { play(list.shuffled(), 0) },
                rows,
            )
        } else rows
    }

    private fun showGroups(groups: Map<String, List<Track>>, icon: Int, open: (String) -> Unit) {
        hideEmpty()
        val sortedGroups = groups.toList().sortedBy { it.first.lowercase() }
        subTitle.text = getString(R.string.count_songs, allTracks.size)
        recycler.adapter = RowAdapter(sortedGroups.map { (name, songs) ->
            Row(name, getString(R.string.count_songs, songs.size), icon, onClick = { open(name) })
        })
    }

    // ------------------------------------------------------------ private folder

    private fun movePrivate(uris: List<String>) {
        if (!Privacy.hasPin(this)) {
            // Mundu PIN set cheyyali, lekapothe tirigi teyalemu
            PinScreen(this, setMode = true) { movePrivate(uris) }.show()
            return
        }
        Privacy.add(this, uris)
        toast(getString(R.string.moved_private, uris.size))
    }

    private fun afterPrivacyChange() {
        applyPrivacy()
        stopSelecting(render = false)
        render()
    }

    private fun renderPrivate() {
        val hidden = Privacy.all(this)
        val list = sorted(rawVideos.filter { it.uri.toString() in hidden })
        if (list.isEmpty()) {
            subTitle.text = getString(R.string.private_note)
            showEmpty(getString(R.string.private_empty), showButton = false)
            shownVideos = emptyList()
            return
        }
        showVideos(list, query.trim().lowercase())
        subTitle.text = getString(R.string.count_videos, list.size) + " · " + getString(R.string.private_note)
    }

    private fun openPrivateNow() {
        privateOpen = true
        render()
    }

    private fun unlockPrivate() {
        if (!Privacy.hasPin(this)) {
            PinScreen(this, setMode = true) { openPrivateNow() }.show()
            return
        }
        PinScreen(this, setMode = false) { openPrivateNow() }.show()
    }


    private fun thumbMode(real: Boolean) {
        val pad = if (real) 0 else (12 * resources.displayMetrics.density).toInt()
        miniThumb.setPadding(pad, pad, pad, pad)
        miniThumb.scaleType = if (real) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        miniThumb.imageTintList = if (real) null else android.content.res.ColorStateList.valueOf(0xFFBDBDBD.toInt())
    }

    /** Music tab lo "ippudu play avuthunna paata" bar — tap chesthe music screen. */
    private fun updateMusicBar() {
        val p = PlayerHolder.peek()
        val item = p?.currentMediaItem
        if (p == null || item == null || item.mediaMetadata.mediaType != androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = item.mediaMetadata.title
        miniSub.text = item.mediaMetadata.artist ?: getString(R.string.now_playing)
        val art = p.mediaMetadata.artworkData
        if (art != null) {
            thumbMode(true)
            miniThumb.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size))
        } else {
            // Art lekapothe: box lo pattetattu chinna music icon
            thumbMode(false)
            miniThumb.setImageResource(R.drawable.ic_music)
        }
        miniBar.setOnClickListener { startActivity(Intent(this, MusicActivity::class.java)) }
        val playBtn = findViewById<ImageButton>(R.id.mini_play)
        playBtn.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.setOnClickListener {
            if (p.playWhenReady) p.pause() else p.play()
            playBtn.setImageResource(if (p.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    // ------------------------------------------------------------ Me (settings)

    private fun renderMe() {
        hideEmpty()
        subTitle.text = ""
        val bg = settings.getBoolean(Prefs.BG_PLAY, false)
        val langTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val langLabel = when {
            langTag.startsWith("te") -> getString(R.string.language_telugu)
            langTag.startsWith("ta") -> getString(R.string.language_tamil)
            langTag.startsWith("hi") -> getString(R.string.language_hindi)
            langTag.startsWith("en") -> getString(R.string.language_english)
            else -> getString(R.string.language_system)
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        recycler.adapter = RowAdapter(listOf(
            Row(getString(R.string.language), langLabel, R.drawable.ic_language, onClick = { chooseLanguage() }),
            Row(
                getString(R.string.background_play),
                getString(if (bg) R.string.on else R.string.off),
                R.drawable.ic_headphones,
                onClick = {
                    settings.edit().putBoolean(Prefs.BG_PLAY, !bg).apply()
                    render()
                },
            ),
            Row(
                getString(R.string.private_folder),
                getString(R.string.count_videos, Privacy.all(this).size),
                R.drawable.ic_lock,
                onClick = { unlockPrivate() },
            ),
            Row(getString(R.string.open_url), "", R.drawable.ic_link, onClick = { openUrlDialog() }),
            Row(getString(R.string.scan_videos), "", R.drawable.ic_refresh, onClick = { scanForVideos() }),
            Row(getString(R.string.about), getString(R.string.version, version), R.drawable.ic_info, onClick = {}),
        ))
    }

    private fun chooseLanguage() {
        val tags = arrayOf("", "en", "te", "ta", "hi")
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_telugu),
            getString(R.string.language_tamil),
            getString(R.string.language_hindi),
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

    // ------------------------------------------------------------ network (PC / NAS)

    private fun openDownloader(tab: Int) {
        startActivity(Intent(this, DownloaderActivity::class.java).putExtra(DownloaderActivity.EXTRA_TAB, tab))
    }

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
            val tools = listOf(
                Row(getString(R.string.dl_link_row), getString(R.string.dl_link_row_sub), R.drawable.ic_download,
                    onClick = { openDownloader(DownloaderActivity.TAB_LINK) }),
                Row(getString(R.string.dl_status_row), getString(R.string.dl_status_row_sub), R.drawable.ic_status,
                    onClick = { openDownloader(DownloaderActivity.TAB_STATUS) }),
            )
            recycler.adapter = RowAdapter(tools + rows)
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
                            sizes = media.map { it.size },
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
                // "\\192.168.1.5\Movies" / "smb://192.168.1.5/Movies" kuda ardham chesukuntam
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

    // ------------------------------------------------------------ player

    private fun openPlayer(list: List<Video>, index: Int) =
        launchPlayer(
            list.map { it.uri.toString() }, list.map { it.name }, index,
            audio = false, sizes = list.map { it.sizeBytes },
        )

    private fun launchPlayer(
        uris: List<String>,
        titles: List<String>,
        index: Int,
        audio: Boolean,
        artists: List<String>? = null,
        sizes: List<Long>? = null,
        durations: List<Long>? = null,
    ) {
        if (index !in uris.indices) return
        launching = true
        // Intent size limit dhaatakunda, chuttupakkala items matrame playlist lo pampistham
        val from = (index - 100).coerceAtLeast(0)
        val to = (index + 200).coerceAtMost(uris.size)
        // Songs ki music screen, videos ki video player
        val target = if (audio) MusicActivity::class.java else PlayerActivity::class.java
        val intent = Intent(this, target)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(uris.subList(from, to)))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(titles.subList(from, to)))
            .putExtra(PlayerActivity.EXTRA_INDEX, index - from)
            .putExtra(PlayerActivity.EXTRA_AUDIO, audio)
        if (artists != null) {
            intent.putStringArrayListExtra(PlayerActivity.EXTRA_ARTISTS, ArrayList(artists.subList(from, to)))
        }
        if (durations != null) {
            intent.putExtra(EXTRA_DURATIONS_KEY, durations.subList(from, to).toLongArray())
        }
        if (sizes != null) {
            intent.putExtra(PlayerActivity.EXTRA_SIZES, sizes.subList(from, to).toLongArray())
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
        netIo.shutdown()
    }
}
