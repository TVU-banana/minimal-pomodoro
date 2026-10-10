package com.akira.clock.data

import android.content.Context
import android.content.SharedPreferences
import com.akira.clock.report.PosterSections

/**
 * Shared preferences wrapper managing application theme, durable backup URI, and
 * poster preferences.
 */
internal class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("clock_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() {
            val value = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
            return ThemeMode.valueOf(value ?: ThemeMode.SYSTEM.name)
        }
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    /** URI of the user-owned archive kept outside app storage, or null until it is created. */
    var durableArchiveUri: String?
        get() = prefs.getString(KEY_DURABLE_ARCHIVE_URI, null)
        set(value) {
            prefs.edit().putString(KEY_DURABLE_ARCHIVE_URI, value).apply()
        }

    var posterSections: PosterSections
        get() = PosterSections(
            showChart = prefs.getBoolean(KEY_POSTER_SHOW_CHART, true),
            showReflection = prefs.getBoolean(KEY_POSTER_SHOW_REFLECTION, true),
            showMotivation = prefs.getBoolean(KEY_POSTER_SHOW_MOTIVATION, true),
        )
        set(value) {
            prefs.edit()
                .putBoolean(KEY_POSTER_SHOW_CHART, value.showChart)
                .putBoolean(KEY_POSTER_SHOW_REFLECTION, value.showReflection)
                .putBoolean(KEY_POSTER_SHOW_MOTIVATION, value.showMotivation)
                .apply()
        }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DURABLE_ARCHIVE_URI = "durable_archive_uri"
        private const val KEY_POSTER_SHOW_CHART = "poster_show_chart"
        private const val KEY_POSTER_SHOW_REFLECTION = "poster_show_reflection"
        private const val KEY_POSTER_SHOW_MOTIVATION = "poster_show_motivation"
    }
}

internal enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}
