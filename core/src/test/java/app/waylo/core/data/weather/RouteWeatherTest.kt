package app.waylo.core.data.weather

import app.waylo.core.model.LatLng
import app.waylo.core.model.Route
import app.waylo.core.nav.RouteProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteWeatherTest {

    private fun line(lengthM: Double): List<LatLng> =
        listOf(LatLng(38.5, -121.7), LatLng(38.5 + lengthM / 111_320.0, -121.7))

    private fun routeOf(lengthM: Double, minutes: Double, inTrafficMinutes: Double? = null): Route = Route(
        polyline = line(lengthM),
        legs = emptyList(),
        distanceMeters = lengthM,
        durationSeconds = minutes * 60,
        durationInTrafficSeconds = inTrafficMinutes?.times(60),
        summary = "test",
    )

    private fun measured(r: Route): Double = RouteProjection.cumulative(r.polyline).last()

    @Test fun a60KmDriveSamplesEvery10KmIncludingBothEnds() {
        val r = routeOf(lengthM = 60_000.0, minutes = 60.0)
        val s = RouteWeather.samples(r, departAtMs = 0L)
        assertEquals(7, s.size)
        assertEquals(0.0, s.first().meters, 1.0)
        assertEquals(measured(r), s.last().meters, 1.0)
        s.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b.meters - a.meters}", b.meters - a.meters in 9_900.0..10_100.0) }
        assertEquals(600_000.0, s[1].atMs.toDouble(), 5_000.0)
    }

    @Test fun a300KmDriveKeeps12SamplesAndBothEndsCovered() {
        val r = routeOf(lengthM = 300_000.0, minutes = 180.0)
        val s = RouteWeather.samples(r, departAtMs = 0L)
        assertEquals(12, s.size)
        assertEquals(0.0, s.first().meters, 1.0)
        assertEquals(measured(r), s.last().meters, 1.0)
        s.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b.meters - a.meters}", b.meters - a.meters in 20_000.0..30_000.0) }
    }

    @Test fun durationInTrafficWinsOverDuration() {
        val r = routeOf(lengthM = 60_000.0, minutes = 60.0, inTrafficMinutes = 90.0)
        val s = RouteWeather.samples(r, departAtMs = 0L)
        assertEquals(5_400_000.0, s.last().atMs.toDouble(), 20_000.0)
    }

    @Test fun theKeyIgnoresDurationsAndCatchesMovedCoordinates() {
        val a = RouteWeather.samples(routeOf(60_000.0, 60.0), 0L)
        val b = RouteWeather.samples(routeOf(60_000.0, 95.0), 0L)
        val shifted = RouteWeather.samples(
            Route(
                polyline = listOf(LatLng(38.6, -121.7), LatLng(38.6 + 60_000.0 / 111_320.0, -121.7)),
                legs = emptyList(),
                distanceMeters = 60_000.0,
                durationSeconds = 3_600.0,
                durationInTrafficSeconds = null,
            ),
            0L,
        )
        assertEquals(RouteWeather.key(a), RouteWeather.key(b))
        assertNotEquals(RouteWeather.key(a), RouteWeather.key(shifted))
    }

    @Test fun eligibility() {
        val now = 1_000_000_000_000L
        assertFalse(RouteWeather.eligible(2_000.0, now, now, now, on = true, lowData = false))
        assertFalse(RouteWeather.eligible(50_000.0, now, now, now, on = false, lowData = false))
        assertFalse(RouteWeather.eligible(50_000.0, now, now, now, on = true, lowData = true))
        assertFalse(RouteWeather.eligible(50_000.0, now + 73 * 3_600_000L, now + 74 * 3_600_000L, now, on = true, lowData = false))
        assertTrue(RouteWeather.eligible(50_000.0, now, now + 3_600_000L, now, on = true, lowData = false))
    }
}
