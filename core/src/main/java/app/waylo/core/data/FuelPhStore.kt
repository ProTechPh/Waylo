package app.waylo.core.data

import app.waylo.core.WayloConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * The Philippine fuel price file on the phone (SPEC 5.9), shaped like [FuelGbStore] for the UK
 * one. Nothing is fetched until the app asks, which it does only when a Philippine gas station is
 * opened. Then the file on disk is parsed if nothing is in memory, and at most every
 * [CHECK_EVERY_MS] (a day: the DOE publishes weekly) the manifest is read and the data file is
 * downloaded only when the manifest names a different one. A failure is retried after
 * [RETRY_AFTER_MS] and never replaces a good file. [dir] is read on every access.
 */
class FuelPhStore(
    private val dir: () -> File,
    private val http: OkHttpClient,
    private val baseUrl: String = BASE_URL,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        val BASE_URL: String get() = app.waylo.core.config.WayloLinks.fuelPh()
        const val FOLDER = "fuelph"
        const val DATA_FILE = "fuel-ph.csv.gz"
        const val MANIFEST_FILE = "fuel-ph-manifest.json"
        private const val CHECKED_FILE = "checked"
        const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000
        const val RETRY_AFTER_MS = 15L * 60 * 1000
        private val FILE_NAME = Regex("[A-Za-z0-9._-]+")
    }

    private val mutex = Mutex()
    @Volatile private var data: FuelPhData? = null
    @Volatile private var failedAtMs = 0L

    /** The rows in memory, or null until [ensure] has loaded them. */
    val current: FuelPhData? get() = data

    /** Drop the parsed rows (memory pressure). The next [ensure] reads the file again. */
    fun release() { data = null }

    /** Load the file from disk and fetch a newer one when due. True when [current] changed. */
    suspend fun ensure(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { ensureLocked() }.getOrDefault(false) }
    }

    private fun ensureLocked(): Boolean {
        val d = dir().apply { mkdirs() }
        val file = File(d, DATA_FILE)
        var changed = false
        if (data == null && file.exists()) {
            data = runCatching { file.inputStream().use { FuelPh.parseGzip(it) } }.getOrNull()?.takeIf { it.size > 0 }
            changed = data != null
        }
        val now = nowMs()
        val checked = File(d, CHECKED_FILE)
        val lastCheck = runCatching { checked.readText().trim().toLong() }.getOrDefault(0L)
        if (file.exists() && data != null && now - lastCheck in 0 until CHECK_EVERY_MS) return changed
        if (now - failedAtMs in 0 until RETRY_AFTER_MS) return changed

        val remoteText = get(baseUrl.trimEnd('/') + "/" + MANIFEST_FILE)?.toString(Charsets.UTF_8)
        val remote = remoteText?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (remote == null) { failedAtMs = now; return changed }
        val localManifest = File(d, MANIFEST_FILE)
        val local = runCatching { Json.parseToJsonElement(localManifest.readText()).jsonObject }.getOrNull()
        val remoteSha = remote.str("fileSha256")
        if (file.exists() && data != null && remoteSha != null && remoteSha == local?.str("fileSha256")) {
            checked.writeText(now.toString())
            return changed
        }
        val name = remote.str("file")?.takeIf { it.matches(FILE_NAME) } ?: DATA_FILE
        val bytes = get(baseUrl.trimEnd('/') + "/" + name)
        if (bytes == null || bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte() ||
            (remoteSha != null && sha256(bytes) != remoteSha)
        ) { failedAtMs = now; return changed }
        val parsed = runCatching { FuelPh.parseGzip(ByteArrayInputStream(bytes)) }.getOrNull()
        if (parsed == null || parsed.size == 0) { failedAtMs = now; return changed }

        val tmp = File(d, "$DATA_FILE.tmp").apply { writeBytes(bytes) }
        val old = File(d, "$DATA_FILE.old").apply { delete() }
        if (file.exists() && !file.renameTo(old)) { tmp.delete(); failedAtMs = now; return changed }
        if (!tmp.renameTo(file)) {
            old.renameTo(file); tmp.delete(); failedAtMs = now; return changed
        }
        old.delete()
        localManifest.writeText(remoteText)
        checked.writeText(now.toString())
        data = parsed
        return true
    }

    private fun get(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", WayloConfig.WAYLO_UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
