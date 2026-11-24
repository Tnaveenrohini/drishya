package com.drishya.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.CastMediaControlIntent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Chromecast / Android TV ki cast cheyyadaniki settings. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notif = NotificationOptions.Builder()
            .setTargetActivityClassName(PlayerActivity::class.java.name)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(notif)
                    .build()
            )
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object Cast {
    /** Play Services lekapothe null (konni phones lo undadu). */
    fun context(c: Context): CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(c.applicationContext)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Phone lo unna video ni TV chadavadaniki chinna HTTP server (same Wi-Fi lo matrame).
 * Random token unna link ki matrame file istundi.
 */
object LocalServer {
    private var server: ServerSocket? = null
    private val routes = ConcurrentHashMap<String, Pair<Uri, String>>()
    private var appCtx: Context? = null

    @Synchronized
    fun serve(c: Context, uri: Uri, mime: String): String? {
        appCtx = c.applicationContext
        val ip = wifiIp() ?: return null
        val s = server?.takeIf { !it.isClosed } ?: try {
            ServerSocket(0).also { ss ->
                server = ss
                thread(isDaemon = true, name = "flikk-cast") { loop(ss) }
            }
        } catch (_: Exception) {
            return null
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        routes.clear()
        routes[token] = uri to mime
        val ext = when {
            mime.contains("mp4") -> ".mp4"
            mime.contains("webm") -> ".webm"
            mime.contains("matroska") -> ".mkv"
            mime.startsWith("audio/") -> ".mp3"
            else -> ""
        }
        return "http://$ip:${s.localPort}/$token$ext"
    }

    @Synchronized
    fun stop() {
        routes.clear()
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun wifiIp(): String? {
        return try {
            val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            list.flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun loop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val sock = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            thread(isDaemon = true) {
                try {
                    handle(sock)
                } catch (_: Exception) {
                } finally {
                    try {
                        sock.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val req = reader.readLine() ?: return
        val parts = req.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) range = line.substringAfter(":").trim()
        }
        val out = sock.getOutputStream()
        val token = parts[1].trimStart('/').substringBefore('?').substringBefore('.')
        val route = routes[token]
        val ctx = appCtx
        if (route == null || ctx == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val (uri, mime) = route
        val cr = ctx.contentResolver
        var size = -1L
        try {
            cr.openAssetFileDescriptor(uri, "r")?.use { afd ->
                size = if (afd.length != AssetFileDescriptor.UNKNOWN_LENGTH) afd.length
                else afd.parcelFileDescriptor.statSize
            }
        } catch (_: Exception) {
        }

        var start = 0L
        var end = if (size > 0) size - 1 else -1L
        val partial = range != null && size > 0 && range.startsWith("bytes=")
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-').trim()
            if (a.isEmpty()) {
                // "bytes=-500" = chivari 500 bytes
                val n = b.toLongOrNull() ?: 0L
                start = (size - n).coerceAtLeast(0L)
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(size - 1)
            }
            if (start >= size) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
        }
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: $mime\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n")
        if (size > 0) sb.append("Content-Length: ${end - start + 1}\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/$size\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (method.equals("HEAD", true)) {
            out.flush()
            return
        }
        cr.openInputStream(uri)?.use { input ->
            var skip = start
            while (skip > 0) {
                val n = input.skip(skip)
                if (n <= 0) break
                skip -= n
            }
            var left = if (size > 0) end - start + 1 else Long.MAX_VALUE
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                left -= r
            }
        }
        out.flush()
    }
}
