package app.waylo.core.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteWeatherReadTest {

    private val hour = 3_600_000L

    /** One location per sample, each carrying the same hourly arrays. */
    private fun hours(
        vararg mm: Double,
        pct: Int = 0,
        snow: Double = 0.0,
        temp: Double = 20.0,
    ): List<Hourly> = List(mm.size) {
        Hourly(
            time = LongArray(mm.size) { i -> i * 3_600L },
            precipitation = mm.toList().toDoubleArray(),
            probability = IntArray(mm.size) { pct },
            snowfall = DoubleArray(mm.size) { snow },
            temperature = DoubleArray(mm.size) { temp },
        )
    }

    /** Samples every 10 km, one per hour, starting at hour 0. */
    private fun samples(count: Int): List<RouteWeather.Sample> =
        List(count) { RouteWeather.Sample(38.5 + it * 0.001, -121.7, it * 10_000.0, it * hour) }

    @Test fun aWetHourAtTheArrivalHourKeepsTheSampleBounds() {
        val span = RouteWeather.read(hours(0.0, 0.6, 0.0), samples(3))
        assertEquals(RouteWeather.Kind.LIGHT, span?.kind)
        assertEquals(5_000.0, span!!.fromMeters, 1.0)
        assertEquals(15_000.0, span.toMeters, 1.0)
        assertEquals(3_600.0, span.afterSeconds, 1.0)
        assertNull(span.percent)
    }

    @Test fun theMiddleDrySampleSeparatesTwoSpans() {
        val span = RouteWeather.read(hours(1.0, 0.0, 9.0), samples(3))
        assertEquals(RouteWeather.Kind.HEAVY, span?.kind)
        assertEquals(15_000.0, span!!.fromMeters, 1.0)
        assertEquals(20_000.0, span.toMeters, 1.0)
        assertEquals(7_200.0, span.afterSeconds, 1.0)
    }

    @Test fun intensityWordsFollowWmo() {
        assertEquals(RouteWeather.Kind.LIGHT, RouteWeather.read(hours(0.5), samples(1))?.kind)
        assertEquals(RouteWeather.Kind.RAIN, RouteWeather.read(hours(5.0), samples(1))?.kind)
        assertEquals(RouteWeather.Kind.HEAVY, RouteWeather.read(hours(9.0), samples(1))?.kind)
    }

    @Test fun snowWinsBelowFreezing() {
        val span = RouteWeather.read(hours(1.0, snow = 0.4, temp = 0.5), samples(1))
        assertEquals(RouteWeather.Kind.SNOW, span?.kind)
    }

    @Test fun aMaybeHourNeedsTheProbabilityAndCarriesTheModelsOwnPercent() {
        assertEquals(70, RouteWeather.read(hours(0.0, pct = 70), samples(1))?.percent)
        assertEquals(70, RouteWeather.read(hours(0.0, pct = 67), samples(1))?.percent)
        assertNull(RouteWeather.read(hours(0.0, pct = 45), samples(1)))
    }

    @Test fun aWetSpanBeatsAMaybeSpan() {
        val span = RouteWeather.read(hours(0.0, 3.0, pct = 95), samples(2))
        assertEquals(RouteWeather.Kind.RAIN, span?.kind)
        assertNull(span?.percent)
    }

    @Test fun noAnswerIsNull() {
        assertNull(RouteWeather.read(emptyList(), samples(3)))
        assertNull(RouteWeather.read(hours(1.0, 1.0, 1.0), emptyList()))
    }

    @Test fun aDryRouteIsNull() {
        assertNull(RouteWeather.read(hours(0.0, 0.0, 0.0), samples(3)))
    }
}
