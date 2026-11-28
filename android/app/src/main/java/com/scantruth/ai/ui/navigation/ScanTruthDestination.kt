package com.scantruth.ai.ui.navigation

/**
 * Every route in the app graph, declared once so navigation calls cannot drift from
 * the destinations that actually exist. Like the website, results, history and the
 * privacy review open as sheets over these four tabs rather than as pushed screens.
 */
object ScanTruthDestination {
    const val HOME = "home"
    const val SCAN = "scan"
    const val MY_HEALTH = "my_health"
    const val PROFILE = "profile"

    /** The four tabs of the bottom bar, in display order. */
    val tabs: List<String> = listOf(HOME, SCAN, MY_HEALTH, PROFILE)

    fun isTopLevel(route: String?): Boolean = route != null && route in tabs

    fun label(route: String): String = when (route) {
        HOME -> "Home"
        SCAN -> "Scan"
        MY_HEALTH -> "My Health"
        PROFILE -> "Profile"
        else -> route
    }
}
