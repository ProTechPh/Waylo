package app.waylo.core.data.weather

import app.waylo.core.model.Route
import app.waylo.core.nav.RouteProjection
import java.util.Locale

/**
 * The points along a driving route whose weather is asked for, and the rules that decide which
 * of them matters (SPEC 4.12). Pure logic: no Android types, no clock, no network.
 *
 * A sample carries the time the drive reaches it, so a forecast hour can be read where the car
 * will be, not where it is now. [samples] covers both ends of every route and grows the stride on
 * a long drive, so a 300 km trip is still sampled end to end inside [MAX_SAMPLES_PER_ROUTE].
 */
object RouteWeather {

    data class Sample(val lat: Double, val lng: Double, val meters: Double, val atMs: Long)

    /** A wet hour. Below this the models are noise. */
    const val WET_MM = 0.2

    /** An hour with no amount but a probability at or above this is "maybe". */
    const val MAYBE_PCT = 60

    /** WMO rain intensity words: light below [LIGHT_MM], heavy above [HEAVY_MM]. */
    const val LIGHT_MM = 2.5
    const val HEAVY_MM = 7.6

    /** Below freezing, snowfall is said as snow. */
    const val SNOW_C = 1.0

    /** A shorter drive explains itself. */
    const val MIN_ROUTE_M = 3_000.0

    /** Past the payload's reach there is nothing honest to say. */
    const val HORIZON_H = 72L

    const val SAMPLE_EVERY_M = 10_000.0
    const val MAX_SAMPLES_PER_ROUTE = 12
    const val MAX_SAMPLES_TOTAL = 36

    fun samples(route: Route, departAtMs: Long): List<Sample> {
        val poly = route.polyline
        if (poly.size < 2) return emptyList()
        val cum = RouteProjection.cumulative(poly)
        val total = cum.last()
        if (total <= 0.0) return emptyList()
        // One fewer than the cap leaves room for the end, so the samples span the whole route.
        val stride = maxOf(SAMPLE_EVERY_M, total / (MAX_SAMPLES_PER_ROUTE - 1))
        val points = ArrayList<Double>(MAX_SAMPLES_PER_ROUTE)
        var m = 0.0
        while (m < total - 1.0 && points.size < MAX_SAMPLES_PER_ROUTE - 1) {
            points += m
            m += stride
        }
        points += total
        val travelMs = (route.durationInTrafficSeconds ?: route.durationSeconds) * 1000.0
        return points.map { at ->
            val p = RouteProjection.pointAt(poly, cum, at)
            Sample(p.lat, p.lng, at, departAtMs + (travelMs * at / total).toLong())
        }
    }

    /** The coordinates alone: the cached forecast must survive a route that was only re-timed. */
    fun key(samples: List<Sample>): String =
        samples.joinToString(";") { String.format(Locale.US, "%.3f,%.3f", it.lat, it.lng) }

    fun eligible(
        distanceMeters: Double,
        departAtMs: Long,
        arriveAtMs: Long,
        nowMs: Long,
        on: Boolean,
        lowData: Boolean,
    ): Boolean = on && !lowData &&
        distanceMeters >= MIN_ROUTE_M &&
        departAtMs <= nowMs + HORIZON_H * 3_600_000L &&
        arriveAtMs <= nowMs + HORIZON_H * 3_600_000L

    enum class Kind { LIGHT, RAIN, HEAVY, SNOW, MAYBE }

    /** The one stretch of the route worth saying: its kind, where it is, how long after leaving it
     *  starts, and, for a chance of rain, the model's own percentage. */
    data class Span(
        val kind: Kind,
        val fromMeters: Double,
        val toMeters: Double,
        val afterSeconds: Double,
        val percent: Int?,
    )

    /**
     * The span to show for one route. Each [samples] entry pairs with the [hours] entry at the same
     * index (the caller slices the location list), and the weather read is the one at the hour the
     * drive reaches that point. A wet span beats a chance of rain; a dry answer is null.
     */
    fun read(hours: List<Hourly>, samples: List<Sample>): Span? {
        if (hours.isEmpty() || samples.isEmpty() || hours.size != samples.size) return null
        val peakMm = DoubleArray(samples.size)
        val pct = IntArray(samples.size)
        val kinds = arrayOfNulls<Kind>(samples.size)
        samples.forEachIndexed { i, s ->
            val h = hours[i]
            val idx = hourIndex(h, s.atMs / 1000L) ?: return@forEachIndexed
            val mm = h.precipitation.getOrElse(idx) { 0.0 }
            val p = h.probability.getOrElse(idx) { 0 }
            val sn = h.snowfall.getOrElse(idx) { 0.0 }
            val t = h.temperature.getOrElse(idx) { 99.0 }
            peakMm[i] = mm
            pct[i] = p
            kinds[i] = when {
                mm >= WET_MM && sn > 0.0 && t <= SNOW_C -> Kind.SNOW
                mm >= WET_MM -> when {
                    mm >= HEAVY_MM -> Kind.HEAVY
                    mm >= LIGHT_MM -> Kind.RAIN
                    else -> Kind.LIGHT
                }
                mm == 0.0 && p >= MAYBE_PCT -> Kind.MAYBE
                else -> null
            }
        }

        var bestWet: IntRange? = null
        var bestWetMm = 0.0
        var bestMaybe: IntRange? = null
        var bestMaybePct = 0
        var i = 0
        while (i < samples.size) {
            if (kinds[i] == null) { i++; continue }
            val maybe = kinds[i] == Kind.MAYBE
            var j = i
            while (j + 1 < samples.size && kinds[j + 1] != null && (kinds[j + 1] == Kind.MAYBE) == maybe) j++
            if (maybe) {
                val p = (i..j).maxOf { pct[it] }
                if (p > bestMaybePct) { bestMaybePct = p; bestMaybe = i..j }
            } else {
                val mm = (i..j).maxOf { peakMm[it] }
                if (mm > bestWetMm) { bestWetMm = mm; bestWet = i..j }
            }
            i = j + 1
        }
        val run = bestWet ?: bestMaybe ?: return null
        val first = run.first
        val last = run.last
        val from = if (first == 0) 0.0 else (samples[first - 1].meters + samples[first].meters) / 2
        val to = if (last == samples.lastIndex) samples.last().meters else (samples[last].meters + samples[last + 1].meters) / 2
        val kind = if (bestWet == null) Kind.MAYBE else kinds[run.maxByOrNull { peakMm[it] }!!]!!
        val percent = if (bestWet == null) ((bestMaybePct + 5) / 10 * 10).coerceAtMost(100) else null
        return Span(
            kind = kind,
            fromMeters = from,
            toMeters = to,
            afterSeconds = (samples[first].atMs - samples.first().atMs) / 1000.0,
            percent = percent,
        )
    }

    private fun hourIndex(h: Hourly, epochSec: Long): Int? {
        for (i in h.time.indices) {
            val t = h.time[i]
            if (epochSec >= t && epochSec < t + 3_600L) return i
        }
        return null
    }
}
