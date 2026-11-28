package com.drishya.player

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** App crash ayithe report save chestundi — next open lo user ki chupinchi share cheyyochu. */
class FlikkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                CrashLog.save(this, t, e)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }
}

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun save(c: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        file(c).writeText(
            "Flikk Play ${info.versionName} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.joinToString()}\nThread: ${t.name}\n\n" +
                sw.toString().take(6000)
        )
    }

    fun text(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return "Flikk Play · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL} · " +
            "${Build.SUPPORTED_ABIS.joinToString()}\n\n" + sw.toString().take(4000)
    }

    /** Okkasari chadivi delete. */
    fun take(c: Context): String? {
        val f = file(c)
        if (!f.exists()) return null
        val s = f.readText()
        f.delete()
        return s
    }
}
