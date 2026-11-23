package com.drishya.player

import android.content.SharedPreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class FolderAdapter(
    private val folders: List<Folder>,
    private val onClick: (Folder) -> Unit,
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.folder_icon)
        val name: TextView = v.findViewById(R.id.folder_name)
        val count: TextView = v.findViewById(R.id.folder_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

    override fun getItemCount() = folders.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val f = folders[position]
        h.name.text = f.name
        val n = f.videos.size
        h.count.text = if (n == 1) "1 video" else "$n videos"
        h.icon.setImageResource(if (f.isAll) R.drawable.ic_library else R.drawable.ic_folder)
        h.itemView.setOnClickListener { onClick(f) }
    }
}

class VideoAdapter(
    private val videos: List<Video>,
    private val prefs: SharedPreferences,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val duration: TextView = v.findViewById(R.id.duration)
        val name: TextView = v.findViewById(R.id.video_name)
        val meta: TextView = v.findViewById(R.id.video_meta)
        val progress: View = v.findViewById(R.id.resume_bar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))
        vh.thumb.clipToOutline = true
        return vh
    }

    override fun getItemCount() = videos.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = videos[position]
        h.name.text = v.name
        h.duration.text = formatTime(v.durationMs)
        val saved = prefs.getLong(v.uri.toString(), 0L)
        var meta = "${formatSize(v.sizeBytes)} · ${v.folder}"
        if (saved > 5000 && v.durationMs > 0) {
            meta += " · ▶ ${formatTime(saved)}"
            h.progress.visibility = View.VISIBLE
            h.progress.pivotX = 0f
            h.progress.scaleX = (saved.toFloat() / v.durationMs).coerceIn(0.02f, 1f)
        } else {
            h.progress.visibility = View.GONE
        }
        h.meta.text = meta
        Glide.with(h.thumb)
            .load(v.uri)
            .centerCrop()
            .placeholder(R.drawable.bg_thumb)
            .into(h.thumb)
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}
