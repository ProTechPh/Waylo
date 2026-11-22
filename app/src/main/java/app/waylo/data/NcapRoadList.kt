package app.waylo.data

import android.content.Context
import app.waylo.core.nav.NcapRoads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The NCAP road list, held as data so a corrected list reaches phones without an app release
 * (the same shape as [FlockCameras]): a bundled floor in `assets/ncap_roads.tsv`, and a hosted copy
 * on the `ncap-ph` release that [refresh] swaps in when the manifest's version is newer.
 *
 * No agency publishes a machine-readable feed, so there is no cron fetching a source: the list is
 * reviewed in `tools/ncap-roads.tsv` and the `ncap-roads` workflow publishes it when that file
 * changes. Until the bundled floor has loaded, [NcapRoads] matches nothing, which under-reports
 * rather than guessing.
 */
object NcapRoadList {
    private const val BUNDLED = "ncap_roads.tsv"
    private const val BUNDLED_VER = "ncap_roads_version.txt"

    @Volatile private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "ncap").apply { mkdirs() }
    private fun downloadedFile(context: Context) = File(dir(context), "roads.tsv")
    private fun downloadedVer(context: Context) = File(dir(context), "version.txt")

    private fun bundledVersion(context: Context): Int = runCatching {
        context.assets.open(BUNDLED_VER).bufferedReader().use { it.readText() }.trim().toInt()
    }.getOrDefault(0)

    private fun downloadedVersion(context: Context): Int {
        if (!downloadedFile(context).exists() || !downloadedVer(context).exists()) return -1
        return downloadedVer(context).readText().trim().toIntOrNull() ?: -1
    }

    /** Newest available locally: max of the download and the bundled floor, so an app update can
     *  ship a list fresher than an old download. */
    private fun installedVersion(context: Context): Int =
        maxOf(downloadedVersion(context), bundledVersion(context))

    /** Parse the newest file on the phone into [NcapRoads], once, off the main thread. */
    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            val dl = downloadedFile(context)
            val useDownload = downloadedVersion(context) >= bundledVersion(context) && dl.exists()
            if (!useDownload && dl.exists()) {
                runCatching { dl.delete(); downloadedVer(context).delete() }
            }
            val list = runCatching {
                val text = if (useDownload) dl.readText()
                else context.assets.open(BUNDLED).bufferedReader().use { it.readText() }
                NcapRoads.parse(text.lineSequence())
            }.getOrNull() ?: return@withContext
            if (list.size == 0) return@withContext
            NcapRoads.install(list)
            loaded = true
        }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /**
     * Check the hosted manifest and, when it carries a newer version, download the list and install
     * it at once. Best effort: any network or parse failure keeps what is installed. Called once per
     * launch after [ensureLoaded].
     */
    suspend fun refresh(context: Context, manifestUrl: String) = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                .use { if (!it.isSuccessful) return@withContext; it.body?.string().orEmpty() }
            // org.json, not kotlinx.serialization: the :app module stays off kotlinx (a deliberate boundary).
            val obj = org.json.JSONObject(body)
            val version = obj.optInt("version", -1); if (version < 0) return@withContext
            val url = obj.optString("url", ""); if (url.isBlank()) return@withContext
            if (version <= installedVersion(context)) return@withContext
            val text = http.newCall(Request.Builder().url(url).build()).execute()
                .use { if (!it.isSuccessful) return@withContext; it.body?.string().orEmpty() }
            // Parse before touching the disk: a truncated download never replaces a good list.
            val list = NcapRoads.parse(text.lineSequence())
            if (list.size == 0) return@withContext
            val target = downloadedFile(context)
            val tmp = File(dir(context), "roads.tsv.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            downloadedVer(context).writeText(version.toString())
            NcapRoads.install(list)
            loaded = true
        }
        Unit
    }
}
