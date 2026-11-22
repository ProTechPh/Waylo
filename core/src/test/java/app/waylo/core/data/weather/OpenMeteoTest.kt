package app.waylo.core.data.weather

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoTest {

    private val fixture: String by lazy { javaClass.getResource("/weather/openmeteo-davis.json")!!.readText() }

    private fun samples(n: Int): List<RouteWeather.Sample> =
        List(n) { RouteWeather.Sample(38.55 + it * 0.01, -121.74, it * 10_000.0, it * 3_600_000L) }

    private class Fetcher(var body: String?) {
        var calls = 0
        suspend fun fetch(url: String): String? {
            calls++
            return body
        }
    }

    @Test fun urlCarriesEverySampleAndThreeDays() {
        val url = OpenMeteo.url(samples(2))
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url, url.contains("latitude=38.550,38.560"))
        assertTrue(url, url.contains("longitude=-121.740,-121.740"))
        assertTrue(url, url.contains("hourly=precipitation,precipitation_probability,snowfall,temperature_2m"))
        assertTrue(url, url.contains("forecast_days=3"))
        assertTrue(url, url.contains("timeformat=unixtime"))
        assertTrue(url, url.contains("timezone=GMT"))
    }

    @Test fun parseReadsTheListShapedReply() {
        val hours = OpenMeteo.parse(fixture)
        assertNotNull(hours)
        assertEquals(2, hours!!.size)
        hours.forEach { h ->
            assertTrue(h.time.size > 24)
            assertEquals(h.time.size, h.precipitation.size)
            assertEquals(h.time.size, h.probability.size)
            assertEquals(h.time.size, h.snowfall.size)
            assertEquals(h.time.size, h.temperature.size)
            assertTrue(h.time[0] > 1_700_000_000L)
            assertEquals(h.time[0] + 3_600L, h.time[1])
        }
    }

    @Test fun parseAnswersNullOnABrokenReply() {
        assertNull(OpenMeteo.parse("{"))
        assertNull(OpenMeteo.parse("[]"))
        assertNull(OpenMeteo.parse("{\"latitude\":1}"))
    }

    @Test fun oneFetchPerKeyInsideTheTtl() = runBlocking {
        val f = Fetcher(fixture)
        val now = 1_000_000L
        val c = RouteWeatherClient({ f.fetch(it) }, { now })
        assertNotNull(c.hourly(samples(2)))
        assertNotNull(c.hourly(samples(2)))
        assertEquals(1, f.calls)
    }

    @Test fun aNewKeyFetchesAgain() = runBlocking {
        val f = Fetcher(fixture)
        val c = RouteWeatherClient({ f.fetch(it) }, { 0L })
        c.hourly(samples(2))
        c.hourly(samples(3))
        assertEquals(2, f.calls)
    }

    @Test fun anExpiredEntryFetchesAgain() = runBlocking {
        val f = Fetcher(fixture)
        var now = 0L
        val c = RouteWeatherClient({ f.fetch(it) }, { now })
        c.hourly(samples(2))
        now = RouteWeatherClient.CACHE_TTL_MS + 1
        c.hourly(samples(2))
        assertEquals(2, f.calls)
    }

    @Test fun aFailureIsRetriedOnlyAfterTheGate() = runBlocking {
        val f = Fetcher(null)
        var now = 0L
        val c = RouteWeatherClient({ f.fetch(it) }, { now })
        assertNull(c.hourly(samples(2)))
        assertNull(c.hourly(samples(2)))
        assertEquals(1, f.calls)
        now = RouteWeatherClient.RETRY_AFTER_MS + 1
        assertNull(c.hourly(samples(2)))
        assertEquals(2, f.calls)
    }

    @Test fun aFailureIsNeverCachedAsAnAnswer() = runBlocking {
        val f = Fetcher(null)
        var now = 0L
        val c = RouteWeatherClient({ f.fetch(it) }, { now })
        assertNull(c.hourly(samples(2)))
        f.body = fixture
        now = RouteWeatherClient.RETRY_AFTER_MS + 1
        assertNotNull(c.hourly(samples(2)))
    }

    @Test fun moreThanTheCapEvictsTheOldest() = runBlocking {
        val f = Fetcher(fixture)
        val c = RouteWeatherClient({ f.fetch(it) }, { 0L })
        repeat(RouteWeatherClient.MAX_CACHED + 1) { c.hourly(samples(it + 1)) }
        val before = f.calls
        c.hourly(samples(1))
        assertEquals(before + 1, f.calls)
    }
}
