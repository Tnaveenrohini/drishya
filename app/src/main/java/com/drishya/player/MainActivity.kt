package com.drishya.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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

    override fun onCreate(savedInstanceState: Bundle?) {
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

        listOf(R.string.tab_all, R.string.tab_folders, R.string.tab_playlists, R.string.tab_recent)
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

    private fun requestPermission() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permLauncher.launch(perms)
    }

    private fun refresh() {
        if (!hasPermission()) {
            showEmpty(getString(R.string.need_permission), showButton = true)
            miniBar.visibility = View.GONE
            return
        }
        io.execute {
            val list = try { VideoRepository.load(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allVideos = list
                loaded = true
                render()
            }
        }
    }

    // ------------------------------------------------------------ rendering

    private fun goBack() {
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
        val inside = openFolder != null || openPlaylist != null
        backCallback.isEnabled = inside
        toolbar.navigationIcon =
            if (inside) ContextCompat.getDrawable(this, R.drawable.ic_back) else null
        tabs.visibility = if (inside) View.GONE else View.VISIBLE
        bigTitle.text = when {
            openFolder != null -> openFolder
            openPlaylist != null -> playlistLabel(openPlaylist!!)
            else -> getString(R.string.title_videos)
        }
        if (!loaded) return
        updateMiniBar(inside)

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
        MaterialAlertDialogBuilder(this)
            .setTitle(v.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        Library.toggleFavourite(this, uri)
                        if (!fav) toast(getString(R.string.added_to, getString(R.string.favourites)))
                        render()
                    }
                    1 -> choosePlaylist(uri)
                    2 -> {
                        Library.remove(this, inPlaylist!!, uri)
                        render()
                    }
                }
            }
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

    private fun openPlayer(list: List<Video>, index: Int) {
        // Intent size limit dhaatakunda, chuttupakkala videos matrame playlist lo pampistham
        val from = (index - 100).coerceAtLeast(0)
        val to = (index + 200).coerceAtMost(list.size)
        val window = list.subList(from, to)
        val intent = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(window.map { it.uri.toString() }))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(window.map { it.name }))
            .putExtra(PlayerActivity.EXTRA_INDEX, index - from)
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
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
