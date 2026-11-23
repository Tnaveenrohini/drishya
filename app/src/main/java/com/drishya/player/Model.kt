package com.drishya.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Locale
import kotlin.math.abs

data class Video(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
)

data class Folder(val name: String, val videos: List<Video>, val isAll: Boolean = false)

object Prefs {
    const val RESUME = "resume"
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
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += Video(
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "Video $id",
                    durationMs = c.getLong(durCol),
                    sizeBytes = c.getLong(sizeCol),
                    folder = c.getString(bucketCol) ?: "Other",
                )
            }
        }
        return out
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
