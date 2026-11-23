package com.drishya.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumSet
import kotlin.math.min

/** PC / NAS lo "Share" chesina folder. */
data class SmbServer(
    val host: String,
    val share: String,
    val user: String = "",
    val pass: String = "",
    val domain: String = "",
) {
    val id get() = "$host/$share"
    val label get() = "$share  ·  $host"
}

data class SmbEntry(val name: String, val isDir: Boolean, val size: Long)

private val VIDEO_EXT = setOf(
    "mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "flv", "wmv", "3gp", "mpg", "mpeg"
)
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")

fun isMediaName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in VIDEO_EXT || ext in AUDIO_EXT
}

fun isAudioName(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

/** Saved servers (phone lone, prefs lo). */
object SmbStore {
    private const val NAME = "smb"
    private const val KEY = "servers"

    fun list(c: Context): List<SmbServer> {
        val raw = c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SmbServer(
                    o.getString("host"), o.getString("share"),
                    o.optString("user"), o.optString("pass"), o.optString("domain")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(c: Context, list: List<SmbServer>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("host", it.host).put("share", it.share)
                    .put("user", it.user).put("pass", it.pass).put("domain", it.domain)
            )
        }
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun save(c: Context, s: SmbServer) = write(c, list(c).filter { it.id != s.id } + s)

    fun delete(c: Context, id: String) = write(c, list(c).filter { it.id != id })

    fun find(c: Context, host: String, share: String) =
        list(c).firstOrNull { it.host.equals(host, true) && it.share.equals(share, true) }
}

/** smbj connections — okate connection prathi share ki reuse. */
object Smb {
    private val client by lazy { SMBClient() }
    private val shares = HashMap<String, DiskShare>()

    @Synchronized
    fun share(s: SmbServer): DiskShare {
        shares[s.id]?.let { if (it.isConnected) return it }
        val conn = client.connect(s.host)
        val auth = if (s.user.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(s.user, s.pass.toCharArray(), s.domain.ifBlank { null })
        val session = conn.authenticate(auth)
        val share = session.connectShare(s.share) as DiskShare
        shares[s.id] = share
        return share
    }

    @Synchronized
    fun forget(s: SmbServer) {
        try { shares.remove(s.id)?.close() } catch (_: Exception) {}
    }

    /** path "" = share root; "Movies/Telugu" = sub folder */
    fun list(s: SmbServer, path: String): List<SmbEntry> {
        val share = share(s)
        val dirFlag = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
        return share.list(path.replace('/', '\\'))
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .map { SmbEntry(it.fileName, (it.fileAttributes and dirFlag) != 0L, it.endOfFile) }
            .filter { it.isDir || isMediaName(it.name) }
            .sortedWith(compareBy<SmbEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    fun uriFor(s: SmbServer, path: String, name: String): Uri {
        val b = Uri.Builder().scheme("smb").authority(s.host).appendPath(s.share)
        path.split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
        b.appendPath(name)
        return b.build()
    }
}

/** ExoPlayer ki smb:// files chadhuvadaniki. */
@OptIn(UnstableApi::class)
class SmbDataSource(private val context: Context) : BaseDataSource(true) {
    private var file: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val host = dataSpec.uri.host ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val segs = dataSpec.uri.pathSegments
        if (segs.size < 2) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val server = SmbStore.find(context, host, segs[0]) ?: SmbServer(host, segs[0])
        val path = segs.drop(1).joinToString("\\")
        val f = try {
            Smb.share(server).openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        } catch (e: Exception) {
            Smb.forget(server)
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = f
        val size = f.fileInformation.standardInformation.endOfFile
        pos = dataSpec.position
        if (pos > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - pos
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: return C.RESULT_END_OF_INPUT
        val toRead = min(length.toLong(), remaining).toInt()
        val n = try {
            f.read(buffer, pos, offset, toRead)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { file?.close() } catch (_: Exception) {}
        file = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** smb:// ki SmbDataSource, migatha anni (content, file, http) ki DefaultDataSource. */
@OptIn(UnstableApi::class)
class FlikkDataSource(context: Context) : DataSource {
    private val default = DefaultDataSource(context, true)
    private val smb = SmbDataSource(context)
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == "smb") smb else default
        current = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        current?.close()
        current = null
    }
}
