package app.waylo.core.nav

import app.waylo.core.model.LatLng
import app.waylo.core.model.Route

/**
 * The roads the MMDA and the Metro Manila cities enforce by camera under the No Contact
 * Apprehension Policy (NCAP), matched against the names a route already carries.
 *
 * The list is DATA, not code: `tools/ncap-roads.tsv` is the source, baked into
 * `assets/ncap_roads.tsv`, published to the `ncap-ph` release and refreshed in the app on launch
 * (`NcapRoadList`), so a corrected list reaches phones without an app release. The agencies
 * publish covered roads, never camera positions; the list of camera locations that circulated in
 * 2025 was debunked as fake. So the only honest statement a route can make is "this drive runs on
 * a road NCAP watches", never "there is a camera at this corner".
 *
 * A stretch counts at [MIN_SPAN_M] or more on the named road: crossing EDSA at a junction is a few
 * dozen meters and is not driving on it. Names come from the route's own maneuvers, so a route
 * whose street names fell short under-reports rather than guesses. A city's roads only match inside
 * that city, because "Rizal Avenue" and "13th Avenue" exist in several of them.
 */
class NcapRoads(private val rows: List<Row>) {

    /** One alias of one road: [city] is `MM` (an MMDA road, all of Metro Manila), `QC` or `MNL`. */
    data class Row(val road: String, val city: String, val alias: String)

    /** How many alias rows are in force. */
    val size: Int get() = rows.size

    /** The NCAP-covered roads this drive uses, in the order met. Empty outside Metro Manila. */
    fun roads(route: Route): List<String> {
        if (!touchesMetroManila(route)) return emptyList()
        val out = LinkedHashSet<String>()
        for (m in route.maneuvers) {
            // The name in force from each point: the maneuver's own road, then each rename.
            val starts = ArrayList<Pair<Double, String?>>(m.renames.size + 1)
            starts.add(0.0 to m.road)
            for (r in m.renames) starts.add(r.atMeters to r.road)
            for (i in starts.indices) {
                val (from, name) = starts[i]
                val to = starts.getOrNull(i + 1)?.first ?: m.distanceMeters
                if (to - from < MIN_SPAN_M) continue
                val road = match(name, m.location)
                if (road != null) out.add(road)
            }
        }
        return out.toList()
    }

    /** The NCAP-covered road under the car after [traveledM] meters, or null. One answer per tick,
     *  for the alert while driving; [roads] is the per-route list for the pickers. */
    fun roadAt(route: Route, traveledM: Double): String? {
        var start = 0.0
        for (m in route.maneuvers) {
            val end = start + m.distanceMeters
            if (traveledM < end) {
                // The car's own point decides the city, not the whole line: one check per tick.
                if (!METRO_MANILA.holds(m.location)) return null
                var name = m.road
                for (r in m.renames) if (traveledM >= start + r.atMeters) name = r.road
                return match(name, m.location)
            }
            start = end
        }
        return null
    }

    private fun match(name: String?, at: LatLng): String? {
        val n = normalize(name ?: return null)
        if (n.isEmpty()) return null
        return rows.firstOrNull { row ->
            val city = CITY_BOX[row.city]
            (city == null || city.holds(at)) && n.contains(row.alias)
        }?.road
    }

    companion object {

        /** How long a stretch on a named road must be before the route is said to use it. */
        const val MIN_SPAN_M = 200.0

        private data class Box(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
            fun holds(p: LatLng): Boolean = p.lat in minLat..maxLat && p.lng in minLng..maxLng
        }

        // Coarse boxes, not boundaries: Metro Manila for the MMDA roads, and each city whose own
        // NCAP list is published. Only a route that touches one is checked at all.
        private val METRO_MANILA = Box(14.35, 14.80, 120.90, 121.13)
        private val CITY_BOX = mapOf(
            "QC" to Box(14.58, 14.79, 120.97, 121.12),
            "MNL" to Box(14.55, 14.64, 120.95, 121.02),
            "SJ" to Box(14.595, 14.635, 121.020, 121.055),
            "MUNTI" to Box(14.35, 14.46, 120.99, 121.10),
        )
        private val CITIES = setOf("MM") + CITY_BOX.keys

        private val EMPTY = NcapRoads(emptyList())

        @Volatile private var installed: NcapRoads = EMPTY

        /** The rows in force, for the diagnostic and the loader's empty check. */
        val rowCount: Int get() = installed.rows.size

        /** Swap in a freshly parsed list (the loader calls this at launch and after a refresh). */
        fun install(list: NcapRoads) {
            installed = list
        }

        fun roads(route: Route): List<String> = installed.roads(route)

        fun roadAt(route: Route, traveledM: Double): String? = installed.roadAt(route, traveledM)

        /** The baked TSV (`road<TAB>city<TAB>alias`): `#` lines and malformed rows are skipped. */
        fun parse(lines: Sequence<String>): NcapRoads {
            val rows = ArrayList<Row>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size != 3) continue
                val road = parts[0].trim()
                val city = parts[1].trim()
                val alias = parts[2].trim()
                if (road.isEmpty() || alias.isEmpty() || city !in CITIES) continue
                rows.add(Row(road, city, normalize(alias)))
            }
            return NcapRoads(rows)
        }

        private fun touchesMetroManila(route: Route): Boolean = route.polyline.any { METRO_MANILA.holds(it) }

        /** Lowercase, punctuation to spaces, and the abbreviations Google and OSM mix. */
        private val ABBREV = mapOf("ave" to "avenue", "blvd" to "boulevard", "hwy" to "highway")

        private fun normalize(name: String): String = name.lowercase()
            .replace(Regex("[.,'’-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { ABBREV[it] ?: it }
    }
}
