package app.waylo.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch check's throttle. The bug it fixes: the stamp was written BEFORE the check ran, so a
 * check that failed (no network on a cold start, a rate-limited API) or was cut short when the
 * app closed silenced every launch for 20 hours, and the update card never appeared. Now only a
 * check that finished stamps the day.
 */
class LaunchCheckTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun `the switch off means no check`() {
        assertFalse(launchCheckDue(now, 0L, enabled = false))
    }

    @Test
    fun `no stamp yet is due`() {
        assertTrue(launchCheckDue(now, 0L, enabled = true))
    }

    @Test
    fun `a check a few hours ago is not due`() {
        assertFalse(launchCheckDue(now, now - 19 * hour, enabled = true))
    }

    @Test
    fun `a check a day ago is due`() {
        assertTrue(launchCheckDue(now, now - LAUNCH_CHECK_EVERY_MS, enabled = true))
    }

    @Test
    fun `a stamp from the future does not silence the check`() {
        assertTrue(launchCheckDue(now, now + 5 * hour, enabled = true))
    }
}
