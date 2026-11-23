package com.drishya.player

import android.app.Activity
import android.content.Context
import android.util.TypedValue

/** Black & White theme: accent eppudu white. */
object ThemeColors {
    fun apply(a: Activity) {
        // Okate theme (B&W) — manifest lo unna Theme.Flikk ye
    }

    fun accent(c: Context): Int {
        val tv = TypedValue()
        return if (c.theme.resolveAttribute(R.attr.flikkAccent, tv, true)) tv.data else 0xFFFFFFFF.toInt()
    }
}
