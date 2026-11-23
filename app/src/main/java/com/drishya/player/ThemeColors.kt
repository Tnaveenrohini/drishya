package com.drishya.player

import android.app.Activity
import android.content.Context
import android.util.TypedValue

/** User pick chesina theme colour (accent). Activity onCreate lo apply cheyyali. */
object ThemeColors {
    data class Option(val key: String, val label: Int, val overlay: Int, val color: Int)

    val options = listOf(
        Option("green", R.string.colour_green, R.style.ThemeOverlay_Flikk_Green, 0xFF3CCB6A.toInt()),
        Option("pink", R.string.colour_pink, R.style.ThemeOverlay_Flikk_Pink, 0xFFFF3D8B.toInt()),
        Option("blue", R.string.colour_blue, R.style.ThemeOverlay_Flikk_Blue, 0xFF4D9BFF.toInt()),
        Option("saffron", R.string.colour_saffron, R.style.ThemeOverlay_Flikk_Saffron, 0xFFFF9933.toInt()),
        Option("purple", R.string.colour_purple, R.style.ThemeOverlay_Flikk_Purple, 0xFFB07CFF.toInt()),
        Option("cyan", R.string.colour_cyan, R.style.ThemeOverlay_Flikk_Cyan, 0xFF2EE6D6.toInt()),
    )

    private const val KEY = "accent"

    fun current(c: Context): Option {
        val k = c.getSharedPreferences(Prefs.SETTINGS, Context.MODE_PRIVATE).getString(KEY, "green")
        return options.firstOrNull { it.key == k } ?: options[0]
    }

    fun set(c: Context, key: String) {
        c.getSharedPreferences(Prefs.SETTINGS, Context.MODE_PRIVATE).edit().putString(KEY, key).apply()
    }

    fun apply(a: Activity) {
        a.theme.applyStyle(current(a).overlay, true)
    }

    fun accent(c: Context): Int {
        val tv = TypedValue()
        return if (c.theme.resolveAttribute(R.attr.flikkAccent, tv, true)) tv.data else current(c).color
    }
}
