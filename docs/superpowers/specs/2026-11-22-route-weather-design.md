# Weather along the route: design

Status: proposed 2026-11-22. Nothing built yet.

## Why

A drive can be ruined by rain you only meet halfway. Waylo tells you the route, its traffic and
its cameras, and nothing about the sky. The user's own words: "pag nag tratravel ako hindi ko
alam if uulan ba dun sa dadaanan ko". So: before leaving, for each route option, where it will
rain and roughly when you will be there.

The answer has to be usable in two ways, both from the same line: pick another route, or go
anyway and be ready.

## What it does

Under each route option in the directions panel, one line:

- Rain on the route: `Light rain around km 40-60, about 25 min after you leave`
- Snow where it is freezing (`snowfall` above zero): `Snow around km 40-60, about 25 min after you leave`
- A chance with no amount forecast: `Maybe rain around km 40-60, about 25 min after you leave, 70%`
- Dry: `No rain on this route`
- No answer (offline, toggle off, failure, beyond the forecast): no line at all.

The line never blocks the panel, never delays the ETAs, and never appears during a drive.

## Goals

- Per-route rain, so a dry route can win the pick.
- Timing by arrival, not by now: the forecast hour that matches when you reach each point.
- One keyless request for the whole panel.
- Silent on every failure.

## Non-goals

- A rain overlay, radar tiles or a nowcast.
- Weather at the destination only.
- A 7 or 16 day outlook, an hourly chart, wind, temperature, air quality.
- Weather as a routing input (no "avoid the rain" reroute).
- The car screen, Android Auto, a notification, or anything during a drive.
- Remembering a forecast across launches.

## Anti-goals

- A number we cannot justify, or a percentage the model does not actually support.
- A new host with an account, a key, or a Waylo server. That is the legal footing.
- A request when the switch is off.
- A slow panel. The route list must be usable the moment it opens.

## Constraints found in the project

- No key, no account, no Waylo backend (`CONTRIBUTING.md` ground rules). Open-Meteo's free API
  fits: no key, non-commercial use only, CC BY 4.0, under 10,000 calls a day, 5,000 an hour and
  600 a minute per IP. Waylo is free and carries no advertising, so it is the non-commercial case.
  Ads later would break this.
- CC BY 4.0 needs a credit in the app. The Wikipedia voice names and Overture places are already
  credited in the Map data hint, so weather goes beside them.
- A new network source gets a row in `PRIVACY.md`. Open-Meteo sees the IP and the sampled
  coordinates, and its policy keeps logs of coordinates up to 90 days.
- `:core` holds the logic and the network code, with unit tests. `:app` holds the UI.
- `MapScreen` is at two size limits: no new parameters or calls there. A child composable and a
  holder object.
- Non-Google requests carry `WayloConfig.WAYLO_UA` and use their own OkHttp client.
- New UI strings are translatable, in `values/strings.xml`.

## Approaches considered

**A. Open-Meteo, sampled along each route (chosen).** One request carries every sample point of
every route as comma-separated coordinates, so three routes cost one call. Hourly `precipitation`,
`precipitation_probability`, `snowfall` and `temperature_2m`, three forecast days, `timeformat=unixtime`.
The model grid is about 9 km outside Europe, so a ridge can be called dry when the pass is wet.
Open-Meteo downscales to a 90 m elevation model by default (`cell_selection=land`), which helps a
little on mountain roads and is the reason to prefer it over a raw grid.

**B. Open-Meteo at the endpoints only.** Two points, one line each. Cheapest, but it cannot answer
"on the way", which is the whole request.

**C. MET Norway, sampled along the route.** Same keyless footing, better terrain model in the
Nordics, but one request per point (up to 30 requests per panel), a required identifying
User-Agent, and the same coarse global grid over the Philippines. More code for no gain where this
user drives.

A is chosen: one call, one host, one credit, the smallest code that answers the question.

## How it is built

### Core: `core/data/weather/`

`RouteWeather.kt`, pure and testable, no Android types:

- `samples(route, departAtMs)`: walks `RouteProjection.cumulative` and `pointAt`, always including
  both ends. The stride is `max(SAMPLE_EVERY_M, total / MAX_SAMPLES_PER_ROUTE)`, so 12 samples span
  the whole route: a 300 km drive is sampled every 25 km, never left blind in the middle. Each
  sample carries its arrival time:
  `departAtMs + (durationInTrafficSeconds ?: durationSeconds) * m / total`.
- `read(payloads, samples)`: picks each sample's arrival hour from its location's hourly arrays,
  marks it wet, maybe or dry, groups consecutive samples into spans, and returns one span.
- `key(samples)`: the sample coordinates rounded to 3 decimals, joined. This keys the cached
  payload. Coordinates, not durations: selecting a provisional alternate re-times the route and
  "Show traffic" replaces the set, and neither changes where the road is. The rendered line is
  recomputed from the cached arrays on every recomposition, which is free.

Constants, all in one place:

- `WET_MM = 0.2` (a wet hour; below this the models are noise).
- `MAYBE_PCT = 60` (an hour with no amount but a probability at or above this is "maybe").
- `LIGHT_MM = 2.5`, `HEAVY_MM = 7.6` (WMO rain intensity, used as the words in the line: light
  rain, rain, heavy rain).
- `SNOW_C = 1.0` and `snowfall > 0` says snow, not rain.
- `MIN_ROUTE_M = 3_000` (shorter than this, a drive that crosses town explains itself).
- `HORIZON_H = 72` (a departure or arrival further out than the payload reaches shows no line).
- `SAMPLE_EVERY_M = 10_000`, `MAX_SAMPLES_PER_ROUTE = 12`, `MAX_SAMPLES_TOTAL = 36`.

Span selection, in order: a span with an amount beats a maybe span; among wet spans the largest
`precipitation` wins; among maybe spans the highest `precipitation_probability` wins. The line
prints the model's own percentage, rounded to the nearest 10, never `MAYBE_PCT`.

A span's bounds are the midpoints between the last dry sample and the first wet one, and between
the last wet sample and the first dry one after it: an interval the samples bound, never one they
merely sit inside. Both ends round to 5 km (or 5 mi). The distance is formatted by
`ui/Format.kt`'s `formatDistance`, the same as every other distance in the panel.

`OpenMeteo.kt`: the request and the parse, beside `FuelGbStore`'s shape.

- URL: `https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&hourly=precipitation,precipitation_probability,snowfall,temperature_2m&forecast_days=3&timeformat=unixtime&timezone=GMT`
- Own OkHttp client, `callTimeout(10, SECONDS)`, `WayloConfig.WAYLO_UA`.
- Streamed parse with `kotlinx.serialization` into a small DTO. Never hold the body as a tree.
- A failure returns null and is retried no sooner than `RETRY_AFTER_MS = 10 min`. Never cached as
  an empty answer.
- In-memory cache of the parsed hourly arrays, keyed by `key(samples)`, `MAX_CACHED = 4` entries,
  `CACHE_TTL_MS = 15 min`. A second look at the same panel, or at the same roads after the routes
  were re-timed, costs nothing. Nothing survives a restart.
- The panel's departure instant is the one the picker shows when the user picked one
  (`DepartTimeChooser`), otherwise now. A departure or arrival beyond `HORIZON_H` shows no line;
  the payload is not grown to chase it.
- Rate stays far under the limits: one call when a panel opens, at most a few dozen a day.

### App

- `ui/RouteWeatherLine.kt`: the composable line (icon, text, `SheetPalette` colors), and
  `rememberRouteWeather(routes, departAtMs, on)` built on `produceState(Dispatchers.IO)`. It asks
  for the payload once per coordinate key and returns `Map<routeIndex, Span?>`. The line shows only
  when the payload arrived and there is something to say.
- The line is for driving only. Walk, bike and transit panels do not ask, and a driving route under
  `MIN_ROUTE_M` gets no line. `LowDataMode` suppresses the request, the same way it suppresses map
  tiles.
- `DirectionsPanel` (classic, lists every route) draws the line under each route card.
- `GoogleStyleDirectionsPanel` draws it under the selected route summary and under each alternate
  in the alternates pane. One composable, three call sites.
- `object RouteWeatherOn` in `ui/PlaceContent.kt`, pref `route_weather`, **on by default**, started
  in `WayloApp` like `RouteTrafficOnTap`. `GoogleFree` does not turn it off: Open-Meteo is not
  Google. No network, no line.
- Settings > Navigation: a row next to "Live traffic re-checks while navigating",
  "Weather along the route", with a hint naming Open-Meteo.

### Docs in the same commit

- `SPEC.md`: a new 4.12, the constants, the URL, the failure rule.
- `docs/book/04-navigation.md`: what it does, where the data comes from, its license and host, the
  limits (grid size, no nowcast).
- `FEATURES.md`: one line under driving.
- `PRIVACY.md`: one table row (IP, sampled coordinates, 90 day logs at Open-Meteo).
- `res/values/strings.xml`: `settings_map_data_hint` gains one sentence crediting Open-Meteo,
  which is what CC BY 4.0 asks for.
- `README.md` unchanged: it says what reaches Google, and this does not reach Google.
- `ROADMAP.md` unchanged: nothing was listed for weather. The entry that does exist, the Waylo
  traffic layer, is untouched.

### Tests

- `core` unit tests: sampling count and spacing on a synthetic line, and a 300 km line whose stride
  grows so both ends stay covered, arrival-time interpolation from a picked departure, hour
  selection across a midnight boundary, a wet span that starts before a dry gap and resumes, span
  bounds made of midpoints, the threshold constants, the ranking of a wet span over a maybe span,
  and the cache key staying the same when only a duration changes.
- Parse test on a captured Open-Meteo reply for two locations (a Davis, California fixture), with
  the list-shaped response the API returns for comma-separated coordinates.
- One UI check on a release build with the location simulator: a route with rain in it, and a
  route with none.

## What is left to the builder

- The exact wording and icon of the line (base English strings).
- Whether the alternates pane's line sits above or below the route's time, once seen on a phone.
- The layout of the Settings row's hint.
- The order of the constants inside `RouteWeather.kt`.
