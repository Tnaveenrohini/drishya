package com.drishya.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

data class Video(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
    val quality: String?,
    val dateAdded: Long = 0L,
)

data class Playlist(val key: String, val uris: List<String>) {
    val isFavourites get() = key == Library.FAVOURITES
}

object Prefs {
    const val RESUME = "resume"
    const val SETTINGS = "settings"
    const val BG_PLAY = "bg_play"
}

object VideoRepository {
    fun load(context: Context): List<Video> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val out = ArrayList<Video>()
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val wCol = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val dateCol = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val w = if (wCol >= 0) c.getInt(wCol) else 0
                val h = if (hCol >= 0) c.getInt(hCol) else 0
                out += Video(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "Video $id",
                    durationMs = c.getLong(durCol),
                    sizeBytes = c.getLong(sizeCol),
                    folder = c.getString(bucketCol) ?: "Other",
                    quality = qualityLabel(w, h),
                    dateAdded = if (dateCol >= 0) c.getLong(dateCol) else 0L,
                )
            }
        }
        return out
    }

    private fun qualityLabel(w: Int, h: Int): String? {
        if (w <= 0 || h <= 0) return null
        val short = min(w, h)
        return when {
            short >= 2000 -> "4K"
            short >= 1400 -> "1440P"
            short >= 1000 -> "1080P"
            short >= 700 -> "720P"
            else -> null
        }
    }
}

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

object AudioRepository {
    fun load(context: Context): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val out = ArrayList<Track>()
        context.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val tCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val aCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val alCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val dCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(aCol).orEmpty().let { if (it == "<unknown>") "" else it }
                out += Track(
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(tCol) ?: "Track $id",
                    artist = artist,
                    album = c.getString(alCol).orEmpty(),
                    durationMs = c.getLong(dCol),
                )
            }
        }
        return out
    }
}

/** Playlists, favourites and "recently watched" — phone lone save avthayi. */
object Library {
    const val FAVOURITES = "__favourites__"
    private const val NAME = "library"
    private const val KEY_LISTS = "playlists"
    private const val PLAYED = "played:"

    private fun prefs(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun readAll(c: Context): LinkedHashMap<String, MutableList<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        map[FAVOURITES] = mutableListOf()
        val raw = prefs(c).getString(KEY_LISTS, null) ?: return map
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = obj.getJSONArray(k)
                map[k] = MutableList(arr.length()) { arr.getString(it) }
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun writeAll(c: Context, map: Map<String, List<String>>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
        prefs(c).edit().putString(KEY_LISTS, obj.toString()).apply()
    }

    fun playlists(c: Context): List<Playlist> = readAll(c).map { (k, v) -> Playlist(k, v) }

    fun userPlaylistNames(c: Context): List<String> = readAll(c).keys.filter { it != FAVOURITES }

    fun get(c: Context, key: String): Playlist? = readAll(c)[key]?.let { Playlist(key, it) }

    fun create(c: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n == FAVOURITES) return false
        val all = readAll(c)
        if (!all.containsKey(n)) all[n] = mutableListOf()
        writeAll(c, all)
        return true
    }

    fun delete(c: Context, key: String) {
        if (key == FAVOURITES) return
        val all = readAll(c)
        all.remove(key)
        writeAll(c, all)
    }

    fun add(c: Context, key: String, uri: String) {
        val all = readAll(c)
        val list = all.getOrPut(key) { mutableListOf() }
        if (uri !in list) list.add(0, uri)
        writeAll(c, all)
    }

    fun remove(c: Context, key: String, uri: String) {
        val all = readAll(c)
        all[key]?.remove(uri)
        writeAll(c, all)
    }

    fun isFavourite(c: Context, uri: String) = readAll(c)[FAVOURITES]?.contains(uri) == true

    fun toggleFavourite(c: Context, uri: String) {
        if (isFavourite(c, uri)) remove(c, FAVOURITES, uri) else add(c, FAVOURITES, uri)
    }

    fun videosOf(c: Context, key: String, all: List<Video>): List<Video> {
        val byUri = all.associateBy { it.uri.toString() }
        return get(c, key)?.uris?.mapNotNull { byUri[it] } ?: emptyList()
    }

    /** Video phone nunchi delete ayyaka anni lists nunchi teeseyyi. */
    fun forget(c: Context, uri: String) {
        val all = readAll(c)
        all.values.forEach { it.remove(uri) }
        writeAll(c, all)
        prefs(c).edit().remove(PLAYED + uri).apply()
        c.getSharedPreferences(Prefs.RESUME, Context.MODE_PRIVATE).edit().remove(uri).apply()
    }

    fun lastPlayed(c: Context, uri: String) = prefs(c).getLong(PLAYED + uri, 0L)

    fun markPlayed(c: Context, uri: String) {
        prefs(c).edit().putLong(PLAYED + uri, System.currentTimeMillis()).apply()
    }

    fun recent(c: Context, all: List<Video>): List<Video> {
        val p = prefs(c)
        return all.map { it to p.getLong(PLAYED + it.uri.toString(), 0L) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(60)
            .map { it.first }
    }
}

fun formatTime(ms: Long): String {
    val total = abs(ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
