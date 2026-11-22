package app.waylo.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** "Private roads in offline routes": lets the on-device OsmAnd router use ways tagged
 *  `access=private`, so a gated village can be cut through when the answer comes from a
 *  downloaded region. Off by default: most private ways are driveways and locked compounds, not
 *  shortcuts. Google's own routes are not affected. Read by `CoreModule` from the same key. */
object PrivateRoads {
    val on = mutableStateOf(false)

    fun init(context: Context) {
        on.value = prefs(context).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        on.value = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("waylo_settings", Context.MODE_PRIVATE)
    private const val KEY = "allow_private_roads"
}
