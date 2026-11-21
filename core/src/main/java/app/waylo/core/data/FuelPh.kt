package app.waylo.core.data

import app.waylo.core.model.Place
import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Philippine fuel prices (SPEC 5.9). Google's keyless search carries a price for US gas stations
 * ([Place.fuelPrice]) and none for the Philippines, and the UK's Fuel Finder covers only Great
 * Britain and Northern Ireland. The Department of Energy publishes a weekly table per field
 * office instead, and it is not per station: each city or municipality gets a price RANGE and a
 * COMMON price per product. So a Philippine gas station shows its city's DOE price, and the text
 * says so — it is never a claim about that station's own pump.
 *
 * File format, gzipped CSV with a header:
 * `area,region,product,low,high,common,weekStart,weekEnd`. Prices are pesos per litre, [common]
 * blank when the sheet names none, the week as ISO dates. Columns are read by name.
 */
object FuelPh {
    /** The country the DOE sheet covers ([Place.countryCode]). */
    const val COUNTRY = "PH"

    /** Products shown on the sheet, in the order they are tried: the two a driver takes. */
    private val PETROL = listOf("RON 95", "RON 91", "RON 97")
    private const val DIESEL = "Diesel"

    /** A gas station in the Philippines with no city price yet. */
    fun wants(place: Place, byCategory: (Place) -> Boolean): Boolean =
        place.fuelPh == null && place.countryCode.equals(COUNTRY, ignoreCase = true) &&
            FuelGb.isGasStation(place, byCategory)

    fun parseGzip(input: InputStream): FuelPhData =
        GZIPInputStream(input, 1 shl 16).bufferedReader().use { parse(it) }

    /** Rows with a bad price are skipped. A missing column reads as blank. */
    fun parse(reader: Reader): FuelPhData {
        val br = reader as? BufferedReader ?: BufferedReader(reader)
        val header = br.readLine()?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }
            ?: return FuelPhData.EMPTY
        fun col(name: String) = header.indexOf(name)
        val cArea = col("area"); val cProduct = col("product")
        val cLow = col("low"); val cHigh = col("high"); val cCommon = col("common")
        val cStart = col("weekstart"); val cEnd = col("weekend")
        if (cArea < 0 || cProduct < 0 || cLow < 0 || cHigh < 0) return FuelPhData.EMPTY
        val rows = ArrayList<FuelPhRow>(1024)
        while (true) {
            val line = br.readLine() ?: break
            if (line.isBlank()) continue
            val f = line.split(',')
            fun at(i: Int) = if (i in f.indices) f[i].trim() else ""
            val area = at(cArea).takeIf { it.isNotEmpty() } ?: continue
            val product = at(cProduct).takeIf { it.isNotEmpty() } ?: continue
            val low = centavos(at(cLow)) ?: continue
            val high = centavos(at(cHigh)) ?: continue
            if (low > high) continue
            rows += FuelPhRow(
                area = area,
                full = full(area),
                bare = bare(area),
                product = product,
                low = low,
                high = high,
                common = centavos(at(cCommon)),
                weekStart = at(cStart),
                weekEnd = at(cEnd),
            )
        }
        return FuelPhData(rows)
    }

    /** "92.10" -> 9210 (centavos); blank or junk -> null. */
    private fun centavos(s: String): Int? =
        s.toDoubleOrNull()?.takeIf { it > 0 }?.let { Math.round(it * 100).toInt() }

    /** "92.10" for 9210. */
    fun formatPeso(centavos: Int): String = String.format(Locale.US, "%.2f", centavos / 100.0)

    /** Lowercased, punctuation read as spaces: "Cagayan de Oro City" and "CAGAYAN DE ORO" match. */
    fun full(area: String): String = area.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /** [full] without the trailing "City"/"Municipality" an address often leaves out. */
    fun bare(area: String): String = full(area).replace(Regex("\\s+(city|municipality|municipal)$"), "").trim()

    /**
     * The sheet text for [address], or null: the city's diesel and petrol price plus a line that
     * says what it is. The longest area name the address contains wins ("Cagayan de Oro" over
     * "Cagayan"); a bare name two published areas share is skipped rather than guessed.
     */
    fun text(data: FuelPhData, address: String): String? {
        val rows = data.rowsFor(address) ?: return null
        val diesel = rows.firstOrNull { it.product == DIESEL }
            ?: rows.firstOrNull { it.product == "Diesel Plus" }
        val petrol = PETROL.firstNotNullOfOrNull { p -> rows.firstOrNull { it.product == p } }
        val parts = listOfNotNull(
            diesel?.let { "Diesel \u20B1${formatPeso(it.common ?: it.low)}" },
            petrol?.let { "${it.product} \u20B1${formatPeso(it.common ?: it.low)}" },
        )
        if (parts.isEmpty()) return null
        val area = (diesel ?: petrol)!!.area
        val note = listOfNotNull(week(diesel ?: petrol!!)?.let { "DOE $area, week of $it" }, "city price, not this station's")
            .joinToString(" \u00B7 ")
        return parts.joinToString(" \u00B7 ") + "\n" + note
    }

    private val MONTH = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** "Nov 10-16" from the row's week, or null when the dates do not read. */
    private fun week(row: FuelPhRow): String? = runCatching {
        val a = LocalDate.parse(row.weekStart)
        val b = LocalDate.parse(row.weekEnd)
        val sameMonth = a.month == b.month
        (if (sameMonth) a.format(MONTH) + "\u2013" + b.dayOfMonth else a.format(MONTH) + " \u2013 " + b.format(MONTH))
    }.getOrNull()

    /** [place] with its city price filled in, or the same instance when there is nothing to fill
     *  (not a Philippine gas station, no city the sheet names, or it already has one). */
    fun annotate(place: Place, data: FuelPhData, byCategory: (Place) -> Boolean): Place {
        if (!wants(place, byCategory)) return place
        val text = text(data, place.address ?: return place) ?: return place
        return place.copy(fuelPh = text)
    }

    /** [places] with every Philippine gas station's city price filled in; the same list otherwise. */
    fun annotateAll(places: List<Place>, data: FuelPhData, byCategory: (Place) -> Boolean): List<Place> {
        var out: MutableList<Place>? = null
        for (k in places.indices) {
            val p = places[k]
            val q = annotate(p, data, byCategory)
            if (q !== p) {
                if (out == null) out = places.toMutableList()
                out[k] = q
            }
        }
        return out ?: places
    }
}

/** One DOE row: an area's range and common price for one product in one week. Centavos. */
data class FuelPhRow(
    val area: String,
    val full: String,
    val bare: String,
    val product: String,
    val low: Int,
    val high: Int,
    val common: Int?,
    val weekStart: String,
    val weekEnd: String,
)

/** One week's rows, indexed by area name as the address writes it. */
class FuelPhData(rows: List<FuelPhRow>) {
    val size: Int = rows.size
    private val byFull: Map<String, List<FuelPhRow>> = rows.groupBy { it.full }
    private val byBare: Map<String, List<FuelPhRow>> = rows.groupBy { it.bare }
    // A bare name two published areas share ("Quezon City" and "Quezon") cannot be told apart
    // from an address that says only "Quezon", so it is left out rather than guessed.
    private val ambiguous: Set<String> =
        byBare.filterValues { v -> v.map { it.area }.distinct().size > 1 }.keys
    private val fullNames = byFull.keys.filter { it !in ambiguous }.sortedByDescending { it.length }
    private val bareNames = byBare.keys.filter { it !in ambiguous }.sortedByDescending { it.length }

    /** The rows of the area the address names, or null: the full name first ("Quezon City"),
     *  then the name without its "City" ("Caloocan" for Caloocan City). */
    fun rowsFor(address: String): List<FuelPhRow>? {
        val hay = " " + FuelPh.full(address) + " "
        fullNames.firstOrNull { hay.contains(" $it ") }?.let { return byFull[it] }
        bareNames.firstOrNull { hay.contains(" $it ") }?.let { return byBare[it] }
        return null
    }

    companion object {
        val EMPTY = FuelPhData(emptyList())
    }
}
