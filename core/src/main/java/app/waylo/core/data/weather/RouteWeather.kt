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
}
