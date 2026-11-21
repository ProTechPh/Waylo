package app.waylo.core.data

import app.waylo.core.model.LatLng
import app.waylo.core.model.Place
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Philippine fuel prices (SPEC 5.9). The sheet is per city and product, so the tests pin down the
 * matching (which city an address names), the text the sheet shows (prices, then what they are),
 * and that nothing is claimed for a station the sheet does not name.
 */
class FuelPhTest {
    private val csv = """
        area,region,product,low,high,common,weekStart,weekEnd
        Caloocan City,ncr,Diesel,87.00,93.10,92.00,2026-11-10,2026-11-16
        Caloocan City,ncr,RON 91,65.30,77.80,68.10,2026-11-10,2026-11-16
        Caloocan City,ncr,RON 95,67.90,83.50,69.10,2026-11-10,2026-11-16
        Cagayan,north_luzon,Diesel,88.00,90.00,,2026-11-10,2026-11-16
        Cagayan de Oro,mindanao,Diesel,84.00,86.00,85.00,2026-11-17,2026-11-23
        Cagayan de Oro,mindanao,RON 95,79.00,81.00,80.00,2026-11-17,2026-11-23
        Quezon,north_luzon,Diesel,91.00,93.00,92.00,2026-11-10,2026-11-16
        Quezon City,ncr,Diesel,87.00,93.10,92.00,2026-11-10,2026-11-16
    """.trimIndent()

    private val data: FuelPhData = FuelPh.parse(StringReader(csv))

    private fun station(address: String, country: String? = "PH", name: String = "Petron") = Place(
        id = "p:$name",
        name = name,
        location = LatLng(14.65, 120.98),
        category = "Gas station",
        address = address,
        countryCode = country,
        placeType = FuelGb.GAS_STATION_TYPE,
    )

    private val isFuel: (Place) -> Boolean = { true }

    @Test
    fun sheetTextIsTheCityPriceAndSaysSo() {
        val text = FuelPh.text(data, "Caloocan, Metro Manila, Philippines")
        assertEquals(
            "Diesel \u20B192.00 \u00B7 RON 95 \u20B169.10\nDOE Caloocan City, week of Nov 10\u201316 \u00B7 city price, not this station's",
            text,
        )
    }

    @Test
    fun theLongestAreaNameTheAddressNamesWins() {
        // "Cagayan" is a province in the sheet; an address in Cagayan de Oro must not take it.
        assertEquals("DOE Cagayan de Oro", FuelPh.text(data, "Cagayan de Oro, Misamis Oriental")!!.substringAfter("\n").substringBefore(","))
        assertEquals("DOE Cagayan", FuelPh.text(data, "Cagayan, Tuguegarao")!!.substringAfter("\n").substringBefore(","))
    }

    @Test
    fun aBareNameTwoAreasShareIsNotGuessed() {
        assertNull(FuelPh.text(data, "Quezon, Nueva Ecija"))
        // The full name still matches.
        assertEquals("DOE Quezon City", FuelPh.text(data, "Quezon City, Metro Manila")!!.substringAfter("\n").substringBefore(","))
    }

    @Test
    fun aMissingCommonPriceFallsBackToTheRangeLow() {
        val text = FuelPh.text(data, "Tuguegarao, Cagayan")!!
        assertEquals("Diesel \u20B188.00\nDOE Cagayan, week of Nov 10\u201316 \u00B7 city price, not this station's", text)
    }

    @Test
    fun onlyAPhilippineGasStationIsFilled() {
        val ph = station("Caloocan, Metro Manila")
        assertEquals(2, FuelPh.annotate(ph, data, isFuel).fuelPh!!.lines().size)
        assertNull(ph.fuelPh)

        val us = station("Caloocan, Metro Manila", country = "US")
        assertSame(us, FuelPh.annotate(us, data, isFuel))

        val shop = station("Caloocan, Metro Manila").copy(placeType = "SearchResult.TYPE_CONVENIENCE_STORE")
        assertSame(shop, FuelPh.annotate(shop, data) { false })
    }

    @Test
    fun aCityTheSheetDoesNotNameIsLeftAlone() {
        val p = station("Davao City, Davao del Sur")
        assertSame(p, FuelPh.annotate(p, data, isFuel))
    }

    @Test
    fun anEmptyFileFillsNothing() {
        val p = station("Caloocan, Metro Manila")
        assertSame(p, FuelPh.annotate(p, FuelPhData.EMPTY, isFuel))
    }
}
