package com.scantruth.ai.ui.navigation

/**
 * Every route in the app graph, declared once so navigation calls cannot drift from
 * the destinations that actually exist.
 */
object ScanTruthDestination {
    const val HOME = "home"
    const val SCAN = "scan"
    const val MY_HEALTH = "my_health"
    const val PROFILE = "profile"

    /** Pushed screens — these hide the bottom bar and are reached only from a flow. */
    const val SCAN_HISTORY = "scan_history"
    const val PRIVACY_REVIEW = "privacy_review"
    const val REPORT_RESULT = "report_result"
    const val INGREDIENT_RESULT = "ingredient_result"

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
