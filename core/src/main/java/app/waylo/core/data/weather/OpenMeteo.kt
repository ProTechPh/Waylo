package app.waylo.core.data.weather

/** One location's hourly forecast, the four arrays Open-Meteo answers with. `time` is epoch seconds. */
class Hourly(
    val time: LongArray,
    val precipitation: DoubleArray,
    val probability: IntArray,
    val snowfall: DoubleArray,
    val temperature: DoubleArray,
)
