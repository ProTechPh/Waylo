package app.waylo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.waylo.R
import app.waylo.core.data.LowDataMode
import app.waylo.core.data.weather.RouteWeather
import app.waylo.core.data.weather.RouteWeatherRepo
import app.waylo.core.model.Route
import app.waylo.core.model.TravelMode
import app.waylo.ui.place.DepartPick
import app.waylo.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One route's weather, under its card in the directions panel (SPEC 4.12). Null means there is
 * nothing to say: the switch is off, the drive is short, the answer did not come, or the drive is
 * past the forecast's reach. Never draws an error.
 */
@Composable
fun RouteWeatherLine(span: RouteWeather.Span?) {
    if (span == null) return
    val dim = SheetPalette.dim(isAppInDarkTheme())
    val range = formatDistanceRange(span.fromMeters, span.toMeters)
    val after = formatDuration(span.afterSeconds)
    val text = when (span.kind) {
        RouteWeather.Kind.NONE -> stringResource(R.string.route_weather_none)
        RouteWeather.Kind.LIGHT -> stringResource(R.string.route_weather_light_rain, range, after)
        RouteWeather.Kind.RAIN -> stringResource(R.string.route_weather_rain, range, after)
        RouteWeather.Kind.HEAVY -> stringResource(R.string.route_weather_heavy_rain, range, after)
        RouteWeather.Kind.SNOW -> stringResource(R.string.route_weather_snow, range, after)
        RouteWeather.Kind.MAYBE -> stringResource(R.string.route_weather_maybe, range, after, "${span.percent ?: 0}%")
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = dim)
}

/**
 * The line for every route in the panel, aligned with [routes]. One request for all of them; the
 * answer is cached by coordinates, so a re-timed route does not ask again. Runs off the main
 * thread: the panel draws its ETAs before this answers.
 */
@Composable
fun rememberRouteWeather(routes: List<Route>, mode: TravelMode, active: Route?): List<RouteWeather.Span?> {
    val on = RouteWeatherOn.on.value
    val departAt = remember(active?.summary, nowMinute()) { departAtMs(active) }
    return produceState<List<RouteWeather.Span?>>(List(routes.size) { null }, routes, departAt, on, mode) {
        value = if (mode != TravelMode.DRIVE || !on || routes.isEmpty()) List(routes.size) { null }
        else withContext(Dispatchers.IO) { loadRouteWeather(routes, departAt) }
    }.value
}

private fun nowMinute(): Long = System.currentTimeMillis() / 60_000L

private suspend fun loadRouteWeather(routes: List<Route>, departAtMs: Long): List<RouteWeather.Span?> {
    val now = System.currentTimeMillis()
    val perRoute = routes.map { RouteWeather.samples(it, departAtMs) }
    val used = ArrayList<Int>()
    var total = 0
    routes.indices.forEach { i ->
        val s = perRoute[i]
        if (s.isEmpty()) return@forEach
        val ok = RouteWeather.eligible(
            distanceMeters = routes[i].distanceMeters,
            departAtMs = s.first().atMs,
            arriveAtMs = s.last().atMs,
            nowMs = now,
            on = true,
            lowData = LowDataMode.enabled,
        )
        if (!ok) return@forEach
        if (total + s.size > RouteWeather.MAX_SAMPLES_TOTAL) return@forEach
        total += s.size
        used += i
    }
    if (used.isEmpty()) return List(routes.size) { null }
    val hours = RouteWeatherRepo.client.hourly(used.flatMap { perRoute[it] }) ?: return List(routes.size) { null }
    // A reply shorter than the request would throw on the slice; say nothing instead.
    if (hours.size < total) return List(routes.size) { null }

    val out = MutableList<RouteWeather.Span?>(routes.size) { null }
    var at = 0
    used.forEach { i ->
        val n = perRoute[i].size
        val span = RouteWeather.read(hours.subList(at, at + n), perRoute[i])
        out[i] = span ?: RouteWeather.Span(RouteWeather.Kind.NONE, 0.0, 0.0, 0.0, null)
        at += n
    }
    return out
}

/** The picked departure for the chooser's active route, else now. "Arrive by" is the pick minus
 *  the route's drive time. */
private fun departAtMs(active: Route?): Long {
    val now = System.currentTimeMillis()
    val pick = DepartPick.peek(active?.summary) ?: return now
    val picked = pick.date.atTime(pick.time)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    return when (pick.mode) {
        1 -> picked
        2 -> (picked - ((active?.durationInTrafficSeconds ?: active?.durationSeconds ?: 0.0) * 1000.0).toLong()).coerceAtLeast(now)
        else -> now
    }
}
