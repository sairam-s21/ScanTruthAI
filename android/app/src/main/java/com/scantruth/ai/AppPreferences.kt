package com.scantruth.ai

import android.content.Context
import android.content.SharedPreferences

/** Settings from the Profile cards (same keys and defaults as iOS and web). Stored on this device only. */
object AppPreferences {
    const val CRITICAL_ALLERGEN_ALERTS = "pref.criticalAllergenAlerts"
    const val INTOLERANCE_ALERTS = "pref.intoleranceAlerts"
    const val WEEKLY_DIGEST = "pref.weeklyDigest"
    const val STRICT_SAFETY_PENALTIES = "pref.strictSafetyPenalties"

    private const val FILE = "scantruth_settings"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** Falls back to the default when not initialised, e.g. in JVM unit tests. */
    fun bool(key: String, default: Boolean): Boolean = prefs?.getBoolean(key, default) ?: default

    fun set(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }
}
