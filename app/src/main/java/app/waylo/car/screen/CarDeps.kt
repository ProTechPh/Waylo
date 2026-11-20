package app.waylo.car.screen

import androidx.car.app.CarContext
import app.waylo.car.CarMapRenderer
import app.waylo.core.data.MapDataSource
import app.waylo.core.data.PlaceShortcutStore
import app.waylo.core.data.RecentPlaceStore
import app.waylo.core.data.RouteEngine
import app.waylo.core.data.SavedPlaceStore
import app.waylo.core.location.LocationProvider
import app.waylo.core.nav.NavSession
import app.waylo.core.voice.VoiceGuide

/** The `:core` singletons the car screens share (all the same instances the phone app uses). */
data class CarDeps(
    val navSession: NavSession,
    val locationProvider: LocationProvider,
    val mapDataSource: MapDataSource,
    val recentPlaces: RecentPlaceStore,
    val savedPlaces: SavedPlaceStore,
    val shortcuts: PlaceShortcutStore,
    val voiceGuide: VoiceGuide,
    val routeEngine: RouteEngine, // for the speed-limit badge (offline graphs' max_speed)
    // Offline search on the car: the same on-phone place and address stores the phone's offline
    // search reads, so a car search with no signal still finds what the downloaded packs hold.
    val offlinePois: app.waylo.core.data.OfflinePoiStore,
    val offlineAddresses: app.waylo.core.data.OfflineAddressStore,
    // The shared client, for the UK fuel price file the result rows read (UkFuelPrices.fill).
    val http: okhttp3.OkHttpClient,
) {
    // ONE shared map renderer for the whole car session. Per-screen renderer instances DON'T work:
    // swapping the surface callback to a new instance doesn't re-deliver onSurfaceAvailable, so the
    // new renderer never gets the surface and the map freezes. All screens use this same instance
    // (set as the callback in each onStart) and just switch its mode (browse / preview / nav).
    private var sharedRenderer: CarMapRenderer? = null
    fun mapRenderer(ctx: CarContext): CarMapRenderer =
        sharedRenderer ?: CarMapRenderer(ctx, locationProvider, navSession, routeEngine).also { sharedRenderer = it }
    fun stopMapRenderer() { sharedRenderer?.stop() }
}
