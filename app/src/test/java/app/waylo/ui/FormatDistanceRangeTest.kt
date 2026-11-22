package app.waylo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDistanceRangeTest {

    @Test fun metricRoundsBothEndsToFiveKm() {
        Units.imperial.value = false
        assertEquals("40-60 km", formatDistanceRange(38_000.0, 61_000.0))
    }

    @Test fun aCollapsedRangeIsOneNumber() {
        Units.imperial.value = false
        assertEquals("5 km", formatDistanceRange(4_600.0, 5_200.0))
    }

    @Test fun imperialRoundsBothEndsToFiveMiles() {
        Units.imperial.value = true
        try {
            assertEquals("25-35 mi", formatDistanceRange(40_000.0, 56_000.0))
        } finally {
            Units.imperial.value = false
        }
    }
}
