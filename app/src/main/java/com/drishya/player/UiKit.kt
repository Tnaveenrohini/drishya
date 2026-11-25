package com.drishya.player

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide

/** App style lo Info card: thumbnail (optional), title, label · value rows. */
object UiKit {
    fun infoView(
        c: Context,
        title: String,
        rows: List<Pair<String, String>>,
        thumb: Uri? = null,
        badge: String? = null,
    ): View {
        val view = LayoutInflater.from(c).inflate(R.layout.dialog_info, null)
        val box = view.findViewById<View>(R.id.info_box)
        if (thumb != null) {
            box.clipToOutline = true
            Glide.with(c).load(thumb).centerCrop().into(view.findViewById<ImageView>(R.id.info_thumb))
            val b = view.findViewById<TextView>(R.id.info_badge)
            if (badge.isNullOrBlank()) b.visibility = View.GONE else b.text = badge
        } else {
            box.visibility = View.GONE
            (view.findViewById<View>(R.id.info_title).layoutParams as LinearLayout.LayoutParams).topMargin = 0
        }
        view.findViewById<TextView>(R.id.info_title).text = title
        val list = view.findViewById<LinearLayout>(R.id.info_rows)
        val d = c.resources.displayMetrics.density
        rows.filter { it.second.isNotBlank() }.forEachIndexed { i, (k, value) ->
            if (i > 0) list.addView(
                View(c).apply { setBackgroundColor(0xFF1E1E1E.toInt()) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, d.toInt().coerceAtLeast(1)),
            )
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (11 * d).toInt(), 0, (11 * d).toInt())
            }
            row.addView(TextView(c).apply {
                text = k
                textSize = 13f
                setTextColor(0xFF8A8A8A.toInt())
            }, LinearLayout.LayoutParams((92 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(c).apply {
                text = value
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.END
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
        return view
    }
}
