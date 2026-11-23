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
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var allowBtn: Button

    private var allVideos: List<Video> = emptyList()
    private var openFolder: Folder? = null
    private var query = ""
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences(Prefs.RESUME, MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            openFolder = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        recycler = findViewById(R.id.recycler)
        emptyBox = findViewById(R.id.empty_box)
        emptyText = findViewById(R.id.empty_text)
        allowBtn = findViewById(R.id.allow_btn)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { backCallback.handleOnBackPressed() }
        recycler.layoutManager = LinearLayoutManager(this)
        allowBtn.setOnClickListener { requestPermission() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (!hasPermission()) requestPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

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
            return
        }
        io.execute {
            val list = try { VideoRepository.load(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                allVideos = list
                loaded = true
                // folder lo unte, refreshed data tho update cheyyi
                openFolder = openFolder?.let { f ->
                    if (f.isAll) Folder(f.name, allVideos, true)
                    else Folder(f.name, allVideos.filter { it.folder == f.name })
                }
                render()
            }
        }
    }

    private fun showEmpty(text: String, showButton: Boolean) {
        recycler.visibility = View.GONE
        emptyBox.visibility = View.VISIBLE
        emptyText.text = text
        allowBtn.visibility = if (showButton) View.VISIBLE else View.GONE
    }

    private fun render() {
        val folder = openFolder
        backCallback.isEnabled = folder != null
        supportActionBar?.title = folder?.name ?: getString(R.string.app_name)
        toolbar.navigationIcon =
            if (folder != null) ContextCompat.getDrawable(this, R.drawable.ic_back) else null

        if (!loaded) return
        if (allVideos.isEmpty()) {
            supportActionBar?.subtitle = null
            showEmpty(getString(R.string.no_videos), showButton = false)
            return
        }
        emptyBox.visibility = View.GONE
        recycler.visibility = View.VISIBLE

        val q = query.trim().lowercase()
        if (folder == null && q.isEmpty()) {
            val folders = allVideos.groupBy { it.folder }
                .map { (name, vids) -> Folder(name, vids) }
                .sortedBy { it.name.lowercase() }
            supportActionBar?.subtitle = "${allVideos.size} videos · ${folders.size} folders"
            val all = Folder(getString(R.string.all_videos), allVideos, isAll = true)
            recycler.adapter = FolderAdapter(listOf(all) + folders) { f ->
                openFolder = f
                render()
            }
        } else {
            val source = folder?.videos ?: allVideos
            val list = if (q.isEmpty()) source else source.filter { it.name.lowercase().contains(q) }
            supportActionBar?.subtitle = "${list.size} videos"
            recycler.adapter = VideoAdapter(list, prefs) { i -> if (i in list.indices) openPlayer(list, i) }
        }
    }

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
        if (item.itemId == R.id.action_refresh) {
            refresh()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
