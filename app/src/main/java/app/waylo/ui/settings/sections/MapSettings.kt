package app.waylo.ui.settings.sections

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.waylo.R
import app.waylo.ui.StartView
import app.waylo.ui.dpadHighlight // D-pad-only operation (docs/dpad.md)
import app.waylo.ui.settings.GroupDivider
import app.waylo.ui.settings.settingsAnchor
import app.waylo.ui.settings.SettingsGroup
import app.waylo.ui.settings.SettingsScaffold
import app.waylo.ui.settings.Hint
import app.waylo.ui.settings.SelectableRow
import app.waylo.ui.settings.ToggleRow

/** Map sub-screen: how the map looks and moves (traffic, transit, topography, layers button,
 * tilt, north lock, 3D, missing-building fill, house numbers). Cameras live under Navigation, places under Places
 * (settings reshuffle, 2026-09-17). */
@Composable
internal fun MapSettingsScreen(onBack: () -> Unit, homeSet: Boolean = false) {
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_map), onBack) { topRow ->
        Spacer(Modifier.height(4.dp))
        SettingsGroup {
        // The top focusable control: Back routes its DOWN here, UP from here goes back to Back.
        LiveTrafficRow(switchModifier = topRow)
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_transit_layer),
            checked = app.waylo.ui.TransitLayer.on.value,
            onCheckedChange = { app.waylo.ui.TransitLayer.set(context, it) },
            hint = stringResource(R.string.settings_transit_layer_hint),
        )
        // Which kinds of line (discussion #648). Shown only while the lines are on.
        if (app.waylo.ui.TransitLayer.on.value) {
            ToggleRow(
                label = stringResource(R.string.settings_transit_lines_metro),
                checked = app.waylo.ui.TransitLayer.metro.value,
                onCheckedChange = { app.waylo.ui.TransitLayer.setMetro(context, it) },
            )
            ToggleRow(
                label = stringResource(R.string.settings_transit_lines_trains),
                checked = app.waylo.ui.TransitLayer.trains.value,
                onCheckedChange = { app.waylo.ui.TransitLayer.setTrains(context, it) },
            )
        }
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_topography),
            checked = app.waylo.ui.Topography.on.value,
            onCheckedChange = { app.waylo.ui.Topography.set(context, it) },
            hint = stringResource(R.string.settings_topography_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_layers_button),
            checked = app.waylo.ui.LayersButton.on.value,
            onCheckedChange = { app.waylo.ui.LayersButton.set(context, it) },
            hint = stringResource(R.string.settings_layers_button_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_parking_button),
            checked = app.waylo.ui.ParkingButton.on.value,
            onCheckedChange = { app.waylo.ui.ParkingButton.set(context, it) },
            hint = stringResource(R.string.settings_parking_button_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_two_finger_tilt),
            checked = app.waylo.ui.MapTilt.on.value,
            onCheckedChange = { app.waylo.ui.MapTilt.set(context, it) },
            hint = stringResource(R.string.settings_two_finger_tilt_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_keep_north_up),
            checked = app.waylo.ui.NorthLock.on.value,
            onCheckedChange = { app.waylo.ui.NorthLock.set(context, it) },
            hint = stringResource(R.string.settings_keep_north_up_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_buildings_3d),
            checked = app.waylo.ui.Buildings3d.on.value,
            onCheckedChange = { app.waylo.ui.Buildings3d.set(context, it) },
            hint = stringResource(R.string.settings_buildings_3d_hint),
        )
        GroupDivider()
        ToggleRow(
            label = stringResource(R.string.settings_building_overlay),
            checked = app.waylo.ui.BuildingOverlay.on.value,
            onCheckedChange = { app.waylo.ui.BuildingOverlay.set(context, it) },
            hint = stringResource(R.string.settings_building_overlay_hint),
        )
        GroupDivider()
        StartViewRows(homeSet)
        // Category shortcuts under the search bar (issue #654): the row, one button, or none.
        GroupDivider()
        Text(
            stringResource(R.string.settings_category_chips),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.waylo.ui.CategoryChipsPref.Mode.ROW to stringResource(R.string.settings_category_chips_row),
            app.waylo.ui.CategoryChipsPref.Mode.BUTTON to stringResource(R.string.settings_category_chips_button),
            app.waylo.ui.CategoryChipsPref.Mode.HIDDEN to stringResource(R.string.settings_category_chips_hidden),
        ).forEach { (mode, label) ->
            SelectableRow(
                label = label,
                selected = app.waylo.ui.CategoryChipsPref.mode.value == mode,
                onClick = { app.waylo.ui.CategoryChipsPref.set(context, mode) },
            )
        }
        Hint(stringResource(R.string.settings_category_chips_hint))
        // House numbers: how far out they appear (issue #329). Numbers come from OpenStreetMap
        // and, in the US, OpenAddresses, so a missing number is usually missing data.
        GroupDivider()
        Text(
            stringResource(R.string.settings_house_numbers),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.settingsAnchor(stringResource(R.string.settings_house_numbers)).padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.waylo.ui.HouseNumbers.NEAR to stringResource(R.string.settings_house_numbers_near),
            app.waylo.ui.HouseNumbers.NORMAL to stringResource(R.string.settings_house_numbers_normal),
            app.waylo.ui.HouseNumbers.FAR to stringResource(R.string.settings_house_numbers_far),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.waylo.ui.HouseNumbers.level.value == id,
                onClick = { app.waylo.ui.HouseNumbers.set(context, id) },
            )
        }
        Hint(stringResource(R.string.settings_house_numbers_hint))
        // Which name a road or place label shows (issue #738): English where OpenStreetMap has
        // one, then the basemap's machine romanization or the local script, or the local name alone.
        GroupDivider()
        Text(
            stringResource(R.string.settings_map_names),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.settingsAnchor(stringResource(R.string.settings_map_names)).padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        listOf(
            app.waylo.ui.MapNames.ENGLISH_LATIN to stringResource(R.string.settings_map_names_english_latin),
            app.waylo.ui.MapNames.ENGLISH_LOCAL to stringResource(R.string.settings_map_names_english_local),
            app.waylo.ui.MapNames.LOCAL to stringResource(R.string.settings_map_names_local),
            app.waylo.ui.MapNames.LOCAL_ENGLISH to stringResource(R.string.settings_map_names_local_english),
        ).forEach { (id, label) ->
            SelectableRow(
                label = label,
                selected = app.waylo.ui.MapNames.mode.value == id,
                onClick = { app.waylo.ui.MapNames.set(context, id) },
            )
        }
        Hint(stringResource(R.string.settings_map_names_hint))
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** "Where the map opens": the phone's position, the view the map was left on, Home (offered only
 *  while one is saved), or a view picked from the map as it is now. A choice whose target is gone
 *  shows as "Where I am", which is how the map then opens. */
@Composable
private fun StartViewRows(homeSet: Boolean) {
    val context = LocalContext.current
    val title = stringResource(R.string.settings_start_view)
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.settingsAnchor(title).padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
    val shown = app.waylo.ui.shownStartMode(StartView.mode.value, homeSet, StartView.place.value != null)
    // Takes the view the map is on now, picks the option and says so.
    val useCurrent = {
        if (StartView.useLiveView(context)) {
            android.widget.Toast.makeText(context, context.getString(R.string.settings_start_view_saved), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    SelectableRow(
        label = stringResource(R.string.settings_start_view_here),
        selected = shown == StartView.HERE,
        onClick = { StartView.set(context, StartView.HERE) },
    )
    SelectableRow(
        label = stringResource(R.string.settings_start_view_last),
        selected = shown == StartView.LAST,
        onClick = { StartView.set(context, StartView.LAST) },
    )
    if (homeSet) {
        SelectableRow(
            label = stringResource(R.string.shortcut_home),
            selected = shown == StartView.HOME,
            onClick = { StartView.set(context, StartView.HOME) },
        )
    }
    SelectableRow(
        label = stringResource(R.string.settings_start_view_place),
        selected = shown == StartView.PLACE,
        // The first pick takes the current view. After that the row keeps the saved one and the
        // button below replaces it.
        onClick = { if (StartView.place.value != null) StartView.set(context, StartView.PLACE) else useCurrent() },
    )
    androidx.compose.foundation.layout.Box(Modifier.padding(start = 54.dp, end = 16.dp, bottom = 4.dp)) {
        androidx.compose.material3.FilledTonalButton(
            modifier = Modifier.dpadHighlight(androidx.compose.material3.ButtonDefaults.filledTonalShape),
            onClick = useCurrent,
        ) { Text(stringResource(R.string.settings_start_view_use_current)) }
    }
    // Driving mode (issue #727): "Where I am" in the drive's layout, for a mounted phone or tablet.
    SelectableRow(
        label = stringResource(R.string.settings_start_view_drive),
        selected = shown == StartView.DRIVE,
        onClick = { StartView.set(context, StartView.DRIVE) },
    )
    Hint(stringResource(R.string.settings_start_view_hint))
}
