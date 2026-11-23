package com.drishya.player

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/** 16:9 box for video thumbnails. */
class RatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY))
    }
}

class VideoCardAdapter(
    private val videos: List<Video>,
    private val resume: SharedPreferences,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<VideoCardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge)
        val duration: TextView = v.findViewById(R.id.duration)
        val bar: View = v.findViewById(R.id.resume_bar)
        val name: TextView = v.findViewById(R.id.video_name)
        val meta: TextView = v.findViewById(R.id.video_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video_card, parent, false))
        vh.thumb.clipToOutline = true
        return vh
    }

    override fun getItemCount() = videos.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = videos[position]
        h.name.text = v.name
        h.duration.text = formatTime(v.durationMs)
        h.meta.text = "${v.folder} · ${formatSize(v.sizeBytes)}"
        if (v.quality != null) {
            h.badge.visibility = View.VISIBLE
            h.badge.text = v.quality
        } else {
            h.badge.visibility = View.GONE
        }
        val saved = resume.getLong(v.uri.toString(), 0L)
        if (saved > 5000 && v.durationMs > 0) {
            h.bar.visibility = View.VISIBLE
            h.bar.pivotX = 0f
            h.bar.scaleX = (saved.toFloat() / v.durationMs).coerceIn(0.02f, 1f)
        } else {
            h.bar.visibility = View.GONE
        }
        Glide.with(h.thumb).load(v.uri).centerCrop().placeholder(R.drawable.bg_thumb).into(h.thumb)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
    }
}

data class Row(
    val name: String,
    val sub: String,
    val icon: Int,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

class RowAdapter(private val rows: List<Row>) : RecyclerView.Adapter<RowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.row_icon)
        val name: TextView = v.findViewById(R.id.row_name)
        val sub: TextView = v.findViewById(R.id.row_sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.name.text = r.name
        h.sub.text = r.sub
        h.sub.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        h.icon.setImageResource(r.icon)
        h.itemView.setOnClickListener { r.onClick() }
        val long = r.onLongClick
        if (long != null) {
            h.itemView.setOnLongClickListener { long(); true }
        } else {
            h.itemView.setOnLongClickListener(null)
            h.itemView.isLongClickable = false
        }
    }
}
