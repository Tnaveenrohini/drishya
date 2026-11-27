package com.drishya.player

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Flikk style "Cast to" sheet: Wi-Fi lo unna TVs list, connect / disconnect, controls. */
class CastSheet(
    private val activity: AppCompatActivity,
    private val castCtx: CastContext,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
) {
    private val dialog = BottomSheetDialog(activity)
    private val view = activity.layoutInflater.inflate(R.layout.sheet_cast, null)
    private val list: LinearLayout = view.findViewById(R.id.cast_list)
    private val searching: View = view.findViewById(R.id.cast_searching)
    private val controls: View = view.findViewById(R.id.cast_controls)
    private val progress: ProgressBar = view.findViewById(R.id.cast_progress)
    private val pos: TextView = view.findViewById(R.id.cast_pos)
    private val dur: TextView = view.findViewById(R.id.cast_dur)
    private val playBtn: ImageButton = view.findViewById(R.id.cast_play)
    private val router = MediaRouter.getInstance(activity)
    private val selector = castCtx.mergedSelector
    private val handler = Handler(Looper.getMainLooper())
    private val d = activity.resources.displayMetrics.density

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        override fun onRouteUnselected(r: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
    }

    private val tick = object : Runnable {
        override fun run() {
            updateControls()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * d).toInt()

    fun show() {
        view.findViewById<View>(R.id.cast_prev).setOnClickListener { onPrev() }
        view.findViewById<View>(R.id.cast_next).setOnClickListener { onNext() }
        playBtn.setOnClickListener { castCtx.sessionManager.currentCastSession?.remoteMediaClient?.togglePlayback() }
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(tick)
            router.removeCallback(callback)
        }
        if (selector != null) {
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        }
        refresh()
        handler.post(tick)
        dialog.show()
    }

    private fun routes(): List<MediaRouter.RouteInfo> {
        val sel = selector ?: return emptyList()
        return router.routes.filter { !it.isDefault && it.isEnabled && !it.isBluetooth && it.matchesSelector(sel) }
    }

    private fun refresh() {
        list.removeAllViews()
        val rs = routes()
        searching.visibility = if (rs.isEmpty()) View.VISIBLE else View.GONE
        rs.forEach { r -> list.addView(row(r)) }
        updateControls()
    }

    private fun row(r: MediaRouter.RouteInfo): View {
        val on = r.isSelected && castCtx.sessionManager.currentCastSession?.isConnected == true
        val fg = if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minimumHeight = dp(68)
            isClickable = true
            background = if (on) ContextCompat.getDrawable(activity, R.drawable.bg_btn_white)
            else ContextCompat.getDrawable(activity, R.drawable.bg_row_ripple)
            setOnClickListener {
                if (on) castCtx.sessionManager.endCurrentSession(true) else router.selectRoute(r)
                handler.postDelayed({ refresh() }, 600)
            }
        }
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_cast)
            imageTintList = android.content.res.ColorStateList.valueOf(fg)
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(8), 0)
        }
        texts.addView(TextView(activity).apply {
            text = r.name
            setTextColor(fg)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        })
        val desc = r.description?.toString()?.takeIf { it.isNotBlank() } ?: "Chromecast"
        texts.addView(TextView(activity).apply {
            text = if (on) activity.getString(R.string.cast_connected, desc) else desc
            setTextColor(if (on) 0xFF555555.toInt() else 0xFFA3A3A3.toInt())
            textSize = 13f
            maxLines = 1
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (on) {
            row.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(fg)
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        return LinearLayout(activity).apply {
            setPadding(0, dp(3), 0, dp(3))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun updateControls() {
        val remote = castCtx.sessionManager.currentCastSession?.remoteMediaClient
        if (remote == null || !remote.hasMediaSession()) {
            controls.visibility = View.GONE
            return
        }
        controls.visibility = View.VISIBLE
        val total = remote.streamDuration
        val now = remote.approximateStreamPosition
        progress.progress = if (total > 0) (now * 1000 / total).toInt() else 0
        pos.text = formatTime(now)
        dur.text = formatTime(total)
        playBtn.setImageResource(if (remote.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }
}
