package app.waylo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkChoiceTest {
    private val split = listOf(
        "waylo-maps-all.apk", "waylo-maps-arm64.apk", "waylo-maps-armv7.apk",
        "waylo-maps-x86.apk", "waylo-maps-x86_64.apk",
    )
    private val arm64Phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test fun `each phone gets its own chip type`() {
        assertEquals("waylo-maps-arm64.apk", ApkChoice.pick(split, arm64Phone))
        assertEquals("waylo-maps-arm64.apk", ApkChoice.pick(split, listOf("arm64-v8a")))
        assertEquals("waylo-maps-armv7.apk", ApkChoice.pick(split, listOf("armeabi-v7a", "armeabi")))
        assertEquals("waylo-maps-x86_64.apk", ApkChoice.pick(split, listOf("x86_64", "x86", "arm64-v8a")))
        assertEquals("waylo-maps-x86.apk", ApkChoice.pick(split, listOf("x86")))
    }

    @Test fun `an unknown chip falls back to the all-in-one APK`() {
        assertEquals("waylo-maps-all.apk", ApkChoice.pick(split, listOf("riscv64")))
    }

    @Test fun `a release with one APK is taken whatever its name`() {
        assertEquals("waylo-maps-v0.4.1802.apk", ApkChoice.pick(listOf("waylo-maps-v0.4.1802.apk"), arm64Phone))
        assertEquals("waylo-maps-canary.apk", ApkChoice.pick(listOf("waylo-maps-canary.apk", "notes.txt"), arm64Phone))
        assertNull(ApkChoice.pick(listOf("notes.txt"), arm64Phone))
    }

    @Test fun `the canary names work the same way`() {
        val canary = listOf("waylo-maps-canary-all.apk", "waylo-maps-canary-arm64.apk", "waylo-maps-canary-armv7.apk")
        assertEquals("waylo-maps-canary-arm64.apk", ApkChoice.pick(canary, arm64Phone))
        assertEquals("waylo-maps-canary-all.apk", ApkChoice.pick(canary, listOf("x86_64")))
    }

    @Test fun `old updaters see the all-in-one APK first`() {
        // GitHub lists assets by name; an updater before ApkChoice takes the first .apk.
        assertEquals("waylo-maps-all.apk", split.sorted().first())
        assertEquals("waylo-maps-canary-all.apk", listOf("waylo-maps-canary-arm64.apk", "waylo-maps-canary-all.apk", "waylo-maps-canary-armv7.apk").sorted().first())
    }

    @Test fun `version codes fold onto the legacy scale`() {
        assertEquals(3802, legacyCode(3802))
        assertEquals(3810, legacyCode(38100))
        assertEquals(3810, legacyCode(38102))
        assertEquals(3810, legacyCode(38104))
        assertEquals(1, legacyCode(1))
    }
}
