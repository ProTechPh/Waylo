package app.waylo.ui.map

import android.content.Context
import app.waylo.core.data.FuelPh
import app.waylo.core.data.FuelPhData
import app.waylo.core.data.FuelPhStore
import app.waylo.core.model.Place
import app.waylo.offline.StorageLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * The DOE's city fuel price on an opened Philippine gas station (SPEC 5.9). The sheet is the only
 * place it is shown: it is a city's price, not the station's own, so it does not belong on a
 * result row or a map pin. Watches `MapUiState.selected`, and when that place is a Philippine gas
 * station with no city price yet, downloads the file on first use and fills it in. The sheet
 * redraws only when its place is still the one that was filled.
 */
internal class PhFuelPrices(
    appContext: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<MapUiState>,
    http: okhttp3.OkHttpClient,
) {
    private val store = storeFor(appContext, http)
    private val byCategory: (Place) -> Boolean = { UkFuelPrices.isFuelByCategory(it) }
    private var job: Job? = null

    fun bind() {
        scope.launch {
            state.map { it.selected }
                .distinctUntilChanged { a, b -> a === b }
                .collect { onPlace(it) }
        }
    }

    private fun onPlace(place: Place?) {
        if (place == null || !FuelPh.wants(place, byCategory)) return
        store.current?.let(::apply)
        if (job?.isActive == true) return
        job = scope.launch { if (store.ensure()) store.current?.let(::apply) }
    }

    private fun apply(data: FuelPhData) {
        state.update { s ->
            val selected = s.selected?.let { FuelPh.annotate(it, data, byCategory) }
            if (selected === s.selected) s else s.copy(selected = selected)
        }
    }

    companion object {
        // One store per process, shared by every sheet opened in it.
        @Volatile private var shared: FuelPhStore? = null

        private fun storeFor(context: Context, http: okhttp3.OkHttpClient): FuelPhStore = shared ?: synchronized(this) {
            shared ?: FuelPhStore(
                dir = { File(StorageLocation.root(context), FuelPhStore.FOLDER) },
                // A download client per CLAUDE.md: the shared client's call timeout cuts a body short.
                http = http.newBuilder()
                    .callTimeout(java.time.Duration.ZERO)
                    .readTimeout(java.time.Duration.ofSeconds(60))
                    .addInterceptor(app.waylo.diag.NetCount)
                    .build(),
            ).also { s ->
                shared = s
                app.waylo.ui.MemoryPressure.register { level -> if (app.waylo.ui.MemoryPressure.isSevere(level)) s.release() }
            }
        }
    }
}
