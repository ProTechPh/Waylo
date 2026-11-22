package app.waylo.core.nav

import app.waylo.core.model.LatLng
import app.waylo.core.model.Maneuver
import app.waylo.core.model.ManeuverType
import app.waylo.core.model.RoadRename
import app.waylo.core.model.Route
import app.waylo.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The NCAP road match: a name from the baked list, held long enough to be a drive on it, inside
 *  the city that lists it. The table is data (`tools/ncap-roads.tsv`), so it is parsed here too. */
class NcapRoadsTest {

    private val manila = LatLng(14.60, 121.00)
    private val cebu = LatLng(10.31, 123.89)

    private val table = NcapRoads.parse(
        sequenceOf(
            "# fixture",
            "EDSA\tMM\tedsa",
            "EDSA\tMM\tepifanio de los santos avenue",
            "Quezon Avenue\tMM\tquezon avenue",
            "Aurora Boulevard\tMM\taurora boulevard",
            "Rizal Avenue\tMM\trizal avenue",
            "Kalayaan Avenue\tQC\tkalayaan avenue",
            "España Boulevard\tMNL\tespana boulevard",
            "España Boulevard\tMNL\tespaña boulevard",
            // Malformed rows the parser must drop rather than trust.
            "Nowhere Road\tXX\tnowhere road",
            "Only Two\tMM",
        ),
    )

    private fun route(at: LatLng, vararg steps: Pair<String?, Double>, renames: List<RoadRename> = emptyList()) =
        Route(
            polyline = listOf(at, at),
            legs = listOf(
                RouteLeg(
                    distanceMeters = steps.sumOf { it.second },
                    durationSeconds = 600.0,
                    durationInTrafficSeconds = null,
                    maneuvers = steps.map { (road, meters) ->
                        Maneuver(
                            type = ManeuverType.CONTINUE,
                            instruction = "Continue",
                            location = at,
                            distanceMeters = meters,
                            durationSeconds = 0.0,
                            road = road,
                            renames = if (road == steps.first().first) renames else emptyList(),
                        )
                    },
                ),
            ),
            distanceMeters = steps.sumOf { it.second },
            durationSeconds = 600.0,
            durationInTrafficSeconds = null,
        )

    @Test fun theParserKeepsTheGoodRowsOnly() {
        assertEquals(8, table.size)
    }

    @Test fun aLongStretchOnEdsaCounts() {
        assertEquals(listOf("EDSA"), table.roads(route(manila, "EDSA" to 3_000.0)))
    }

    @Test fun osmsSpelledOutNameCounts() {
        assertEquals(listOf("EDSA"), table.roads(route(manila, "Epifanio de los Santos Avenue" to 3_000.0)))
    }

    @Test fun crossingACoveredRoadDoesNotCount() {
        assertEquals(emptyList<String>(), table.roads(route(manila, "EDSA" to 40.0)))
    }

    @Test fun googleAbbreviationsMatch() {
        assertEquals(listOf("Quezon Avenue"), table.roads(route(manila, "Quezon Ave." to 1_200.0)))
    }

    @Test fun theSameRoadNameOutsideMetroManilaDoesNotCount() {
        assertEquals(emptyList<String>(), table.roads(route(cebu, "Rizal Avenue" to 3_000.0)))
    }

    @Test fun namesAreOrderedAndNotRepeated() {
        val r = route(manila, "EDSA" to 1_000.0, "Aurora Boulevard" to 1_000.0, "EDSA" to 1_000.0)
        assertEquals(listOf("EDSA", "Aurora Boulevard"), table.roads(r))
    }

    @Test fun aRenameMidLegCountsItsOwnStretch() {
        val r = route(manila, "Unnamed" to 5_000.0, renames = listOf(RoadRename(1_000.0, "Aurora Boulevard", null)))
        assertEquals(listOf("Aurora Boulevard"), table.roads(r))
    }

    @Test fun aShortRenameStretchDoesNotCount() {
        val r = route(manila, "Unnamed" to 5_000.0, renames = listOf(RoadRename(4_950.0, "Aurora Boulevard", null)))
        assertEquals(emptyList<String>(), table.roads(r))
    }

    @Test fun aCityRoadCountsInsideItsCity() {
        // Kalayaan Avenue is on Quezon City's list and the point is in QC.
        assertEquals(listOf("Kalayaan Avenue"), table.roads(route(LatLng(14.64, 121.06), "Kalayaan Avenue" to 1_500.0)))
    }

    @Test fun theSameCityRoadOutsideItsCityDoesNotCount() {
        // The Makati Kalayaan Avenue, same name, outside the QC box.
        assertEquals(emptyList<String>(), table.roads(route(LatLng(14.55, 121.02), "Kalayaan Avenue" to 1_500.0)))
    }

    @Test fun aManilaRoadCountsInManila() {
        assertEquals(listOf("España Boulevard"), table.roads(route(LatLng(14.61, 121.00), "España Blvd" to 900.0)))
    }

    @Test fun theRoadUnderTheCarFollowsTheManeuvers() {
        val r = route(manila, "EDSA" to 1_000.0, "Unnamed" to 1_000.0)
        assertEquals("EDSA", table.roadAt(r, 100.0))
        assertEquals(null, table.roadAt(r, 1_500.0))
    }

    @Test fun theInstalledTableIsWhatTheStaticsRead() {
        try {
            NcapRoads.install(table)
            assertEquals(8, NcapRoads.rowCount)
            assertEquals(listOf("EDSA"), NcapRoads.roads(route(manila, "EDSA" to 3_000.0)))
            assertEquals("EDSA", NcapRoads.roadAt(route(manila, "EDSA" to 3_000.0), 10.0))
        } finally {
            NcapRoads.install(NcapRoads.parse(emptySequence()))
        }
    }

    /** The list that ships in the APK: present, big enough to be the real one, and parseable. */
    @Test fun theBundledAssetIsAUsableList() {
        val f = File("../app/src/main/assets/ncap_roads.tsv")
        assumeTrue("bundled asset not visible from this working directory", f.exists())
        val bundled = NcapRoads.parse(f.readLines().asSequence())
        assertTrue("bundled list is too short: ${bundled.size}", bundled.size >= 40)
        assertEquals(
            listOf("EDSA"),
            bundled.roads(route(manila, "Epifanio de los Santos Avenue" to 3_000.0)),
        )
        // The newer city blocks are gated by their own boxes: Muntinlupa's National Road is not
        // the National Road anywhere else, and San Juan's N. Domingo only counts inside San Juan.
        assertEquals(
            listOf("National Road"),
            bundled.roads(route(LatLng(14.42, 121.04), "National Road" to 1_000.0)),
        )
        assertEquals(emptyList<String>(), bundled.roads(route(manila, "National Road" to 1_000.0)))
        assertEquals(
            listOf("N. Domingo Street"),
            bundled.roads(route(LatLng(14.61, 121.03), "N. Domingo Street" to 800.0)),
        )
    }
}
