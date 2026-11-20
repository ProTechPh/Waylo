package app.waylo.core.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WayloLinksTest {
    @After fun reset() {
        WayloLinks.overrides = emptyMap()
        WayloLinks.compiled = emptyMap()
    }

    @Test fun `defaults derive the files from their bases`() {
        assertEquals("https://github.com/ProTechPh/Waylo/releases/download/obf-regions/obf-manifest.json", WayloLinks.obfManifest())
        assertEquals("https://raw.githubusercontent.com/ProTechPh/Waylo/main/calibration.json", WayloLinks.calibration())
        assertEquals(WayloLinks.calibration() + ".sig", WayloLinks.calibrationSig())
        assertEquals("https://protechph.github.io/Waylo/calibration.json", WayloLinks.calibrationMirror())
        assertEquals("https://protechph.github.io/Waylo/fonts", WayloLinks.mapFonts())
        assertEquals("https://protechph.github.io/Waylo", WayloLinks.site())
        // The website and the Pages address are separate keys: moving the site leaves the fonts.
        WayloLinks.overrides = mapOf(WayloLinks.SITE to "https://waylo.example")
        assertEquals("https://protechph.github.io/Waylo/fonts", WayloLinks.mapFonts())
        assertEquals("https://github.com/ProTechPh/Waylo/releases/download/voices/x.tar.bz2", WayloLinks.release("voices", "x.tar.bz2"))
        assertEquals("https://api.github.com/repos/ProTechPh/Waylo/releases/latest", WayloLinks.api("/releases/latest"))
    }

    @Test fun `a pushed base moves every file under it, and a pushed key moves that one`() {
        WayloLinks.overrides = mapOf(WayloLinks.RELEASES to "https://github.com/NewName/Waylo/releases/download")
        assertEquals("https://github.com/NewName/Waylo/releases/download/obf-regions/obf-manifest.json", WayloLinks.obfManifest())
        assertEquals("https://github.com/NewName/Waylo/releases/download/fuel-gb/", WayloLinks.fuelGb())
        assertEquals("https://github.com/ProTechPh/Waylo", WayloLinks.repo())
        WayloLinks.overrides = mapOf(WayloLinks.RAW to "https://example.org/waylo", WayloLinks.PRIVACY to "https://example.org/privacy")
        assertEquals("https://example.org/waylo/calibration.json", WayloLinks.calibration())
        assertEquals("https://example.org/privacy", WayloLinks.privacy())
    }

    @Test fun `a pushed link loses its trailing slash and must be printable ascii`() {
        WayloLinks.overrides = mapOf(WayloLinks.RAW to "https://example.org/waylo/")
        assertEquals("https://example.org/waylo/calibration.json", WayloLinks.calibration())
        assertEquals(true, WayloLinks.acceptable(mapOf(WayloLinks.REPO to "https://example.org/Waylo")))
        assertEquals(false, WayloLinks.acceptable(mapOf(WayloLinks.REPO to "https://véla.example")))
        assertEquals(false, WayloLinks.acceptable(mapOf(WayloLinks.REPO to "https://example.org/a b")))
        assertEquals(false, WayloLinks.acceptable(mapOf("nope" to "https://example.org")))
    }

    @Test fun `a build's own link beats the default and loses to a pushed one`() {
        WayloLinks.compiled = mapOf(WayloLinks.OBF_MANIFEST to "http://127.0.0.1:8099/obf-manifest.json", WayloLinks.CELLS_MANIFEST to "")
        assertEquals("http://127.0.0.1:8099/obf-manifest.json", WayloLinks.obfManifest())
        assertEquals(WayloLinks.default(WayloLinks.CELLS_MANIFEST), WayloLinks.cellsManifest())
        WayloLinks.overrides = mapOf(WayloLinks.OBF_MANIFEST to "https://example.org/obf.json")
        assertEquals("https://example.org/obf.json", WayloLinks.obfManifest())
    }

    @Test fun `a bundle may push https links under known names only`() {
        assertTrue(WayloLinks.acceptable(mapOf(WayloLinks.SITE to "https://waylo.example")))
        assertFalse(WayloLinks.acceptable(mapOf(WayloLinks.SITE to "http://waylo.example")))
        assertFalse(WayloLinks.acceptable(mapOf("somethingElse" to "https://waylo.example")))
        assertFalse(WayloLinks.acceptable(mapOf(WayloLinks.SITE to "https://waylo.example/a b")))
        val c = CalibrationStore.parseBundle("""{"version": 99, "links": {"site": "https://waylo.example", "repo": ""}}""")!!
        assertEquals(mapOf("site" to "https://waylo.example"), c.links)
    }
}
