package com.akira.clock

import android.app.Application
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.ClockRepository
import com.akira.clock.data.SettingsStore
import com.akira.clock.data.ThemeMode

/** Process-level owner of lazily initialized local data dependencies. */
class ClockApplication : Application() {
    internal val database: ClockDatabase by lazy { ClockDatabase.get(this) }
    internal val repository: ClockRepository by lazy { ClockRepository(database) }
    internal val settings: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        applyThemeMode(settings.themeMode)
    }

    internal fun applyThemeMode(mode: ThemeMode) {
        val delegateMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(delegateMode)
    }
}
