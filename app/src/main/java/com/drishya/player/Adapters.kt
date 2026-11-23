package com.drishya.player

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.widget.FrameLayout
import android.graphics.Typeface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.DateFormat
import java.util.Date

/** Home list row (thumbnail + name + date + ⋮), selection mode tho. */
class VideoRowAdapter(
    private val videos: List<Video>,
    private val resume: SharedPreferences,
    private val isNew: (Video) -> Boolean,
    private val selected: MutableSet<String>,
    private val selecting: () -> Boolean,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onMore: (Int) -> Unit,
) : RecyclerView.Adapter<VideoRowAdapter.VH>() {

    private val dateFormat = DateFormat.getDateInstance(DateFormat.LONG)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge)
        val duration: TextView = v.findViewById(R.id.duration)
        val bar: View = v.findViewById(R.id.resume_bar)
        val name: TextView = v.findViewById(R.id.video_name)
        val meta: TextView = v.findViewById(R.id.video_meta)
        val more: ImageButton = v.findViewById(R.id.item_more)
        val check: ImageView = v.findViewById(R.id.item_check)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video_row, parent, false))
        vh.thumb.clipToOutline = true
        return vh
    }

    override fun getItemCount() = videos.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = videos[position]
        val key = v.uri.toString()
        h.name.text = v.name
        h.duration.text = formatTime(v.durationMs)
        val date = if (v.dateAdded > 0) dateFormat.format(Date(v.dateAdded * 1000)) else ""
        h.meta.text = listOf(date, formatSize(v.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · ")
        h.badge.visibility = if (isNew(v)) View.VISIBLE else View.GONE
        val saved = resume.getLong(key, 0L)
        if (saved > 5000 && v.durationMs > 0) {
            h.bar.visibility = View.VISIBLE
            h.bar.pivotX = 0f
            h.bar.scaleX = (saved.toFloat() / v.durationMs).coerceIn(0.02f, 1f)
        } else {
            h.bar.visibility = View.GONE
        }
        Glide.with(h.thumb).load(v.uri).centerCrop().placeholder(R.drawable.bg_thumb).into(h.thumb)

        if (selecting()) {
            h.more.visibility = View.GONE
            h.check.visibility = View.VISIBLE
            h.check.setImageResource(
                if (key in selected) R.drawable.ic_check_box else R.drawable.ic_check_box_blank
            )
        } else {
            h.more.visibility = View.VISIBLE
            h.check.visibility = View.GONE
        }
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
        h.more.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onMore(p)
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

data class QueueItem(val uri: Uri, val title: String, val sub: String)

/** Player lo right side "Playlist" panel. */
class QueueAdapter(
    private val items: List<QueueItem>,
    private val current: Int,
    private val nowPlaying: String,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.q_thumb)
        val title: TextView = v.findViewById(R.id.q_title)
        val sub: TextView = v.findViewById(R.id.q_sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false))
        vh.thumb.clipToOutline = true
        return vh
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        val isCurrent = position == current
        h.title.text = it.title
        h.title.setTextColor(if (isCurrent) 0xFFFFFFFF.toInt() else 0xFFCFCFCF.toInt())
        h.title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
        h.sub.text = if (isCurrent) "▶  $nowPlaying" else it.sub
        if (it.uri.scheme == "content" || it.uri.scheme == "file") {
            Glide.with(h.thumb).load(it.uri).centerCrop().placeholder(R.drawable.bg_thumb).into(h.thumb)
        } else {
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
        }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}

/** Width, height lo chinnadhi tisukuni square ga untundi (max 340dp) — music cover kosam. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val max = (340 * resources.displayMetrics.density).toInt()
        var size = if (h > 0) minOf(w, h) else w
        size = minOf(size, max)
        val spec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}
