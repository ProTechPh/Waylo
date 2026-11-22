# Weather Along the Route Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Under each driving route in the directions panel, show where and roughly when rain or snow will be on that route, from one keyless Open-Meteo request.

**Architecture:** All sampling, thresholds and parsing live in `:core` (`core/data/weather/`) with unit tests. `:app` adds one composable line and a 12-line preference holder, and draws the line under the route cards in both route pickers. One request covers every route's sample points.

**Tech Stack:** Kotlin, kotlinx.serialization, OkHttp, Compose Material 3, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-22-route-weather-design.md`

## Global Constraints

- No key, no account, no Waylo backend. Open-Meteo's free API is for non-commercial use only; Waylo is free and carries no advertising.
- Attribution: CC BY 4.0. The Map data hint in `strings.xml` credits Open-Meteo.
- `:core` holds no Android or UI types.
- The request uses its own OkHttp client with `callTimeout(10, TimeUnit.SECONDS)` and `WayloConfig.WAYLO_UA`. Never the shared Google client.
- No em dashes and no British spellings in added English text. US English.
- Every user-visible string is translatable, in `res/values/strings.xml`.
- Docs go in the same commit as the behavior (`SPEC.md`, `docs/book/`, `FEATURES.md`, `PRIVACY.md`).
- `MapScreen.kt` is not edited. Both panels get the line through their own composables.
- Test fixtures are Davis and Sacramento, California. No real coordinates anywhere.
- Constants, exact: `WET_MM = 0.2`, `MAYBE_PCT = 60`, `LIGHT_MM = 2.5`, `HEAVY_MM = 7.6`, `SNOW_C = 1.0`, `MIN_ROUTE_M = 3_000`, `HORIZON_H = 72`, `SAMPLE_EVERY_M = 10_000`, `MAX_SAMPLES_PER_ROUTE = 12`, `MAX_SAMPLES_TOTAL = 36`, `CACHE_TTL_MS = 15 min`, `MAX_CACHED = 4`, `RETRY_AFTER_MS = 10 min`, `forecast_days=3`.
- Commit subjects are plain sentences about the change. No AI attribution line.

## Review Focus

1. A 300 km drive: the stride grows so the whole route is sampled, and the reported km range does not claim more than the samples bound.
2. A picked departure: "Depart at Friday 08:00" and "Arrive by" time the line from the pick, and a pick past 72 h shows nothing.
3. A re-timed or re-fetched route set: the cache is keyed on coordinates, so no refetch, no flicker, no second request.
4. No network, an API error, the switch off, or low data mode: no line, no delay, no crash, and an empty answer is never cached.
5. A short drive, a walk or a bike route: no line and no request.

---

### Task 1: Sample points along a route

**Files:**
- Create: `core/src/main/java/app/waylo/core/data/weather/RouteWeather.kt`
- Test: `core/src/test/java/app/waylo/core/data/weather/RouteWeatherTest.kt`

**Interfaces:**
- Consumes: `Route.polyline`, `Route.distanceMeters`, `Route.durationSeconds`, `Route.durationInTrafficSeconds`, `RouteProjection.cumulative`, `RouteProjection.pointAt`.
- Produces:
  - `RouteWeather.Sample(val lat: Double, val lng: Double, val meters: Double, val atMs: Long)`
  - `RouteWeather.samples(route: Route, departAtMs: Long): List<Sample>`
  - `RouteWeather.key(samples: List<Sample>): String`
  - `RouteWeather.eligible(distanceMeters: Double, departAtMs: Long, arriveAtMs: Long, nowMs: Long, on: Boolean, lowData: Boolean): Boolean`
  - constants `SAMPLE_EVERY_M`, `MAX_SAMPLES_PER_ROUTE`, `MAX_SAMPLES_TOTAL`, `MIN_ROUTE_M`, `HORIZON_H`

- [ ] **Step 1: Write the failing tests**

`RouteWeatherTest.kt`, synthetic straight lines built from `LatLng` pairs (no real place):

```kotlin
private fun line(lengthM: Double): List<LatLng> =
    listOf(LatLng(38.5, -121.7), LatLng(38.5 + lengthM / 111_320.0, -121.7))

@Test fun `a 60 km drive samples every 10 km including both ends`() {
    val r = routeOf(distanceM = 60_000.0, durationSec = 3_600.0, line = line(60_000.0))
    val s = RouteWeather.samples(r, departAtMs = 0L)
    assertEquals(7, s.size)
    assertEquals(0.0, s.first().meters, 1.0)
    assertEquals(60_000.0, s.last().meters, 1.0)
    assertEquals(600_000L, s[1].atMs) // 10 km of 60 km of 60 min
}

@Test fun `a 300 km drive keeps 12 samples and both ends covered`() {
    val r = routeOf(distanceM = 300_000.0, durationSec = 10_800.0, line = line(300_000.0))
    val s = RouteWeather.samples(r, departAtMs = 0L)
    assertEquals(12, s.size)
    assertEquals(0.0, s.first().meters, 1.0)
    assertEquals(300_000.0, s.last().meters, 1.0)
    assertTrue(s.zipWithNext().all { (a, b) -> b.meters - a.meters in 20_000.0..30_000.0 })
}

@Test fun `durationInTraffic wins over duration`() { /* 60 km, duration 60 min, inTraffic 90 min -> last sample at 5_400_000L */ }

@Test fun `key ignores durations and catches moved coordinates`() { /* same line different durations -> equal keys; shifted line -> different key */ }

@Test fun `eligibility`() {
    val now = 1_000_000_000_000L
    assertFalse(RouteWeather.eligible(2_000.0, now, now, now, on = true, lowData = false)) // too short
    assertFalse(RouteWeather.eligible(50_000.0, now, now, now, on = false, lowData = false))
    assertFalse(RouteWeather.eligible(50_000.0, now, now, now, on = true, lowData = true))
    assertFalse(RouteWeather.eligible(50_000.0, now + 73 * 3_600_000L, now + 74 * 3_600_000L, now, true, false)) // past horizon
    assertTrue(RouteWeather.eligible(50_000.0, now, now + 3_600_000L, now, on = true, lowData = false))
}
```

Add a private `routeOf(...)` helper constructing a `Route` with `polyline`, `distanceMeters`, `durationSeconds`, `durationInTrafficSeconds`, `legs = emptyList()`, `summary = "test"`.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew :core:test --tests 'app.waylo.core.data.weather.RouteWeatherTest'`
Expected: FAIL, `Unresolved reference: RouteWeather`.

- [ ] **Step 3: Implement the sampling**

`RouteWeather.kt`: the `Sample` data class, the constants, then

```kotlin
fun samples(route: Route, departAtMs: Long): List<Sample> {
    val poly = route.polyline
    if (poly.size < 2) return emptyList()
    val cum = RouteProjection.cumulative(poly)
    val total = cum.last().coerceAtLeast(1.0)
    val stride = maxOf(SAMPLE_EVERY_M, total / MAX_SAMPLES_PER_ROUTE)
    val n = (total / stride).toInt().coerceIn(1, MAX_SAMPLES_PER_ROUTE - 1)
    val travelMs = (route.durationInTrafficSeconds ?: route.durationSeconds) * 1000.0
    fun at(m: Double) = pointAt(poly, cum, m).let { Sample(it.lat, it.lng, m, departAtMs + (travelMs * m / total).toLong()) }
    return (0..n).map { at(stride * it) } .let { it.dropLast(1) + at(total) }
}
```

`key`: `samples.joinToString(";") { "%.3f,%.3f".format(Locale.US, it.lat, it.lng) }`.

`eligible`: `on && !lowData && distanceMeters >= MIN_ROUTE_M && arriveAtMs <= nowMs + HORIZON_H * 3_600_000L`.

- [ ] **Step 4: Run the tests until they pass**

Run: `./gradlew :core:test --tests 'app.waylo.core.data.weather.RouteWeatherTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/app/waylo/core/data/weather core/src/test/java/app/waylo/core/data/weather
git commit -m "Sample the points along a driving route for its weather"
```

---

### Task 2: Read the forecast into one span

**Files:**
- Modify: `core/src/main/java/app/waylo/core/data/weather/RouteWeather.kt`
- Create: `core/src/main/java/app/waylo/core/data/weather/OpenMeteo.kt` (the `Hourly` data class only; Task 3 fills it in)
- Test: `core/src/test/java/app/waylo/core/data/weather/RouteWeatherReadTest.kt`

**Interfaces:**
- Consumes: `RouteWeather.Sample`.
- Produces:
  - `OpenMeteo.Hourly(time: LongArray, precipitation: DoubleArray, probability: IntArray, snowfall: DoubleArray, temperature: DoubleArray)`
  - `RouteWeather.Kind { LIGHT, RAIN, HEAVY, SNOW, MAYBE, NONE }`
  - `RouteWeather.Span(kind: Kind, fromMeters: Double, toMeters: Double, afterSeconds: Double, percent: Int?)`
  - `RouteWeather.read(hours: List<OpenMeteo.Hourly>, samples: List<Sample>): Span?`

- [ ] **Step 1: Write the failing tests**

`RouteWeatherReadTest.kt`. A helper builds one `Hourly` whose arrays start at epoch 0, one value per hour:

```kotlin
private fun hours(vararg mm: Double, pct: Int = 0, snow: Double = 0.0, temp: Double = 20.0): List<Hourly>
```

Tests:

```kotlin
@Test fun `a wet hour at the arrival hour is LIGHT and keeps the sample bounds`()
// samples at 0 m / 10 000 m / 20 000 m, arrivals 0 h / 1 h / 2 h, mm = 0, 0.6, 0
// expect Kind.LIGHT, fromMeters = 5_000.0 (midpoint), toMeters = 15_000.0, afterSeconds = 3_600.0

@Test fun `the middle dry sample separates two spans`() // mm = 1.0, 0, 9.0 -> one span at the last sample only
@Test fun `intensity words follow WMO`() // 0.5 -> LIGHT, 5.0 -> RAIN, 9.0 -> HEAVY
@Test fun `snow wins below freezing`() // mm 1.0, snow 0.4, temp 0.5 -> Kind.SNOW
@Test fun `a maybe hour needs the probability and carries the model's own percent`()
// mm 0.0, pct 70 -> Kind.MAYBE, percent = 70; pct 67 -> percent = 70; pct 45 -> null
@Test fun `a wet span beats a maybe span`() // sample 0 pct 95 mm 0, sample 1 mm 3.0 -> Kind.RAIN
@Test fun `no answer is null`() // read(emptyList(), samples) == null
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :core:test --tests 'app.waylo.core.data.weather.RouteWeatherReadTest'`
Expected: FAIL, `Unresolved reference: read`.

- [ ] **Step 3: Implement `read`**

Per sample: hour index is `(atMs / 3_600_000L).toInt()`; find the array index whose `time[i] <= atMs/1000 < time[i]+3600` (linear scan over at most 72 entries). Classify: snow when `snowfall > 0 && temperature <= SNOW_C`; wet when `precipitation >= WET_MM`; maybe when `precipitation == 0.0 && probability >= MAYBE_PCT`; else dry. Wet intensity: `>= HEAVY_MM` HEAVY, `>= LIGHT_MM` RAIN, else LIGHT.

Then: split samples into runs of consecutive non-dry samples of the same kind class (wet or maybe), choose the run with the largest peak `precipitation` among wet runs, else the largest `probability` among maybe runs, and build the span:

- `fromMeters`: `0` for the first sample, else the midpoint between the sample before the run and the run's first sample.
- `toMeters`: `total` for the last sample, else the midpoint between the run's last sample and the next one.
- `afterSeconds`: `(firstSample.atMs - samples.first().atMs) / 1000.0`.
- `percent`: the run's highest probability when the kind is MAYBE, rounded to the nearest 10 (`((p + 5) / 10 * 10).coerceAtMost(100)`), else null. Never `MAYBE_PCT`.

- [ ] **Step 4: Run until they pass**

Run: `./gradlew :core:test --tests 'app.waylo.core.data.weather.RouteWeatherReadTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/app/waylo/core/data/weather core/src/test/java/app/waylo/core/data/weather
git commit -m "Read a route's forecast into the one span worth showing"
```

---

### Task 3: The Open-Meteo request, parse and cache

**Files:**
- Modify: `core/src/main/java/app/waylo/core/data/weather/OpenMeteo.kt`
- Create: `core/src/test/java/app/waylo/core/data/weather/OpenMeteoTest.kt`
- Create: `core/src/test/resources/weather/openmeteo-davis.json`
- Modify: `PRIVACY.md` (one table row)

**Interfaces:**
- Produces:
  - `OpenMeteo.Hourly(time: LongArray, precipitation: DoubleArray, probability: IntArray, snowfall: DoubleArray, temperature: DoubleArray)`
  - `OpenMeteo.url(samples: List<RouteWeather.Sample>): String`
  - `OpenMeteo.parse(json: String): List<Hourly>?`
  - `OpenMeteo.get(url: String): String?`
  - `class RouteWeatherClient(fetch: suspend (String) -> String?, nowMs: () -> Long = System::currentTimeMillis)` with `suspend fun hourly(samples: List<RouteWeather.Sample>): List<Hourly>?`
  - `object RouteWeatherRepo { val client: RouteWeatherClient }`

- [ ] **Step 1: Capture the fixture**

Open this URL once and save the reply as `core/src/test/resources/weather/openmeteo-davis.json` (Davis, California, two points):

`https://api.open-meteo.com/v1/forecast?latitude=38.55,-121.74&longitude=-121.74,-121.49&hourly=precipitation,precipitation_probability,snowfall,temperature_2m&forecast_days=3&timeformat=unixtime&timezone=GMT`

Keep it under 40 KB. It is a JSON array of two objects, each with `hourly.time` as epoch seconds.

- [ ] **Step 2: Write the failing tests**

```kotlin
@Test fun `url carries every sample and three days`() // parsed query has latitude=-..,-.., longitude=.., forecast_days=3, timeformat=unixtime, timezone=GMT
@Test fun `parse reads the list shaped reply`() // fixture -> size 2, equal array lengths, time[0] == 1763...L (read the value from the file)
@Test fun `parse answers null on a broken reply`() // "{" -> null, "[]" -> null
@Test fun `one fetch per key inside the ttl`() // fake fetch counts calls: 2 hourly() calls -> 1 fetch
@Test fun `a new key fetches again`()
@Test fun `an expired entry fetches again`() // nowMs advanced by CACHE_TTL_MS + 1
@Test fun `a failure is retried only after the gate`() // fetch returns null twice: calls = 1; after RETRY_AFTER_MS: calls = 2
@Test fun `a failure is never cached as an answer`() // after a failure, hourly() == null, not emptyList
@Test fun `more than MAX_CACHED keys evicts the oldest`()
```

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew :core:test --tests 'app.waylo.core.data.weather.OpenMeteoTest'`
Expected: FAIL, `Unresolved reference: OpenMeteo`.

- [ ] **Step 4: Implement**

`url`: coordinates with 3 decimals, comma joined, `Locale.US`; the parameter order from the design doc, endpoints included.

`parse`: stream the body through `Json.parseToJsonElement`, accept a `JsonArray` (multi point) or a single `JsonObject` as a one element list, read the four hourly arrays by name, return null on any shape or length mismatch. Coerce absent counts to 0.

`get`: lazy own client, `callTimeout(10, TimeUnit.SECONDS)`, `header("User-Agent", WayloConfig.WAYLO_UA)`, body to string, null on any failure.

`RouteWeatherClient.hourly`: `key = RouteWeather.key(samples)`; hit when now - at < `CACHE_TTL_MS`; miss fetches; on null remember `failedAt` and return null until `RETRY_AFTER_MS`; evict the oldest beyond `MAX_CACHED`; guard with a `Mutex`.

`RouteWeatherRepo.client = RouteWeatherClient(OpenMeteo::get)`.

`PRIVACY.md`: a row after FOSSGIS Valhalla, before NDW:

| **Open-Meteo** | a driving route's weather line in the directions panel, with "Weather along the route" on (the default) | your IP, and up to 36 coordinates sampled along the routes. Its policy keeps webserver logs that may hold coordinates for 90 days |

- [ ] **Step 5: Run the tests until they pass**

Run: `./gradlew :core:test --tests 'app.waylo.core.data.weather.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/app/waylo/core/data/weather core/src/test/java/app/waylo/core/data/weather core/src/test/resources/weather PRIVACY.md
git commit -m "Ask Open-Meteo for the forecast at the points along a route"
```

---

### Task 4: The line under the route cards

**Files:**
- Create: `app/src/main/java/app/waylo/ui/RouteWeatherLine.kt`
- Modify: `app/src/main/java/app/waylo/ui/Format.kt` (add `formatDistanceRange`)
- Modify: `app/src/main/java/app/waylo/ui/PlaceContent.kt` (add `RouteWeatherOn`)
- Modify: `app/src/main/java/app/waylo/WayloApp.kt` (one init line)
- Modify: `app/src/main/java/app/waylo/ui/place/PlaceSheet.kt` (`DirectionsPanel`)
- Modify: `app/src/main/java/app/waylo/ui/place/GoogleChooser.kt` (`GoogleStyleDirectionsPanel`)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `SPEC.md` (new 4.12), `docs/book/04-navigation.md`, `FEATURES.md`

**Interfaces:**
- Consumes: `RouteWeatherRepo.client`, `RouteWeather.samples/read/key/eligible`, `RouteWeather.Kind`, `LowDataMode.enabled`, `DepartPick`.
- Produces:
  - `object RouteWeatherOn { val on: mutableStateOf<Boolean>; fun init(context); fun set(context, value) }` (pref `route_weather`, default on)
  - `fun formatDistanceRange(fromMeters: Double, toMeters: Double): String`
  - `@Composable fun RouteWeatherLine(span: RouteWeather.Span?)`
  - `@Composable fun rememberRouteWeather(routes: List<Route>, mode: TravelMode, active: Route?): List<RouteWeather.Span?>`

- [ ] **Step 1: Add the strings and the range formatter**

Strings (`values/strings.xml`, English only):

- `route_weather_light_rain`: "Light rain %1$s, %2$s after you leave"
- `route_weather_rain`: "Rain %1$s, %2$s after you leave"
- `route_weather_heavy_rain`: "Heavy rain %1$s, %2$s after you leave"
- `route_weather_snow`: "Snow %1$s, %2$s after you leave"
- `route_weather_maybe`: "Maybe rain %1$s, %2$s after you leave, %3$s"
- `route_weather_none`: "No rain on this route"
- `settings_map_data_hint`: append one sentence, "The weather along each route is from Open-Meteo."

`formatDistanceRange` rounds each end to 5 km (metric) or 5 mi (imperial) and returns `"40-60 km"` / `"25-37 mi"`; a collapsed range returns the single rounded value.

- [ ] **Step 2: Add the holder and wire the composition**

`RouteWeatherOn` follows `RouteTrafficOnTap` exactly (same prefs file, `KEY = "route_weather"`, default `true`). Add `app.waylo.ui.RouteWeatherOn.init(this)` beside `RouteTrafficOnTap.init(this)` in `WayloApp.kt`.

- [ ] **Step 3: Write the line and the hook**

`RouteWeatherLine.kt`:

```kotlin
@Composable fun RouteWeatherLine(span: RouteWeather.Span?) { /* nothing when null */ }
```

The text picks one of the six strings by `span.kind`, `%1$s = formatDistanceRange(from, to)`, `%2$s = formatDuration(span.afterSeconds)`, `%3$s = "${span.percent}%"`. Text only, at `MaterialTheme.typography.labelMedium` in `SheetPalette.dim(dark)`; no icon in this version.

```kotlin
@Composable
fun rememberRouteWeather(routes: List<Route>, mode: TravelMode, active: Route?): List<RouteWeather.Span?> {
    val on = RouteWeatherOn.on.value
    val departAt = remember(active?.summary, routes.size) { departAtMs(active) }
    return produceState<List<RouteWeather.Span?>>(emptyList(), routes, departAt, on, mode) {
        value = if (mode != TravelMode.DRIVE || !on) routes.map { null }
                else withContext(Dispatchers.IO) { load(routes, departAt) }
    }.value
}
```

`load`: keep only routes where `RouteWeather.eligible(...)` holds with `lowData = LowDataMode.enabled`, ask `RouteWeatherRepo.client.hourly(samples of all kept routes)` once (past `MAX_SAMPLES_TOTAL`, drop the later routes), then `read` per route. Any null payload returns all nulls.

`departAtMs(active)`: read `DepartPick` without side effects through one new pure accessor `DepartPick.peek(forKey)` (`internal fun peek(forKey: Any?): Pick?`, no dropped-flag change). Mode 1 returns the pick's epoch; mode 2 returns `pickEpoch - (durationInTrafficSeconds ?: durationSeconds) * 1000`; otherwise `System.currentTimeMillis()`.

- [ ] **Step 4: Draw it in both panels**

`DirectionsPanel`: one call at the top of the body, `val weather = rememberRouteWeather(routes, currentMode, activeRoute)`, and `RouteWeatherLine(weather.getOrNull(i))` inside each route card, under the ETA line.

`GoogleStyleDirectionsPanel`: the same call, a line under the selected route's summary line, and one under each alternate row in the alternates pane.

- [ ] **Step 5: Build and run the tests**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugKotlin`
Expected: PASS, no new warnings in `RouteWeatherLine.kt`.

- [ ] **Step 6: Write the docs**

- `SPEC.md` 4.12 "Weather along the route": the switch, the constants, the URL, one request per panel, one span per route, silent on failure, drive only, `MIN_ROUTE_M`, the 72 h horizon, the coordinates-only cache key, and the note that Open-Meteo is non-commercial only.
- `docs/book/04-navigation.md`: in the traffic rechecks part, one paragraph in the book's four-part shape (what you see, where the data comes from with licence and host, how the span is chosen, limits: about 9 km grid, no nowcast, mountains can read dry).
- `FEATURES.md`: one line under driving.

- [ ] **Step 7: Commit**

```bash
git add app/src/main core/src/main SPEC.md docs/book/04-navigation.md FEATURES.md
git commit -m "Show the rain on each route before you leave"
```

---

### Task 5: The switch under Settings > Navigation

**Files:**
- Modify: `app/src/main/java/app/waylo/ui/settings/sections/NavigationSettings.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `docs/book/13-settings.md` (one row in the switch list, if it lists Navigation's switches)

**Interfaces:**
- Consumes: `RouteWeatherOn.on` / `.set(context, it)`.
- Produces: nothing.

- [ ] **Step 1: Add the row**

Strings: `settings_route_weather`: "Weather along the route"; `settings_route_weather_hint`: "Shows rain, snow or a chance of rain along each driving route before you leave, from Open-Meteo. One request when the route list opens, carrying up to 36 points along your routes. On by default."

A `ToggleRow` in `NavigationSettingsScreen` beside the route-bar and turn-arrow rows, in the same style:

```kotlin
ToggleRow(
    label = stringResource(R.string.settings_route_weather),
    checked = app.waylo.ui.RouteWeatherOn.on.value,
    onCheckedChange = { app.waylo.ui.RouteWeatherOn.set(context, it) },
    hint = stringResource(R.string.settings_route_weather_hint),
)
```

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/waylo/ui/settings/sections/NavigationSettings.kt app/src/main/res/values/strings.xml docs/book/13-settings.md
git commit -m "Add the weather row to Settings > Navigation"
```

---

### Task 6: Check it on a release build

**Files:** none.

- [ ] **Step 1: Build and install the release build over the current one**

Run: `./gradlew :app:assembleRelease`, then `adb install -r` with `-PappVersionCode=` set to the installed build's code.

- [ ] **Step 2: Check a route with rain in it**

Settings > Diagnostics > Simulate my location to Davis, California. Plan a long driving route (300 km or more, so the stride grows). Expected: one line under each route card, the km range consistent with the sample spacing, no line during the walk and bike tabs.

- [ ] **Step 3: Check the failure paths**

Turn on airplane mode, reopen the route list. Expected: the route list appears at once with no line, no error, no spinner. Turn the switch off in Settings > Navigation, turn the radio back on, reopen: no request, no line. Check logcat for no new crash.

- [ ] **Step 4: Check a picked departure**

Pick "Depart at" for tomorrow morning. Expected: the line's "after you leave" minutes still match the route, and the arrival clock shown by the picker is consistent with the span. A pick four days out shows no line.

- [ ] **Step 5: Confirm the docs and the constants agree**

Read SPEC 4.12 against `RouteWeather.kt` and `OpenMeteo.kt` side by side. Every constant in the SPEC exists in the code with the same value. Then:

Run: `./gradlew :core:test :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit anything the check changed**

```bash
git add -A
git commit -m "Match the weather docs to the checked build"
```
