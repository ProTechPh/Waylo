package app.waylo

import android.app.Application
import android.content.Context
import app.waylo.core.diag.DiagLog
import app.waylo.diag.CrashCatcher
import app.waylo.ui.AppLocale
import app.waylo.ui.Onboarding
import app.waylo.ui.Traffic
import app.waylo.ui.TransitLayer
import app.waylo.ui.Units
import app.waylo.ui.theme.AppTheme
import app.waylo.ui.theme.DynamicColor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WayloApp : Application(), coil.ImageLoaderFactory {
    @Inject lateinit var diag: DiagLog
    @Inject lateinit var http: okhttp3.OkHttpClient
    @Inject lateinit var locationProvider: app.waylo.core.location.LocationProvider
    // Built here, before anything below asks for a link: the store applies the cached bundle's
    // pushed links as it is made, and the fonts and camera fetches at start read them.
    @Inject lateinit var calibrationStore: app.waylo.core.config.CalibrationStore

    /** Coil with a HARD memory-cache cap. The default budget is ~25% of the app's heap CLASS,
     *  and largeHeap makes that class huge - on a 512 MB large heap Coil happily retains up to
     *  ~128 MB of decoded gallery bitmaps by design, which is most of the "rapid place churn
     *  runs into the ceiling" OOM (issue #182; measured: 3 gallery-bearing places grew the live
     *  Dalvik heap 14 -> 94 MB). 48 MB still holds a couple of screens of thumbnails + a hero
     *  or two; everything else re-decodes from Coil's disk cache, which is untouched. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        // The shared client (Google hosts go over Cronet and are counted), with Chrome's image
        // headers put on Google image requests first; Coil's own client said "okhttp/4.12.0".
        .okHttpClient { http.newBuilder().apply { interceptors().add(0, app.waylo.core.net.GoogleTransport.imageHeaders) }.build() }
        .memoryCache {
            coil.memory.MemoryCache.Builder(this)
                .maxSizeBytes(if (app.waylo.ui.MemoryPressure.lowRam) 16 * 1024 * 1024 else 48 * 1024 * 1024)
                .build()
        }
        .build()

    /** Apply the persisted in-app language to the Application context too (no-op when following the
     *  system), so `getString` from the ViewModel/nav-notification also localizes: resolved at launch
     *  from the saved pref (an in-session change re-reads it on next launch). */
    /**
     * Hand OS memory pressure to every holder that owns a large or native allocation (ported from
     * vela-dpad, 2026-07-23). Before this existed nothing in the app implemented
     * ComponentCallbacks2, so a TRIM_MEMORY_COMPLETE released nothing at all and the OS had no
     * option but to kill us. Coil's own cache is trimmed here; everything else releases through
     * [app.waylo.ui.MemoryPressure].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        app.waylo.ui.MemoryPressure.dispatch(level)
        app.waylo.diag.TripNote.add("memory: the system asked for memory back (level $level)")
        if (app.waylo.ui.MemoryPressure.isSevere(level)) {
            runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(app.waylo.ui.AdaptiveDensity.wrap(base)))
    }

    override fun onCreate() {
        super.onCreate()
        // A build's own data links (a local test server, -PobfManifestUrl= and the like), before
        // anything fetches. Blank means the compiled default in WayloLinks.
        app.waylo.core.config.WayloLinks.compiled = mapOf(
            app.waylo.core.config.WayloLinks.OBF_MANIFEST to BuildConfig.OBF_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.OVERLAY_MANIFEST to BuildConfig.OVERLAY_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.MAXSPEED_MANIFEST to BuildConfig.MAXSPEED_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.ADDRESS_MANIFEST to BuildConfig.ADDRESS_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.PLACES_MANIFEST to BuildConfig.PLACES_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.CELLS_MANIFEST to BuildConfig.CELLS_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.BASEMAP_MANIFEST to BuildConfig.BASEMAP_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.WORLD_BASEMAP to BuildConfig.WORLD_BASEMAP_URL,
            app.waylo.core.config.WayloLinks.POI_PACK_MANIFEST to BuildConfig.POI_PACK_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.FLOCK_MANIFEST to BuildConfig.FLOCK_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.ROAD_FEATURES_MANIFEST to BuildConfig.ROAD_FEATURES_MANIFEST_URL,
            app.waylo.core.config.WayloLinks.MAP_FONTS to BuildConfig.MAP_FONTS_URL,
        ).filterValues { it.isNotBlank() }
        // Device memory class first: the Coil cap and the eager-warm decisions read it.
        app.waylo.ui.MemoryPressure.init(this)
        app.waylo.ui.SpeechPreload.init(this) // after MemoryPressure: its default reads the RAM tier
        app.waylo.ui.FullPlaceLoad.init(this)
        // Google-host requests over Chrome's network stack (Cronet), built lazily on the first one.
        // Calibration `useCronet` 0, or an engine that fails to build, leaves them on OkHttp.
        // Before Cronet opens its cache: a due rotation deletes it (Settings > Privacy).
        app.waylo.web.SessionRotation.init(this)
        app.waylo.web.GoogleStanding.init(this)
        app.waylo.web.GoogleTelemetry.init(this)
        app.waylo.diag.GoogleUsageStore.init(this) // Settings > Privacy > Requests to Google
        // adb-only: `setprop debug.waylo.tune.feedDump 1` saves raw review-feed replies to
        // Android/data/app.waylo/files/feeddump/ (ReviewFeedDebug). Never on otherwise.
        if (app.waylo.ui.AppTune.localOn("feedDump")) {
            val dir = getExternalFilesDir("feeddump")
            app.waylo.core.data.google.ReviewFeedDebug.sink = { raw ->
                dir?.let { java.io.File(it, "qv9Egd-${System.currentTimeMillis()}.txt").writeText(raw) }
            }
        }
        // adb-only: `setprop debug.waylo.tune.netLog 1` also opens the WebViews to Chrome's remote
        // inspector (adb forward to webview_devtools_remote_<pid>), for reading their real headers.
        if (app.waylo.ui.AppTune.localOn("netLog")) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        // A test switch for the routes' fallbacks (ValhallaRouter.matchOff). adb only.
        app.waylo.core.data.ValhallaRouter.matchOff = app.waylo.ui.AppTune.localOn("noMatch")
        app.waylo.web.SessionRotation.appJar = http.cookieJar as? app.waylo.core.di.ResettableCookieJar
        app.waylo.net.CronetHolder.init(this)
        app.waylo.core.net.GoogleTransport.interceptor = app.waylo.net.CronetTransport(http.cookieJar, app.waylo.web.WebViewCookieJar())
        // Push the device class down to :core, which cannot read an :app holder (same seam as
        // CategoryFilter.enabled). Gates the ambient POI fan-out in GoogleMapsDataSource.
        app.waylo.core.data.LowRamMode.enabled = app.waylo.ui.MemoryPressure.lowRam
        app.waylo.core.data.RouterTestHooks.openRouterDown = app.waylo.ui.AppTune.localOn("openRouterDown")
        app.waylo.offline.StorageLocation.init(this)
        app.waylo.offline.RoadNameTileSource.install(this) // street names for LineNamer (issue #478)
        Units.init(this)
        // The desktop window size Google's requests describe: picked once per install, then kept
        // (a size that changed per launch would be its own oddity). See BrowserViewport.
        run {
            val p = getSharedPreferences("waylo_settings", MODE_PRIVATE)
            val idx = p.getInt("browser_viewport", -1).takeIf { it >= 0 }
                ?: kotlin.random.Random.nextInt(app.waylo.core.data.google.BrowserViewport.CHOICES.size).also { p.edit().putInt("browser_viewport", it).apply() }
            val (w, h) = app.waylo.core.data.google.BrowserViewport.choice(idx)
            app.waylo.core.data.google.BrowserViewport.set(w, h)
        }
        app.waylo.ui.Clock24.refresh(this) // the 12/24-hour clock setting (issue #357); MainActivity refreshes it on resume
        app.waylo.core.data.transit.Transitous.durationFormatter = { secs -> app.waylo.ui.formatDuration(secs.toDouble()) }
        app.waylo.core.i18n.DelayText.formatter = { min ->
            if (min > 0) getString(R.string.transit_min_late, min) else getString(R.string.transit_min_early, -min)
        }
        AppTheme.init(this)
        DynamicColor.init(this)
        AppLocale.init(this) // resolve the app language (system default) → drives the nav-text locale
        Traffic.init(this)
        TransitLayer.init(this)
        app.waylo.ui.SatelliteLayer.init(this) // persisted satellite-imagery toggle
        app.waylo.ui.LayersButton.init(this) // persisted show/hide of the map layers button
        app.waylo.ui.MapTilt.init(this) // two-finger tilt on/off (issue #627)
        app.waylo.ui.NorthLock.init(this) // Settings > Map "Keep north up"
        app.waylo.ui.ParkingButton.init(this) // parking button shown with no spot saved (issue #626)
        app.waylo.ui.LinkAction.init(this) // what a location link from another app does (discussion #640)
        app.waylo.ui.StartView.init(this) // where the map opens, and the view picked for it
        app.waylo.ui.NavEndConfirm.init(this) // ask before ending a drive, off by default (issue #624)
        app.waylo.ui.SpeedDisplay.init(this) // speed + limit badge on/off, phone and car (issue #625)
        app.waylo.ui.Topography.init(this)
        app.waylo.ui.Flock.init(this) // load the persisted surveillance-camera toggle (else it read false every launch)
        app.waylo.ui.SpeedCams.init(this) // same init-or-it-reads-false trap as Flock
        app.waylo.ui.SpeedCamWarn.init(this) // spoken camera warning (issue #229), off by default
        app.waylo.ui.SpeedingAlert.init(this) // spoken over-the-limit alert (issue #404), off by default
        app.waylo.ui.BridgeAlert.init(this) // bridge openings on the route in the Netherlands, on by default
        app.waylo.ui.BikeSafe.init(this) // bike routes prefer lanes and quiet streets (issue #401), on by default
        // The chooser's sticky avoid toggles, for the nav session's own fetches from the first
        // drive on (a resumed drive or an Android Auto start never opens the phone's chooser).
        getSharedPreferences("waylo_settings", MODE_PRIVATE).let { p ->
            app.waylo.core.data.RoutingPrefs.avoidTolls = p.getBoolean("avoid_tolls", false)
            app.waylo.core.data.RoutingPrefs.avoidHighways = p.getBoolean("avoid_highways", false)
            app.waylo.core.data.RoutingPrefs.avoidFerries = p.getBoolean("avoid_ferries", false)
        }
        app.waylo.ui.FlockRouteAlert.init(this) // load the persisted "warn about cameras on route" toggle
        app.waylo.ui.FlockDetour.init(this) // side-street detours around cameras (issue #600), nested under it
        app.waylo.ui.FlockNavAlert.init(this) // plate-camera card + spoken alert while navigating, off by default
        // Parse the bundled on-device ALPR/Flock camera dataset off the main thread (map layer draws
        // instantly, route counts are reliable), then refresh from the hosted manifest so the data updates
        // without an app release (weekly CI cron re-hosts a newer version; a bump swaps it in on next launch).
        CoroutineScope(Dispatchers.IO).launch {
            // The "maps app" entry follows the launcher's name (one call, then a no-op).
            runCatching { app.waylo.ui.AppNameAlias.syncPanel(this@WayloApp) }
            // The catalog regions' real boundaries (issue #599): until this lands, region picks
            // fall back to their boxes, which is what they always were.
            app.waylo.offline.RegionPolys.ensureLoaded(this@WayloApp)
            app.waylo.data.FlockCameras.ensureLoaded(this@WayloApp)
            app.waylo.data.FlockCameras.refresh(this@WayloApp, app.waylo.core.config.WayloLinks.flockManifest())
        }
        app.waylo.ui.SimLocation.onChange = {
            locationProvider.pinned = it
            android.util.Log.i("WayloSim", "simulated location " + if (it != null) "on" else "off")
        }
        app.waylo.ui.SimLocation.init(this)
        app.waylo.ui.UiScale.init(this)
        app.waylo.ui.PageTransitions.init(this)
        app.waylo.ui.AppFont.init(this) // user-supplied UI font (issue #252)
        app.waylo.ui.MapColors.init(this)
        app.waylo.ui.MapButtonShape.init(this) // round or square map buttons (issue #797)
        app.waylo.ui.TurnArrows.init(this) // arrows on the route at the turns ahead (issue #804)
        app.waylo.ui.CarSpeed.init(this) // the car's speed badge, off by default
        app.waylo.ui.LiveReviews.init(this)
        app.waylo.ui.ShowReviews.init(this)
        app.waylo.ui.LoadPhotos.init(this)
        app.waylo.ui.OfflinePlaces.init(this)
        app.waylo.ui.ReviewsOnTap.init(this)
        app.waylo.ui.PhotosOnTap.init(this)
        app.waylo.ui.DetailsRetry.init(this)
        app.waylo.ui.OtherLocationsAuto.init(this)
        app.waylo.ui.RouteTrafficOnTap.init(this)
        app.waylo.core.data.RoutingPrefs.googleTraffic = !app.waylo.ui.RouteTrafficOnTap.on.value
        app.waylo.ui.HideAdult.init(this)
        app.waylo.ui.HideExternalLinks.init(this)
        app.waylo.ui.GoogleFree.init(this) // "Use Waylo without Google": mirrors into the :core NoGoogle flag
        app.waylo.ui.Buildings3d.init(this)
        app.waylo.ui.RouteTrail.init(this)
        app.waylo.ui.TurnDeclutterPref.init(this)
        app.waylo.ui.CategoryChipsPref.init(this)
        app.waylo.ui.QuickChipsPref.init(this) // which quick-category chips show, and their order (issue #779)
        app.waylo.ui.RoadLabel.init(this)
        app.waylo.ui.NavNorthUp.init(this)
        app.waylo.ui.ZoomToTurn.init(this) // zoom out to the next turn while driving, off by default (issue #781)
        app.waylo.ui.PuckStyle.init(this)
        app.waylo.ui.HouseNumbers.init(this) // house-number zoom gate (issue #329)
        app.waylo.ui.MapNames.init(this) // which name a map label shows (issue #738)
        app.waylo.ui.PreferButtons.init(this)
        app.waylo.ui.ZoomKeys.init(this)
        app.waylo.ui.PipTurnCard.init(this)
        app.waylo.ui.PauseInBar.init(this)
        app.waylo.ui.FasterRouteAuto.init(this)
        app.waylo.ui.RegionUpdates.init(this)
        app.waylo.ui.BuildingOverlay.init(this)
        app.waylo.ui.BuildingDebug.init(this)
        app.waylo.ui.MapPoiPrefs.init(this)
        // Mirrors into the :core flag NavEngine reads (issue #596).
        app.waylo.ui.SpokenRoadNames.init(this)
        app.waylo.ui.RoadNumbers.init(this) // numbered roads go by their number (issue #836), off by default
        app.waylo.ui.SpokenDetail.init(this)
        app.waylo.ui.RoutePicker.init(this)
        app.waylo.ui.VoiceSearch.init(this)
        app.waylo.ui.ContactsSearch.init(this) // contacts-in-search toggle (issue #243)
        app.waylo.diag.NavTrace.init(this) // opt-in nav smoothness trace (issue #251)
        app.waylo.ui.MapLook.init(this) // which OpenFreeMap look the basemap loads (issue #750); before MapFonts
        app.waylo.ui.map.MapFonts.init(this) // Roboto basemap glyphs (cached patched style + async refresh)
        Onboarding.init(this)
        app.waylo.ui.WhatsNew.init(this)
        // Persist any fatal crash (stack trace + breadcrumbs) so it survives the
        // restart and can be exported from Settings → Diagnostics next launch.
        CrashCatcher.install(this) { diag.snapshot() }
    }
}
