package app.waylo.core.data

/** Switches only adb can set, for trying the routing fallbacks on a test phone. Set once at launch
 *  from `WayloApp` (`adb shell setprop debug.waylo.tune.<key> 1`, then restart the app). */
object RouterTestHooks {
    /** `openRouterDown`: the open router answers nothing, so a drive runs on Google's routes and the
     *  downloaded region (the reroute fallback, SPEC 4.4). */
    @Volatile var openRouterDown: Boolean = false
}
