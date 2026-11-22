package app.waylo.core.data.weather

import app.waylo.core.WayloConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One location's hourly forecast, the four arrays Open-Meteo answers with. `time` is epoch seconds. */
class Hourly(
    val time: LongArray,
    val precipitation: DoubleArray,
    val probability: IntArray,
    val snowfall: DoubleArray,
    val temperature: DoubleArray,
)

/**
 * The Open-Meteo forecast request (SPEC 4.12). Keyless and free for non-commercial use, CC BY 4.0;
 * one call carries every sample point as a comma separated coordinate list, and the reply to that
 * is a JSON list, one entry per point in the same order.
 *
 * The forecast comes at the hour the drive reaches each point, which is why the request goes by
 * coordinates and [RouteWeather.read] does the hour picking on the phone.
 */
object OpenMeteo {
    const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
    private const val HOURLY = "precipitation,precipitation_probability,snowfall,temperature_2m"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    fun url(samples: List<RouteWeather.Sample>): String {
        val lat = samples.joinToString(",") { String.format(Locale.US, "%.3f", it.lat) }
        val lon = samples.joinToString(",") { String.format(Locale.US, "%.3f", it.lng) }
        return "$BASE_URL?latitude=$lat&longitude=$lon&hourly=$HOURLY" +
            "&forecast_days=3&timeformat=unixtime&timezone=GMT"
    }

    /** The reply for the requested points, in request order, or null on any shape it does not know. */
    fun parse(json: String): List<Hourly>? = runCatching {
        val root = Json.parseToJsonElement(json)
        val objects = when (root) {
            is JsonArray -> root.map { it.jsonObject }
            is JsonObject -> listOf(root)
            else -> return null
        }
        val out = objects.map { o ->
            val h = o["hourly"]?.jsonObject ?: return null
            val time = h["time"]?.jsonArray?.map { it.jsonPrimitive.long } ?: return null
            val mm = h["precipitation"]?.jsonArray?.map { it.jsonPrimitive.double } ?: return null
            val pct = h["precipitation_probability"]?.jsonArray?.map { it.jsonPrimitive.int } ?: return null
            val snow = h["snowfall"]?.jsonArray?.map { it.jsonPrimitive.double } ?: return null
            val temp = h["temperature_2m"]?.jsonArray?.map { it.jsonPrimitive.double } ?: return null
            Hourly(time.toLongArray(), mm.toDoubleArray(), pct.toIntArray(), snow.toDoubleArray(), temp.toDoubleArray())
        }
        out.takeIf { list ->
            list.isNotEmpty() && list.all { it.time.size > 0 && it.precipitation.size == it.time.size && it.probability.size == it.time.size }
        }
    }.getOrNull()

    fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", WayloConfig.WAYLO_UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()
}

/**
 * The cache in front of [OpenMeteo], in memory only. Keyed by [RouteWeather.key], so a route set
 * that was only re-timed keeps its forecast. A failed request is retried after a gate and is never
 * remembered as an empty answer.
 */
class RouteWeatherClient(
    private val fetch: suspend (String) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val CACHE_TTL_MS = 15L * 60 * 1000
        const val RETRY_AFTER_MS = 10L * 60 * 1000
        const val MAX_CACHED = 4
    }

    private class Entry(val hours: List<Hourly>, val atMs: Long)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Entry>()
    private var failedAtMs: Long? = null

    suspend fun hourly(samples: List<RouteWeather.Sample>): List<Hourly>? = mutex.withLock {
        if (samples.isEmpty()) return null
        val key = RouteWeather.key(samples)
        val now = nowMs()
        cache[key]?.let { hit ->
            if (now - hit.atMs < CACHE_TTL_MS) return hit.hours
            cache.remove(key)
        }
        val failedAt = failedAtMs
        if (failedAt != null && now - failedAt in 0 until RETRY_AFTER_MS) return null
        val body = fetch(OpenMeteo.url(samples))
        val hours = body?.let { OpenMeteo.parse(it) }
        if (hours == null) {
            failedAtMs = now
            return null
        }
        cache[key] = Entry(hours, now)
        while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
        hours
    }
}

object RouteWeatherRepo {
    val client: RouteWeatherClient by lazy { RouteWeatherClient({ OpenMeteo.get(it) }) }
}
