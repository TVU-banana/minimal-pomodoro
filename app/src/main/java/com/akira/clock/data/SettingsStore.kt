package com.akira.clock.data

import android.content.Context
import android.content.SharedPreferences

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

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DURABLE_ARCHIVE_URI = "durable_archive_uri"
    }
}

internal enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}
